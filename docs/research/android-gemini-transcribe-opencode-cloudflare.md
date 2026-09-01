# Android voice input to OpenCode v2 on Cloudflare

Research date: 2026-08-31

## Verdict

Yes. An Android app can capture speech with Gemini 3.5 Transcribe, send the final text to a Cloudflare Worker, and have that Worker start or continue an OpenCode v2 session inside a Durable Object.

There is one important boundary. `gemini-3.5-transcribe` is speech-to-text only. It does not support function calling, thinking, or code execution, so OpenCode needs a separate tool-capable coding model such as a Gemini coding model or an OpenRouter model. The transcription becomes the user's prompt. It is not the agent model. [Gemini model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe)

The other boundary is Cloudflare execution. The current `@opencode-ai/sdk/workerd` profile can run the OpenCode host, model loop, sessions, plugins, and durable event storage. It cannot read a repository or run shell commands by itself. OpenCode's Cloudflare docs say the profile replaces unavailable local filesystem and process services. The published Workerd profile also marks filesystem reads, filesystem search, PTY, process spawning, shell-backed stdio MCP, snapshots, and VCS as unavailable or inert until a remote sandbox backs them. [OpenCode Cloudflare SDK guide](https://opencode.ai/v2/docs/build/sdk/cloudflare/) · [published Workerd profile types](https://cdn.jsdelivr.net/npm/@opencode-ai/server@0.0.0-dev-18728/dist/workerd.d.ts) · [published Workerd implementation](https://cdn.jsdelivr.net/npm/@opencode-ai/server@0.0.0-dev-18728/dist/workerd.js)

So there are two useful products:

- A voice agent whose tools are bundled Worker plugins and HTTP APIs. This works with the Workerd SDK now.
- A voice coding agent that edits and tests a real repository. This also needs a remote execution plane, for example a custom OpenCode plugin backed by Cloudflare Sandbox, or a separate container service. Cloudflare Sandbox runs full Linux containers but is available only on the Workers Paid plan. Its current stable package is pre-1.0, while 1.0 is still preview. [Cloudflare Sandbox overview](https://developers.cloudflare.com/sandbox/)

## Hardware trigger on Android

Long-press Power is the recommended path. Implement Talkt as a `VoiceInteractionService`, declare support for the assistant role, and ask the user to select Talkt as the device's default assistant. Android keeps the selected voice interaction service running, and the platform documentation names long-press Power as a dedicated entry point that activates the current service. The user must grant the role, and the gesture still depends on the device's SystemUI and Power-button setting, so test every target OEM. [VoiceInteractionService](https://developer.android.com/reference/android/service/voice/VoiceInteractionService) · [assistant role](https://developer.android.com/reference/android/app/role/RoleManager#ROLE_ASSISTANT)

This route also has the cleanest microphone behavior. Android lists apps that provide a `VoiceInteractionService` among the exceptions that may start microphone-capable foreground work from the background. The app still needs `RECORD_AUDIO`; on Android 14 and later it must also declare the `microphone` foreground-service type and `FOREGROUND_SERVICE_MICROPHONE`. Keep the always-running service light and put recording UI and heavy work in the associated voice interaction session. [background microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [microphone foreground-service requirements](https://developer.android.com/about/versions/14/changes/fgs-types-required)

Holding Volume Down globally has no ordinary app shortcut API. The available route is an enabled `AccessibilityService` with `canRequestFilterKeyEvents="true"` and `FLAG_REQUEST_FILTER_KEY_EVENTS`; its `onKeyEvent` callback receives hardware key events before apps, so it can recognize a `KEYCODE_VOLUME_DOWN` hold. If it consumes events, it must consume a well-formed down/up stream. Otherwise the press still changes volume. [Accessibility key filtering](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_REQUEST_FILTER_KEY_EVENTS) · [`onKeyEvent`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent))

Volume Down should be an optional expert mode, not the default product path. The user must manually enable the accessibility service. Google Play requires an Accessibility declaration, prominent disclosure, and affirmative consent when the app is not a genuine accessibility tool. Play permits narrow deterministic automation such as "Trigger X performs Action Y," but prohibits using Accessibility itself to autonomously initiate, plan, and execute actions. Talkt should use the service only to detect the key and must not claim `isAccessibilityTool` unless accessibility is its real core purpose. [Google Play Accessibility policy](https://support.google.com/googleplay/android-developer/answer/10964491)

The Accessibility callback alone is also not a reliable background microphone exemption. On Android 14 and later, starting a microphone foreground service while the app is backgrounded normally throws unless a listed exemption applies. The safe Volume Down flow is to open visible Talkt capture UI or post a notification that the user taps, then start the microphone service from that user-visible state. That is slower than the assistant route and can be affected by OEM background-launch rules. [foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

Power is therefore the production choice. Volume Down is feasible for sideloaded, enterprise, or explicitly disclosed expert builds, but it carries policy and device-compatibility risk.

## Recommended runtime

```text
Android microphone
  |  Live PCM audio or a recorded file
  v
Gemini 3.5 Transcribe
  |  authoritative final transcript
  v
Authenticated Cloudflare Worker endpoint
  |  select object from authenticated user + workspace
  v
OpenCode Durable Object
  |  one retained OpenCodeWorkerd host per object instance
  |  create/reuse session, then sessions.prompt(transcript)
  +--> coding-model provider
  +--> bundled HTTP/plugin tools
  +--> remote sandbox tools when repository execution is required
  |
  v
SSE or WebSocket event relay back to Android
```

Use one Durable Object per user-workspace or project, not one global object. Cloudflare recommends one object for each logical unit that needs coordination. SQLite-backed Durable Objects are available on both Free and Paid plans. [Durable Object design rules](https://developers.cloudflare.com/durable-objects/best-practices/rules-of-durable-objects/) · [Durable Object pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)

Inside the Durable Object constructor, retain a single `OpenCodeWorkerd.create(...)` promise behind `state.blockConcurrencyWhile(...)`. Do not create a host for every request. The host uses the object's SQLite storage and persists durable events for eviction recovery. Starting work means creating or reusing a session and calling `sessions.prompt(...)`; it does not spawn an OpenCode CLI process. [OpenCode Cloudflare SDK guide](https://opencode.ai/v2/docs/build/sdk/cloudflare/) · [OpenCode SDK overview](https://opencode.ai/v2/docs/build/sdk/)

The SDK opens no HTTP listener. The outer Worker must define the mobile API, authenticate it, route to the correct Durable Object, and relay results. OpenCode events are exposed as an `AsyncIterable`. The network client's subscriptions are live-only and do not replay or reconnect automatically, so the Android protocol should reconnect and refetch session context or an app-level persisted result after a dropped stream. [OpenCode SDK overview](https://opencode.ai/v2/docs/build/sdk/) · [OpenCode JavaScript client](https://opencode.ai/v2/docs/build/client/)

## Transcription choices

### Live dictation

Use `gemini-3.5-transcribe-live` over the Gemini Live API WebSocket. Android sends raw 16-bit PCM, 16 kHz, mono, little-endian audio in roughly 100 ms chunks. Render `interim_input_transcription` as disposable preview text, and submit only the authoritative `input_transcription` event to OpenCode. A live transcription session lasts at most 10 minutes. Live mode has no speaker diarization or word-level timestamps. [Live transcription guide](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)

For the lowest latency, the phone can connect directly to Gemini with an ephemeral token minted by the Worker. Ephemeral tokens are Preview, Live API only, and `v1beta` only. By default, the app has one minute to start one session and the token expires after 30 minutes. [Gemini ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens)

### Recorded command

Upload audio through the Worker and call `POST https://generativelanguage.googleapis.com/v1beta/interactions` with model `gemini-3.5-transcribe`. This path supports up to one hour of audio, or 30 minutes when diarization or word timestamps are enabled. It supports 85+ languages, custom vocabulary, up to eight speakers, and word timestamps. Batch API is not supported. [Audio transcription guide](https://ai.google.dev/gemini-api/docs/transcribe) · [Gemini model limits](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe)

For short voice commands, Live mode is the better fit. Recorded mode is useful when the app must retry a durable audio artifact or process a meeting.

## Authentication and secrets

There are three separate trust boundaries:

1. Android to Talkt. The app sends its normal user token, such as a Clerk JWT, to the Worker. The Worker verifies it before choosing a Durable Object. Never accept a user-supplied Durable Object name as authorization.
2. Talkt to Gemini Transcribe. Store the Gemini auth key as a Cloudflare secret. Google says not to embed long-lived Gemini keys in mobile apps. Use a backend proxy for unary transcription, or mint a constrained ephemeral token for direct Live API access. Standard Gemini keys stop working in September 2026, so provision an auth key. [Gemini key security](https://ai.google.dev/gemini-api/docs/api-key)
3. OpenCode to its coding-model provider. The preferred connection can be a per-user ChatGPT Plus/Pro OAuth credential stored by OpenCode in the Durable Object database. Keep `OPENROUTER_API_KEY` as a separate Cloudflare secret for the paid fallback. OpenCode resolves provider credentials from stored connections, declared environment variables, or `settings.apiKey`. [OpenCode providers](https://opencode.ai/v2/docs/providers) · [Cloudflare Workers secrets](https://developers.cloudflare.com/workers/configuration/secrets/)

The OpenCode Cloudflare profile requires `nodejs_compat`. With a current compatibility date, Cloudflare also makes Worker variables and secrets available through `process.env`, which matches OpenCode provider discovery. Do not put keys in `wrangler.jsonc` or ship them in the APK. [OpenCode Cloudflare SDK guide](https://opencode.ai/v2/docs/build/sdk/cloudflare/) · [Cloudflare environment variables](https://developers.cloudflare.com/workers/configuration/environment-variables/)

The public `OpenCodeWorkerd.create(...)` API is in-process and does not authenticate the Android request for the app. Authentication belongs in the outer Worker route.

## Refined routing: dictation in a text field, agent everywhere else

Talkt should make the routing decision before it submits the finalized transcript:

```text
hardware trigger
  |
  +-- safe editable field is active
  |     -> Gemini Transcribe Live, SMART mode
  |     -> lightweight grammar-only rewrite
  |     -> insert at the current selection
  |     -> never start OpenCode
  |
  +-- no safe editable field
        -> Gemini Transcribe final transcript
        -> OpenCode session, ChatGPT OAuth model first
        -> OpenRouter only under the fallback policy
```

Do not hard-code WhatsApp as the switch. Treat WhatsApp as one host application and classify the current editor. `EditorInfo.packageName` can identify the host for diagnostics, while `EditorInfo.inputType` says whether the target is a text editor and what kind. Exclude password and visible-password variations, numeric/PIN fields, payment fields, and any editor that asks the IME not to learn from its contents. If classification is uncertain, do not read or replace existing text; show a small choice between **Insert text** and **Run agent**. [EditorInfo](https://developer.android.com/reference/android/view/inputmethod/EditorInfo) · [InputType](https://developer.android.com/reference/android/text/InputType)

For the text path, configure `gemini-3.5-transcribe-live` with `inputAudioTranscription.mode = "SMART"`. Google says Smart mode removes disfluencies, resolves spoken self-corrections, formats structure, and applies grammar, casing, and punctuation. Commit only `server_content.input_transcription`; interim text is a preview. Then, if the product wants stronger correction than Smart mode already supplies, send the final transcript to a narrow `gemini-3.5-flash-lite` rewrite endpoint with instructions to preserve language, meaning, names, mentions, URLs, emoji, and tone and to return only the rewritten text. Flash-Lite is Google's stable low-latency, cost-focused text model. If that rewrite times out or fails, insert the Smart transcript rather than routing the user's message into OpenCode. [Gemini Live Smart transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe) · [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite)

Smart transcription already performs grammatical cleanup. The second model is therefore an optional polish stage, not a requirement for basic dictation. Keep it stateless, tool-free, bounded to the one utterance, and separate from OpenCode sessions.

### Detecting and editing the focused field

The Android APIs have materially different guarantees:

| Route | What it proves | How it writes | Caveats |
| --- | --- | --- | --- |
| `InputMethodService` plus `InputConnection` | Talkt is the currently selected IME and has an active editor | `commitText()` replaces the selection or inserts at the cursor; surrounding-text and selection APIs support deliberate edits | Strongest and most compatible route, but the user must select Talkt as the keyboard/voice IME. It cannot borrow Gboard's `InputConnection`. |
| Accessibility input method APIs, Android 13 / API 33+ | An enabled `AccessibilityService` with `FLAG_INPUT_METHOD_EDITOR` has an active editor | `InputMethod.AccessibilityInputConnection.commitText()` inserts at the selection without replacing the whole field | Best companion to Talkt's optional Volume Down accessibility service on modern Android. It still carries Accessibility permission and Play-policy obligations. |
| `AccessibilityNodeInfo.ACTION_SET_TEXT`, API 21+ | The accessibility tree exposes an input-focused, editable node that advertises the action | Replaces the node's complete text and moves the cursor to the end | Compatibility fallback only. Talkt must reconstruct `prefix + rewritten selection + suffix`; the read/action gap can overwrite concurrent edits, and custom or embedded view hierarchies may not expose the focused node. |
| `VoiceInteractionSession` plus `AssistStructure` | The assistant received a snapshot of the foreground activity; a `ViewNode` can report input focus, text, selection, and input type | No write API | Useful as a classifier for a Power-button assistant session, not as an insertion mechanism. Assist data may be null, blocked by policy, or an empty stub for a secure window. |

Android's IME guide explicitly defines `InputConnection` as the channel for editing text around the cursor and `commitText()` as the operation that inserts text or replaces the selection. On Android 13 and later, an accessibility service can obtain a restricted accessibility input connection after declaring `FLAG_INPUT_METHOD_EDITOR`, so Talkt does not have to replace the user's keyboard merely to get cursor-correct insertion on supported devices. [Android IME guide](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method) · [accessibility `InputMethod`](https://developer.android.com/reference/android/accessibilityservice/InputMethod) · [`AccessibilityInputConnection.commitText`](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#commitText(java.lang.CharSequence,int,android.view.inputmethod.TextAttribute))

For the older fallback, find `FOCUS_INPUT` across interactive windows, require `node.isEditable`, and verify that `ACTION_SET_TEXT` is present before acting. `ACTION_SET_TEXT` is whole-field replacement and puts the cursor at the end; it is not equivalent to `InputConnection.commitText()`. `findFocus()` can also miss a focused node inside an embedded `SurfaceView` hierarchy. [AccessibilityService.findFocus](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#findFocus(int)) · [`ACTION_SET_TEXT`](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction#ACTION_SET_TEXT)

`AssistStructure` is still useful for the Power-button route: select the focused `AssistState`, traverse to the `ViewNode` whose `isFocused()` is true, and inspect its input type and selection. It is a point-in-time, read-only structure. Do not treat it as proof that the field remains focused after transcription, and revalidate through an input connection or accessibility node immediately before insertion. [VoiceInteractionSession.onHandleAssist](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession#onHandleAssist(android.service.voice.VoiceInteractionSession.AssistState)) · [AssistStructure.ViewNode](https://developer.android.com/reference/android/app/assist/AssistStructure.ViewNode)

The recommended insertion order is therefore:

1. Use the API 33+ accessibility input connection when the Accessibility service is enabled.
2. Otherwise use a normal `InputConnection` only when Talkt is the active IME.
3. Otherwise use `ACTION_SET_TEXT` only after an explicit user trigger, with a last-moment focus and text check.
4. If none is safe, copy the corrected text to a Talkt preview for manual paste. Do not silently route it to OpenCode.

## ChatGPT OAuth as the OpenCode primary

OpenCode's current V2 OpenAI integration offers two OAuth methods: `chatgpt-browser` and `chatgpt-headless`. The browser method starts a localhost callback server using `node:http`, so it is not suitable for the Workerd profile, which replaces process and other local services. Use the headless device-code method instead. It asks `auth.openai.com` for a user code, returns `https://auth.openai.com/codex/device` plus the code to the client, polls for authorization, and exchanges the authorization code for access and refresh tokens. The Android app should display the code and open that URL in a Custom Tab. This is the same ChatGPT Plus/Pro subscription connection OpenCode documents for its normal client, but the Workerd/headless combination is evidenced by the current published V2 package rather than promised on the short Cloudflare guide. [OpenCode OpenAI provider login](https://opencode.ai/docs/providers/#openai) · [published V2 OpenAI integration, pinned dev build](https://cdn.jsdelivr.net/npm/@opencode-ai/core@0.0.0-dev-18729/dist/chunks/persistent-pty-fg5ehpqc.js) · [Workerd replacements](https://cdn.jsdelivr.net/npm/@opencode-ai/server@0.0.0-dev-18729/dist/workerd.js)

The login attempt itself is in-memory. OpenCode keeps the device-code attempt and its polling fiber in process state until it completes; if Cloudflare evicts or restarts the object during login, that attempt can be lost and the user must start again. This is an inference from the published implementation, not an explicit OpenCode guarantee. Keep the Durable Object active while login is pending, poll its attempt status from Android, and make the flow safely restartable.

After authorization succeeds, the durable boundary is sound:

- OpenCode stores an OAuth value containing `methodID`, `refresh`, `access`, `expires`, and optional account metadata in its `credential` SQLite table.
- The Workerd profile replaces OpenCode's database layer with the injected Durable Object SQLite storage, so the credential survives host and object eviction.
- When a credential is resolved within five minutes of expiry, OpenCode exchanges the refresh token at the OpenAI token endpoint and updates the same stored credential before sending the model request.

These details come from the pinned V2 published package, whose Cloudflare profile is still distributed through a moving `@dev` release. Pin the exact tested package, add an eviction/restart test after login and after refresh, and re-audit the implementation before upgrading. [published credential schema and store](https://cdn.jsdelivr.net/npm/@opencode-ai/core@0.0.0-dev-18729/dist/chunks/persistent-pty-e6b95e10.js) · [published credential table](https://cdn.jsdelivr.net/npm/@opencode-ai/core@0.0.0-dev-18729/dist/chunks/persistent-pty-mf0hyk7z.js) · [OpenCode Workerd SDK](https://opencode.ai/v2/docs/build/sdk/cloudflare/)

Durable Object storage supports this technically: it is private to the object, transactional, strongly consistent, persistent across restarts, and encrypted at rest by Cloudflare. It is not a secrets vault from the Worker's point of view—Talkt code running in that object can read the token. Use one credential-owning object per authenticated Talkt user, derive its ID only from the verified user identity, never log credential rows, expose explicit disconnect/revoke behavior, and do not copy one user's ChatGPT token into project objects owned by other users. [SQLite-backed Durable Object storage](https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/) · [Durable Object data security](https://developers.cloudflare.com/durable-objects/reference/data-security/)

ChatGPT OAuth does not grant every model visible in ChatGPT. OpenCode filters the model catalog for subscription use, and that list changes with the pinned build. The inspected `0.0.0-dev-18729` package explicitly disables some entries, including `gpt-5.6` and `gpt-5.5-pro`, while enabling its current Codex-compatible set. Select only an enabled model returned by the runtime catalog; do not hard-code the model assumed to be available from the user's ChatGPT plan. [published V2 OpenAI integration](https://cdn.jsdelivr.net/npm/@opencode-ai/core@0.0.0-dev-18729/dist/chunks/persistent-pty-fg5ehpqc.js) · [OpenCode V2 model selection](https://opencode.ai/v2/docs/models)

## OpenRouter fallback, precisely defined

OpenRouter is a separate provider and billing relationship. A ChatGPT OAuth token cannot be sent to OpenRouter, so configure `OPENROUTER_API_KEY` independently as a Worker secret or as a separate per-user OpenCode connection. Keep the primary session model on `openai/...` and select an `openrouter/...` model only when Talkt's orchestration policy chooses the fallback. The V2 provider package is `@opencode-ai/ai/providers/openrouter`. [OpenCode V2 providers](https://opencode.ai/v2/docs/providers) · [OpenRouter's OpenCode integration](https://openrouter.ai/docs/cookbook/coding-agents/opencode-integration)

There are three different behaviors that are easy to call "fallback":

| Behavior | Owner | Meaning |
| --- | --- | --- |
| OpenCode default-model fallback | OpenCode catalog | If the configured default is unavailable before a run, OpenCode chooses an available supported model. This is not failover after a provider request starts. |
| OpenRouter provider fallback | OpenRouter | One OpenRouter model ID is tried through another upstream host for that same model. `provider.allow_fallbacks` defaults to `true`; `order`, `only`, data policy, and parameter requirements constrain the hosts. |
| OpenRouter model fallback | OpenRouter | A `models` array supplies different model IDs in priority order. OpenRouter advances on documented failures such as provider downtime, rate limiting, or moderation refusal. |

[OpenCode default model semantics](https://opencode.ai/v2/docs/models#default-model) · [OpenRouter provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) · [OpenRouter model fallbacks](https://openrouter.ai/docs/guides/routing/model-fallbacks)

Neither OpenRouter mechanism automatically turns a direct ChatGPT OAuth request into an OpenRouter request. For that cross-provider boundary, Talkt must orchestrate explicitly around OpenCode:

1. Start the run with the enabled ChatGPT OAuth model.
2. Let OpenCode perform its bounded retries for same-model retryable failures.
3. On a terminal rate-limit, provider-internal, transport, quota, or route-availability failure, select the configured OpenRouter model for a fallback attempt.
4. Do not auto-fallback invalid requests, unsupported tools, context errors, authentication/revocation, or content-policy failures. Those need correction, re-login, compaction, or a user-visible refusal rather than a second provider.
5. Mark the fallback in the event stream and UI because it changes provider, privacy routing, model behavior, and cost. Make automatic paid fallback an explicit saved user setting.

OpenCode V2 currently retries the selected model; its root `model` field is a default selection, not a runtime error chain. A cross-provider retry also needs session semantics: do not append the user's command twice to one canonical conversation. The safest design is one Talkt run record with primary and fallback attempts, then attach only the successful attempt's response to the visible run. If the SDK cannot keep failed attempts out of the canonical OpenCode session, keep separate provider-attempt sessions and store the Talkt run mapping outside OpenCode. This is a product orchestration requirement, not a built-in OpenCode failover guarantee.

## Current status and limits

- Google's launch blog calls the models Public Preview, but the current Gemini API release notes from the same date explicitly mark both Transcribe models GA. The model page also uses stable IDs without a preview suffix. Treat the API release notes as current, but recheck before release because Google's official pages conflict. [launch post](https://blog.google/innovation-and-ai/models-and-research/gemini-models/gemini-3-5-transcribe/) · [Gemini API release notes](https://ai.google.dev/gemini-api/docs/changelog)
- OpenCode v2, its API, and its Cloudflare SDK are beta. The documented install is `@opencode-ai/sdk@dev`, so pin an exact tested version rather than following the moving `dev` tag. [OpenCode v2 intro](https://opencode.ai/v2/docs) · [OpenCode SDK overview](https://opencode.ai/v2/docs/build/sdk/)
- Gemini Transcribe cannot be the OpenCode reasoning model because its model card explicitly disables function calling, thinking, and code execution. [Gemini model capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-transcribe)
- OpenCode Workerd is an orchestration and persistence host, not a Linux machine. A configured `build` agent does not restore the missing shell or filesystem.
- Cloudflare Durable Objects can persist OpenCode sessions on the Free plan, within that plan's quotas. Cloudflare Sandbox requires Paid.
- Current Gemini paid pricing is about $0.009 per live minute and $0.005 per recorded minute, with a free tier. The coding model and Cloudflare usage are additional costs. [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing)

## MVP proof gates

Build the smallest end-to-end slice before adding repository execution:

1. Android streams microphone audio and shows interim text.
2. One finalized utterance reaches an authenticated Worker endpoint.
3. The Worker routes by verified user and workspace, creates or resumes the Durable Object session, and calls `sessions.prompt` with the exact transcript.
4. A separate tool-capable model returns a response, and the app receives it over a reconnect-safe event protocol.
5. Evict or restart the Durable Object and confirm session state survives.
6. Only then add a remote sandbox tool and prove clone, read, edit, test, and persisted repository state. This is the step that turns the voice agent into a coding agent.

The clean first milestone is therefore voice to persistent OpenCode conversation on Cloudflare. Real code editing is a second milestone with its own sandbox and security design.

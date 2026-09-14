# Talkt Telegram and ChatGPT

September 7, 2026

Telegram has inline buttons for new chats, conversations, settings, approvals and stop. YOLO stays enabled across restarts and runs tools without approval prompts. Only the personal owner can change it. Explicit denials and duplicate-execution protection remain enforced.

ChatGPT subscription login completed through OpenCode V2 with the approved account. A live reply returned TALKT_CHATGPT_OK_907. OpenCode recorded openai/gpt-5.5 with fallback false.

## Verification

| Check | Result |
| --- | --- |
| Menu and Settings callbacks | Verified in Telegram Desktop |
| Approval button | Allowed once, then GitHub returned OxFrancesco |
| YOLO tool call | Fresh GitHub read completed without a prompt |
| ChatGPT OAuth | Browser authorization and connected status verified |
| ChatGPT inference | Actual OpenAI model reply, no OpenRouter fallback |
| Worker tests | 38 passed after Pigent integration |
| Runner tests | 11 passed, including encrypted transport and replay checks |
| Type checks | Worker, runner and Pigent passed |

## ChatGPT transport

Cloudflare receives an HTML HTTP 403 from ChatGPT's model endpoint. The existing Mac runner forwards that request to the same endpoint. Requests and responses are compressed and encrypted before entering the durable queue and local journal. The relay accepts only the ChatGPT responses endpoint, rejects redirects, and sends large encrypted responses in bounded segments. Cached encrypted response segments expire after ten minutes.

ChatGPT requests currently require the Mac runner to stay online and awake. OpenRouter remains selectable in Settings. A failed ChatGPT request does not silently switch providers.

## Source

Telegram controls remain in Talkt. The shared runtime lives in Pigent. Pigent accepts optional personal plugins, and Talkt supplies the ChatGPT relay plugin. The concurrent backend extraction is preserved.

## Final deployment proof

Worker version 9ce7377a-bd68-4802-9e1f-e64f532a412e. After deployment, the combined ChatGPT and YOLO test returned CHATGPT_YOLO_OK OxFrancesco. Run tg_408110734 completed with model openai/gpt-5.5 and fallback false. The Mac journal records exactly one GitHub execution for this run.

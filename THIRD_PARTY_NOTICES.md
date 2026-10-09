# Third-party notices

## Parakeet

The optional on-device transcription mode uses QVAC's Parakeet C++ engine under Apache 2.0 and QVAC's GGML speech backend under MIT. OpenCL headers are provided by the Khronos Group under Apache 2.0. Talkt's generated OpenCL dispatch bridge loads the phone's vendor driver directly. The pinned native build copies their full licenses into the APK's `assets/licenses/` directory.

- Engine: https://github.com/tetherto/qvac-fabric-speech.cpp
- GGML: https://github.com/tetherto/qvac-ext-ggml
- OpenCL headers: https://github.com/KhronosGroup/OpenCL-Headers

NVIDIA Parakeet model weights are imported separately, not bundled in the APK. NVIDIA's CTC 0.6B and TDT 0.6B v3 checkpoints use Creative Commons Attribution 4.0 International, CC BY 4.0. QVAC converts and quantizes them to GGUF. Talkt does not modify the imported weights.

- Original models and author: NVIDIA, https://huggingface.co/nvidia/parakeet-ctc-0.6b and https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3
- License: https://creativecommons.org/licenses/by/4.0/

The APK includes attribution and the full model license in `assets/licenses/`.

## Thinking orbs

The dotted ribbon geometry is derived from `thinking-orbs` by Jakub Antalik.

MIT License

Copyright (c) 2026 Jakub Antalik

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

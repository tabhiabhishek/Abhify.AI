# Vendored: WebLLM (on-device AI in the browser)

Used only by the **on-phone AI mode** (`frontend/ondevice.js`), loaded lazily. Nothing here runs in PC mode.
These files are **not modified**. `test_ondevice.py` checks every hash below.

| File | Source | Version / commit | SHA-256 |
|---|---|---|---|
| `web-llm-0.2.85.js` | npm `@mlc-ai/web-llm@0.2.85`, `lib/index.js` (registry integrity `sha512-CwUiSAvDCYnmAZlpPMkurPHDfBLq6fvdRB3vRgoA894hRG/V/BSeY9bmMm6iIIU5VpjJxfb/dLPpJX2at0L9rw==`, verified) | 0.2.85 (published 2026-09-08) | `341bae95822bfee1d0fd6a0e6cd2db8613bb8edf809390ac142fba36ec17792c` |
| `Llama-3.2-1B-Instruct-q4f16_1_cs1k-webgpu.wasm` | github.com/mlc-ai/binary-mlc-llm-libs `web-llm-models/v0_2_84/base/` | commit `025bcaf3780fa8254f5e5efd3bfea0a5397248f4` (2026-05-19) | `2aa9b5f0c8de532f6cbf6bb7b863aaa02645bf6fff856083b202f968712d3f92` |
| `Qwen2-1.5B-Instruct-q4f16_1_cs1k-webgpu.wasm` | same repository and path (WebLLM's model library for Qwen2.5-1.5B) | commit `025bcaf3780fa8254f5e5efd3bfea0a5397248f4` | `0fceb50bbaf47efdc31fce96b72c115dcb7f5221c85abe6a9fc02dda9d1d6fc3` |
| `LICENSE` | from the npm package | Apache-2.0 | `d412ab9d5ac17e6931705aac01e5a0d323da5acd2e89a2c19aa8fc05becc59ad` |

What is still downloaded at run time, and only after the user confirms:
- The model **weights** (data, not code) from `huggingface.co/mlc-ai/<model>` over HTTPS. They are stored in the phone's browser cache by WebLLM.
- Nothing the user types is sent anywhere.

Updating: download the new versions, verify the npm integrity and the upstream commit, replace the files, update this table and the test, and review the diff.

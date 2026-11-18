# Chat microphone latency: measured, explained, improved (2026-11-09)

## The voice-to-answer pipeline
**Desktop/web:**
1. **The mic starts:** browser speech recognition (Web Speech API), or in Private voice mode the browser records audio and the backend's Whisper transcribes it (`/transcribe`).
2. **Transcript:** `handleTranscript`.
3. **Chat:** `sendChat` sends the question to the chat endpoint.
4. **Backend agent:** the LangGraph agent with tools runs on Ollama.
5. **Answer:** the answer is shown, then read aloud.

**Android:** Android's speech recognizer fills the message box, and you review it and tap Send (never sent automatically). The answer comes from the phone model (streamed) or your provider.

## How it was measured
Measurement script `voice_latency.py` ran against an isolated data folder and real Ollama 0.40.13 with qwen2.5 7B (the default) on this PC.
- **Audio:** spoken questions made offline by Windows' own speech synthesizer, sent through the real `/transcribe` (Whisper base).
- **Timings:** from Ollama's own metadata: load, prompt evaluation and generation (`record_inference` now also reports `prompt_seconds`).
- **"Cold":** models unloaded first, and Whisper not yet loaded (a fresh backend).

## Baseline (before)
| Stage | Cold | Warm |
|---|---|---|
| Whisper transcription | **7.92 s** (model load) | 0.21–0.42 s |
| Chat model load (Ollama `load_duration`) | **15.41 s** | 0 s |
| Prompt evaluation (551 tokens) | 0.56 s | 0.02–0.03 s |
| Model calls per question | 1 (no tool round, no duplicate inference) | 1 |
| First words visible | 21.32 s after the transcript (the whole answer) | **4.17–14.87 s** (the whole answer: `/chat` wasn't streamed) |
| Generation speed | 44–45 tokens/s | 44–45 tokens/s |

The three bottlenecks, in order:
1. the cold chat-model load;
2. waiting for the complete answer before showing anything;
3. the cold Whisper load.

What was ruled out:
- prompt preparation (milliseconds);
- network to the local backend;
- duplicate inference;
- a tool round on these questions.

## What changed
1. **Streamed answers:**
   - `POST /chat/stream` is the same chat as `/chat` (same agent, history, sources, memory and project rules), sent as newline-delimited JSON.
   - The web chat shows tokens as they arrive, with a Stop button. A stopped answer saves nothing.
   - Guest devices and older servers fall back to `/chat`.
2. **Warm-up when the microphone starts:**
   - `POST /chat/warm` loads the chat's chosen model (never another) and, in Private voice mode, Whisper, while you speak. It generates nothing and changes no setting.
   - The one-chat-model rule still applies, and a request answers at most once a minute.
   - **Android** does the same: when the next answer comes from the phone model and it isn't loaded, the mic start loads it. A Send during the load waits for it instead of loading twice.
3. **Not changed (needs your decision):** model choice, model size, keep-alive time and Whisper beam size. Quality is unchanged.

## After
| Stage | Before | After |
|---|---|---|
| Warm: first words visible after the transcript | 4.17–14.87 s | **0.11–0.37 s** |
| Warm: full answer | 4.1–14.9 s | 4.1–13.5 s (same model, same speed) |
| Cold, short question: Whisper | 7.92 s | **0.62 s** (loaded during speech) |
| Cold, short question: end of speech → first words | 29.2 s | **14.3 s** |
| Cold, short question: end of speech → full answer | 29.2 s | **18.3 s** |
| Voice warm-up in the browser (real Edge) | – | loaded `qwen2.5` alone in 15.9 s, started at the mic press |

## Remaining bottleneck
The cold load of qwen2.5 7B (about 15.5 s, disk to VRAM). It overlaps more with longer questions. It can only shrink by keeping the model loaded longer, or by choosing a smaller default model. Both are your choice; neither was changed.

## Android
Not measured: no device was connected.
- Logging was added for on-device measurement, as timings only with no text:
  - `AbhifyVoice`: speech recognised after N ms; phone model warm-up done in N ms;
  - `AbhifyTask`: task duration.
- Collect them with `adb logcat -s AbhifyVoice AbhifyTask`.

## Tests
- **Backend:**
  - `test_stream_agent_yields_tokens_then_saves_like_chat`: tokens only from the answer (no tool text), history saved;
  - `test_chat_stream_endpoint_sends_ndjson_and_guests_are_refused`;
  - `test_voice_warm_up_loads_the_chosen_model_only`;
  - `test_inference_stats_report_prompt_time_for_first_token_latency`.
- **Browser:** streamed project chat with 54 partial renders before completion; warm-up loads exactly one chat model; warm-up throttled.
- **Live check:** after a streamed chat on qwen2.5 and a switch to llama3.2, only llama3.2 stays resident.

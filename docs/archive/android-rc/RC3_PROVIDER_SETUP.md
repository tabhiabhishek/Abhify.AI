# Abhify RC.3: hosted AI providers and API keys

Abhify works fully offline with the Qwen2.5 model on the phone. Hosted providers are **optional**: they give stronger answers over the phone's internet, using **your own free API key** on each phone, or **shared access** that your administrator configures on the Abhify server.

## 1. Get a free key
| Provider | Where | What "free" means (official docs, 2026-11-02) |
|---|---|---|
| **Google Gemini** | https://aistudio.google.com/apikey | Free **only while the key's Google project has no billing linked**. With billing linked, every request is charged. Free-tier models: `gemini-3.8-flash` (default), `gemini-3.6-flash`, `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`. Google states free-tier content is **used to improve its products**. |
| **Groq** | https://console.groq.com/keys | Free plan, rate-limited. Charges start only if the account is upgraded with a payment method. Default model `llama-3.3-70b-versatile`. |
| **OpenRouter** | https://openrouter.ai/keys | Only free models are offered: the **Free Models Router** `openrouter/free` (default) and models whose id ends in `:free` and whose price is 0. Free models allow 50 requests a day (1000 after buying ≥ 10 credits). |

## 2. Add it on the phone
**☰ → AI providers →** choose a provider:
1. Read the cost and privacy notes.
2. Paste the key.
3. **Validate & save.** The app checks the key with a **no-cost** request (listing the provider's models; for OpenRouter also reading the key's info). The key is saved **only if the check passes**.
4. Gemini and Groq ask you to confirm the free tier ("my Google project has no billing linked" / "my Groq account is on the Free plan"). The provider stays **not ready** until you tick it, because no API can tell the app.
5. Pick a model. Only free models are listed unless you switch on *Allow models that may be charged* and confirm.
6. In **Privacy**, switch on **Allow online AI**. Until then nothing is sent anywhere, even with keys saved.

## 3. How answers are routed
| Mode | Behaviour |
|---|---|
| **On this phone** | Qwen2.5 on the phone, offline, no key needed. |
| **Online** | exactly **one** target (AI providers → *Online mode uses exactly one*): a provider, or shared access. Errors are shown and nothing switches silently. |
| **Auto** | ready providers in **your order** (default Gemini → Groq → OpenRouter), then shared access if signed in, then this phone's AI if *Fall back to this phone's AI* is on. It moves on only **before an answer starts**; one answer never mixes two models. |

- **Labels:** every answer is labelled with who wrote it (`☁ Groq · llama-3.3-70b-versatile`, `📱 On this phone · Qwen2.5 1.5B Instruct`). The label is saved in history.
- **Disclosure:** the line above the message box always says where the next question will go, and what is sent.

## 4. Managing keys
- **Replace key:** validates the new key, then replaces the old one.
- **Remove:** asks for confirmation, then deletes the key from this phone.
- **Test & refresh models:** re-checks the key and updates the model list and usage.
- **Reset all provider settings on this phone:** removes every key and setting.
- **You're asked again only when** you remove a key, the provider rejects it (401: shown as *Last error*, and the provider becomes *needs attention*), you reset providers, or the app is installed on a new phone.

## 5. Usage information
| Provider | Shown |
|---|---|
| OpenRouter | free-model requests used / limit / left today, credits used (from `GET /api/v1/key`) |
| Groq | requests left today and tokens left this minute (from `x-ratelimit-*` headers of your last request) |
| Gemini | **"Usage information unavailable"**. Google offers no usage API; see AI Studio → Usage. |

Numbers are never estimated or invented.

## 6. A second phone
Keys are **per device**. They're never synced, backed up, exported or sent to the Abhify server. On a new phone, after you create a local profile or sign in, Abhify opens **AI providers** once and asks you to add keys, or to use **shared access** if your administrator enabled it. You can skip; the phone's own AI works regardless.

## 7. Shared access (administrator)
On the Abhify server (`cloud/`), set in the environment (see `.env.example`):
`ABHIFY_CLOUD_SHARED_ENABLED=true` plus `ABHIFY_CLOUD_SHARED_{GEMINI,GROQ,OPENROUTER}_KEY`, the models, `ABHIFY_CLOUD_SHARED_ORDER` and `ABHIFY_CLOUD_SHARED_DAILY_LIMIT`.
- Signed-in users see *Shared access* under AI providers with each provider's name, model, today's use and the limit. **They never see the keys.**
- The server refuses to start if a configured shared model may be charged, unless `ABHIFY_CLOUD_SHARED_ALLOW_CHARGED=true`.
- Turn it off with `ABHIFY_CLOUD_SHARED_ENABLED=false`.
- **Status:** the server is not deployed yet, so shared access is *pending deployment*.

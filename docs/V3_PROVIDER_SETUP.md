# Abhify.AI 3.0: online providers

Everything in [RC4_PROVIDER_SETUP.md](RC4_PROVIDER_SETUP.md) still applies:
- your own Gemini, Groq or OpenRouter key works with **no Abhify account or server**;
- keys stay in the Android Keystore;
- only free models are used by default;
- the selected provider is the only one used in Online mode.

## New in 3.0: answer length limit (per provider)

| Provider | Default | Request field | Why |
|---|---|---|---|
| Google Gemini | 8192 tokens | `max_tokens` | Gemini 3.x Flash thinks before answering; the thinking counts toward the limit. |
| Groq | 4096 tokens | `max_completion_tokens` | Groq counts this value against your per-minute token limit (about 8–12k on the free plan), so very high values can be refused. |
| OpenRouter | 4096 tokens | `max_tokens` | Free models vary. Many are reasoning models. |
| Abhify server | 4096 requested | `max_tokens` | The server caps it with `ABHIFY_CLOUD_AI_MAX_TOKENS` (default 1024). |
| On this phone (Qwen) | 1024 tokens | native | Also capped by the context window chosen in AI models (1024/2048/4096). |

RC.4 sent 768 tokens to every online provider. That was the main cause of answers stopping mid-sentence.

**Changing the limit:** AI Providers → a provider → **Answer length limit** (1k, 2k, 4k, 8k or 16k). Larger limits allow longer answers but use more of the free quota.

**When the limit is reached:**
- The provider reports `finish_reason: "length"`. The answer is kept in full, marked *Partial: it reached the answer length limit*, and shows **▶ Continue**.
- Continue asks the **same** provider and model to go on from the partial answer. It never switches provider. Each answer can be continued at most 3 times.
- If a reasoning model uses the whole limit before writing anything, you see: "*Provider* used the whole answer length limit before writing anything (the model's hidden reasoning). Raise the limit in AI Providers or choose another model."

## Opt-in real-provider test

Use your own keys, set in environment variables only and never in files:

```
set ABHIFY_REAL_PROVIDERS=1
set ABHIFY_GROQ_KEY=...   (and/or ABHIFY_GEMINI_KEY, ABHIFY_OPENROUTER_KEY)
gradlew testDebugUnitTest --tests ai.abhify.android.RealProviderTest
```

For each provider, the test:
- validates the key;
- asks one short question;
- asks for a ~700-word answer and checks that it arrives complete (`finish=stop`) or is reported as `length`, never silently short.

It prints only provider, model, sizes and finish reason, and checks that no error message contains the key. Expect about 3 requests per provider, using the free quota.

# RC.4: online providers (your own free keys)

You do **not** need an Abhify account or the Abhify server to use your own Gemini, Groq or OpenRouter key. The app calls the provider directly from the phone. Your key stays on the phone and is never sent to the Abhify server.

## Setup

1. Open **Menu → Privacy** and turn on **Allow online AI**. It is off by default.
2. Open **Menu → AI providers**, pick a provider, paste your key and tap **Validate & save**.
   - Validating only lists the provider's models, so it costs nothing.
   - The app then picks a free model from the list your key offers.
   - The first key you save is selected automatically.
3. To use a different provider, tap **Use for Online** on its card. The ✓ badge shows the selected one, and the choice is remembered after restart.
4. In the chat header, choose **Online**, or **Auto**.

## Where to get free keys

| Provider | Key page | What makes it free | Models RC.4 prefers |
|---|---|---|---|
| Google Gemini | aistudio.google.com/apikey | Free tier, but only if the Google Cloud project has **no billing linked**. If billing is linked, usage is charged. No API can show which applies. | gemini-3.8-flash, 3.5-flash-lite, 3.6-flash, 3.1-flash-lite |
| Groq | console.groq.com/keys | Free while your account is on the Free plan | llama-3.3-70b-versatile *if your key lists it*, otherwise openai/gpt-oss-120b, gpt-oss-20b, llama-3.1-8b-instant |
| OpenRouter | openrouter.ai/keys | `:free` models and the `openrouter/free` router (zero price) | openrouter/free |

Models that may be charged are blocked unless you turn on **Allow charged models** for that provider and confirm. RC.4 never sends a fallback list of models, and never retries endlessly.

## How the modes route a question

- **Online:** only the selected provider is used. If it isn't ready, you get its reason and an **Open AI Providers** button. The app never switches to another provider or to the server without telling you.
- **Auto:**
  1. Your first ready provider is tried.
  2. Other providers are tried only if **Try other providers in Auto** is on. It is off by default.
  3. The phone's model is used only if **Fall back to this phone's AI** is on.
  4. A switch happens only before the first word of an answer, and a notice in the chat says so.
- **On this phone:** the question never leaves the phone.

Every answer is labelled with what wrote it, for example ☁ Groq · openai/gpt-oss-120b or 📱 On this phone · Qwen2.5 1.5B.

## Usage shown in the app

- **OpenRouter:** daily requests used and the limit, read from `/api/v1/key`.
- **Groq:** requests and tokens left, read from the rate-limit headers.
- **Gemini:** not available, so the app shows none rather than inventing figures.

## Opt-in real-provider test (developers)

Set the keys in the environment only. Never put them in a file in the repository.

```
set ABHIFY_REAL_PROVIDERS=1
set ABHIFY_GROQ_KEY=...    (and/or ABHIFY_GEMINI_KEY, ABHIFY_OPENROUTER_KEY)
gradlew testDebugUnitTest --tests ai.abhify.android.RealProviderTest
```

The test prints only the provider, the model, the result and the answer length. It also checks that error messages never contain the key.

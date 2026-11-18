# RC.4 troubleshooting

Start here: **AI Providers → Copy diagnostics (no keys)**. It lists the mode, the selected provider and each provider's status and last error, without any key text.

| Message | Meaning | Fix |
|---|---|---|
| No online provider configured. Open AI Providers to add or select a provider. | Online or Auto mode, but no provider is selected. | Open AI Providers. Save a key, or tap **Use for Online**. |
| *Provider* isn't ready: no API key saved / turned off / the model … may be charged | The selected provider can't be used yet. | Save the key, switch the provider on, or choose a free model. |
| *Provider* rejected the API key. Check the key in AI Providers. | The provider returned 401, or 400 "API key" for Gemini. | Paste the key again. Check that it wasn't revoked. |
| The selected *Provider* model is unavailable… | The provider returned 404, or "model not found". | **Test & refresh**, then choose a model from the list. |
| *Provider* rate limit reached (try again in Ns)… | The provider returned 429: the free quota was used up. | Wait, or select another provider you have set up. |
| *Provider* refused access… | The provider returned 403: the key is restricted or blocked. | Check the key's restrictions in the provider's console. |
| *Provider* is unavailable right now (HTTP 5xx) | The provider has an outage. | Retry later. |
| *Provider* connection failed: no internet or the name couldn't be resolved | DNS failed, or the phone is offline. | Check Wi-Fi or mobile data, and Private DNS. |
| *Provider* connection timed out | The network is slow or blocked. | Retry, or try another network. |
| Secure connection to *Provider* failed | TLS failed: a wrong clock, or a captive portal. | Fix the date and time. Sign in to the Wi-Fi portal. |
| The connection to *Provider* was interrupted during the answer. | The stream dropped part-way through. | The partial answer is kept. Tap **↻ Retry**. |
| *Provider* returned an empty answer… | The provider sent no text. | Retry, or choose another model. |
| You're offline. *Provider* needs internet… | Online mode with no connection. | Switch to **On this phone**. |
| Abhify sign-in expired (your own providers still work) | Only the Abhify account and sync are affected. | Sign in again if you use sync. Personal keys keep working. |

**Install fails with "signatures do not match":**

- Do **not** uninstall. Uninstalling deletes chats and the model.
- RC.4 kits are signed with the RC.3 debug key. Re-download the kit, then check `SHA256SUMS.txt`.

**Dark text on a dark screen:** RC.4 fixes the cause. The app set no default text colour, so text fell back to black. Report any screen where it still happens, with a screenshot.

# Code-signing provider: decision note

Date: 2026-10-19. Status: **for your decision**. Nothing was purchased, configured or signed, and no account was created.

The pipeline is ready (`CODE_SIGNING.md` → *Readiness status*). This note is only about **who issues the certificate**.

Sources are listed at the end. Prices and eligibility change: re-check the provider page on the day you buy. Where a fact came only from a third-party site, it is marked *(third-party, unverified)*.

## 1. The question that decides almost everything

**Where are you, and as what?** Microsoft's official quickstart (updated 2026-10-08) says:

> "Public Trust certificates are available to organizations in the United States, Canada, the European Union, the United Kingdom, Australia, New Zealand, Japan, South Korea, Singapore, Switzerland, Norway, and Israel. **Individual developers must be located in the United States or Canada.**"

- The development PC's clock is set to UTC+05:30, which suggests India. That's an assumption only; please confirm your country and whether you sign as an individual or a registered business.
- **If you are an individual in India,** Azure Artifact Signing (Microsoft's cheapest and best-integrated option) is **not available to you.** Microsoft's own guide then points to traditional OV/IV certificates.

The second question: **will Abhify.AI be open source** (public repository, OSI license, no proprietary parts)? The repo currently has **no LICENSE file** and isn't public. Two of the cheapest options require a public open-source project.

## 2. Options

| | **Azure Artifact Signing** (formerly Trusted Signing) | **SignPath Foundation** (OSS) | **Certum Open Source Code Signing** | **SSL.com IV/OV + eSigner** (traditional CA, cloud key) |
|---|---|---|---|---|
| Who can get it | Organizations in the US, CA, EU, UK, AU, NZ, JP, KR, SG, CH, NO, IL; **individuals: US/CA only** | Open-source projects that meet its conditions; approval is discretionary | Individuals with a **public** open-source project (Certum checks your relationship to it) | Individuals (IV) or organizations (OV), worldwide (IV country rules not stated on the page) |
| Cost | Microsoft guide: **~$9.99/month** (Basic: 5,000 signatures, 1 profile). The official pricing page didn't render amounts when fetched | **Free** | From **€49** (cloud, SimplySign) or €69 (with a card). The shop showed "out of stock" when checked | OV **$129/yr** (1 yr; cheaper for multi-year). The eSigner subscription is extra (~$180/yr for 240 signatures, *third-party, unverified*). A USB token instead costs **+$379** |
| Name users see | Your validated legal name / organization | **"SignPath Foundation"** is the publisher, not you | "Open Source Developer, \<your name\>" | Your name (IV) or organization (OV) |
| Identity check | Azure billing account of type Individual; government ID via AU10TIX/Verified ID; 1–20 business days | Project review, MFA for all team members, a published "Code signing policy" | ID verification + utility bill + the open-source project's website | CA validation (ID documents; business documents for OV) |
| Key protection | Microsoft-managed HSM, never exportable ✔ | Managed by SignPath ✔ | SimplySign cloud, FIPS 140-2 L3 / CC EAL4+ ✔ | eSigner cloud HSM or YubiKey ✔ (required since June 2023) |
| Works with our `sign.ps1` | ✔ `TrustedSigning` mode (dlib + metadata) | ✘ Signing runs in SignPath's own CI pipeline, so a different workflow (GitHub Actions + manual approval) | Likely `Store` mode if SimplySign Desktop exposes the certificate to signtool; **not verified** | ✔ `Store` mode via **eSigner CKA**, which loads the cert into the Windows store for signtool (official SSL.com guide). Manual signing asks for an OTP |
| Usage limits | 5,000 signatures/month (Basic) | Every release needs manual approval | 5,000 signatures/month | Depends on the eSigner tier |
| SmartScreen | Reputation builds over time; first downloads still warn (Microsoft) | Same | Same | Same (OV = EV for SmartScreen since 2024, per Microsoft) |

**Not recommended:**
- **EV certificates:** Microsoft says EV hasn't bypassed SmartScreen since 2024; paying more for that "is no longer justified".
- **Self-signed certificates:** testing only; they block installs for the public. That is how we used one, inside the Sandbox lab only.

**Also worth knowing (Microsoft):**
- Publishing an **MSIX package** through the Microsoft Store is free, and the Store re-signs it with no SmartScreen warnings.
- That would need an MSIX packaging project; today we ship an NSIS installer that runs a bundled Python runtime. It's a larger change: a possible later milestone, not a signing decision.

## 3. Recommendation

| Your situation | Recommended | Why |
|---|---|---|
| Individual in the US/Canada, or an organization in an eligible country | **Azure Artifact Signing** | Cheapest, Microsoft-managed key, already supported by `sign.ps1` (`TrustedSigning` mode) |
| **Individual in India (likely), willing to open-source Abhify.AI** | **Certum Open Source Code Signing (SimplySign cloud)** | Lowest-cost certificate in your name. *Before buying:* confirm stock, how SimplySign works with signtool, and current validity rules |
| Individual in India, **not** open-sourcing | **SSL.com IV (or OV) + eSigner CKA** | Works worldwide; cloud key; works with our `Store` mode unchanged. Higher yearly cost |
| Open-source **and** happy for "SignPath Foundation" to appear as the publisher | SignPath Foundation | Free, but approval depends on established public reputation, which a new project rarely has; and the workflow is different |

**My suggestion:** decide on open-sourcing first. It changes the cheapest viable option and also matters for the GitHub publication you planned.

Either way, signing doesn't remove SmartScreen warnings at first. Reputation builds over consecutive releases signed with the **same identity**, so pick one identity and keep it.

## 4. What I need from you
1. Your country, and individual vs organization.
2. Whether Abhify.AI will be public open source (and which license).
3. Approval to create the account and buy (I won't do either).

After that, the remaining work is configuration through environment variables (`ABHIFY_SIGN_*`), then `build.py --sign`, then the Sandbox tests with the signed build. The steps are in `CODE_SIGNING.md` and `RELEASE_PROCESS.md`.

## Sources
- Microsoft, *Quickstart: Set up Artifact Signing* (eligibility, identity validation, regions): https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart
- Microsoft, *Code signing options for Windows app developers* (cost ~$9.99/month, OV/EV, SmartScreen, Store, SignPath): https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options
- Microsoft, *Artifact Signing pricing* (official page; amounts didn't render when fetched): https://azure.microsoft.com/en-us/pricing/details/artifact-signing/
- SignPath Foundation, *Conditions for Open Source projects*: https://signpath.org/terms.html
- Certum, *Open Source Code Signing in the Cloud*: https://shop.certum.eu/open-source-code-signing-on-simplysign.html
- Certum, *Code signing required documents*: https://support.certum.eu/en/code-signing-required-documents/
- SSL.com, *OV Code Signing Certificates*: https://www.ssl.com/products/software-integrity/code-signing/ov/
- SSL.com, *eSigner pricing for code signing*: https://www.ssl.com/guide/esigner-pricing-for-code-signing/
- SSL.com, *Automate code signing with SignTool using eSigner CKA*: https://ssl.com/how-to/automate-ev-code-signing-with-signtool-or-certutil-esigner/

# Code signing for Abhify.AI (design, not yet implemented)

**Status (2026-10-14):** the Windows installer `Abhify.AI-Setup-2.0.0-dev.2.exe` is **unsigned** (`Get-AuthenticodeSignature` → `NotSigned`). No certificate has been bought, no key exists, and nothing has been signed.

This document is a proposal. It needs your approval before any purchase, key creation or release change.

## Readiness status (2026-10-19, 2.0.0-dev.5)

| Step (§8) | Status |
|---|---|
| 1. Choose the identity type and provider | ⏳ **Your decision.** Nothing bought, no account created |
| 2. Identity validation | ⏳ Your decision (you share identity documents with the provider) |
| 3. Signing in the build | ✅ **Implemented, disabled by default** (`931969d` + the `/pa` fix below) |
| 4. Pipeline lab test with a throw-away certificate | ✅ **PASS 10/10** in Windows Sandbox (details below) |
| 5. CI release workflow (OIDC) | ⏳ Needs the GitHub repository; not started |
| 6. First signed prerelease | ⏳ Needs a certificate and your approval |

**What exists now:**
- `packaging\windows\sign.ps1`: signs one file, then verifies it.
  - Modes: `Store` (certificate in the Windows store, for example a hardware token or cloud HSM, by thumbprint) or `TrustedSigning` (Azure Trusted Signing `dlib` + `metadata.json`).
  - It always timestamps (RFC 3161), then checks: `signtool verify /pa` (exit 0), `Get-AuthenticodeSignature` = Valid, the signer contains the expected publisher (`ABHIFY_SIGN_SUBJECT`), and a timestamp is present. Any failure gives exit code 1.
  - All settings come from `ABHIFY_SIGN_*` environment variables. **No key, certificate, password or account ID is in the repo.**
- `py -3.11 packaging\windows\build.py --sign`:
  - NSIS calls `sign.ps1` for `Uninstall.exe` (`!uninstfinalize`) and then for the installer (`!finalize`); `= 0` stops the build if signing fails.
  - `build.py` then checks `Valid` and **only then** writes `SHA256SUMS.txt` / `latest.json.template`, computed on the **signed** file. If the check fails, it deletes the output.
  - The hook wiring was confirmed with a dummy command: NSIS called it for the temporary `Uninstall.exe` and then for the installer.

**Lab test** (`packaging\windows\sandbox\run-sandbox-signlab.ps1`; throw-away self-signed certificate created **inside** the Sandbox, private key non-exportable, valid 1 day, trusted only inside the VM):

| Check | Result |
|---|---|
| Input is an exact copy of the dev.4 installer, unsigned | PASS |
| Misconfiguration (`ABHIFY_SIGN_MODE=Nonsense`) refused before signing | PASS |
| Sign + RFC 3161 timestamp (DigiCert) + verify | PASS: Valid, timestamped |
| SHA-256 changes after signing; the checksum file matches the signed file | PASS |
| Wrong expected publisher refused | PASS |
| Tampering detected | PASS: untampered verify exit 0; tampered `HashMismatch`, verify exit 1 |
| Signed installer still installs and runs | PASS |
| Host untouched | PASS: 0 lab certificates in the host's stores; no signed copy left the VM; dev.4 still `NotSigned` |

The lab test found one bug in `sign.ps1`: a "lab mode" verified **without** `/pa`, so signtool applied the Windows *driver* policy, which normal programs never pass. Verification now always uses `/pa`, and there is no lab shortcut any more. Not tested in the lab: the `build.py --sign` run end to end with a real certificate (that needs a certificate on the build machine), and Azure Trusted Signing mode.

### Certificate requirements (checklist for step 1)
- [ ] Code-signing (not TLS) certificate, **SHA-256**, RSA ≥ 3072 or ECC P-256. The key is in a **FIPS 140-2 Level 2+ HSM or token**, which has been mandatory for publicly trusted code signing since June 2023. Azure Trusted Signing meets this automatically.
- [ ] Validated **publisher name**: the name Windows shows. It becomes `ABHIFY_SIGN_SUBJECT`.
- [ ] RFC 3161 timestamp URL from the provider.
- [ ] A named person who may sign (MFA on the account), plus a recovery method.
- [ ] Renewal date in the release checklist (§7).

### Release order once a certificate exists
`build.py --sign` (signs `Uninstall.exe` and the installer, verifies, then checksums) → `signtool verify /pa /v` once more by hand → Windows Sandbox clean + upgrade tests **with the signed build** → Defender scan → draft release → your approval → publish → download the published asset again and compare its SHA-256 with `SHA256SUMS.txt`.

## 1. What signing does (and doesn't)

| | What it gives you | What it doesn't |
|---|---|---|
| **Publisher identity** (Authenticode) | Windows shows a verified publisher name instead of "Unknown publisher". It proves the file hasn't changed since signing | It doesn't make SmartScreen trust the file immediately |
| **SmartScreen reputation** | Builds up over time, from downloads of files signed with the same identity | It can't be bought outright. New certificates, including EV since 2024, start with little reputation, so early downloads may still warn |
| **Timestamping** | Signatures stay valid after the certificate expires | |

A **self-signed certificate is not acceptable for public releases.** Windows doesn't trust it, and installing it would teach users to trust an unverified root. Use one only in an isolated lab VM to test the signing *pipeline*, and never ship anything signed with it.

## 2. Certificate options (check current prices with the vendors)

| Option | Identity validated | Where the key lives | Indicative cost | Notes |
|---|---|---|---|---|
| **Azure Trusted Signing** (Microsoft) | Individual or organization identity validation | Microsoft-managed HSM. You never hold the key | Low monthly fee (roughly USD 10/month tier) | **Recommended for a small project.** Works with `signtool` and GitHub Actions through Entra ID / OIDC. Check whether individual validation is offered in your region |
| OV code-signing certificate (DigiCert, Sectigo, SSL.com, GlobalSign…) | Organization (business registration) or individual | **Hardware token or cloud HSM is mandatory** (CA/B Forum rule since June 2023) | ~USD 200–500/year | SSL.com eSigner and DigiCert KeyLocker offer cloud signing; otherwise a USB token |
| EV code-signing certificate | Organization, stricter checks | Hardware token or cloud HSM | ~USD 300–700/year | Only for registered organizations. No longer gives instant SmartScreen reputation |

The **publisher name** that will appear is the validated legal name (for example the person's or company's name). Decide this before applying.

## 3. Where signing fits in the build

```
build.py ──► staging/ ──► makensis ──► Abhify.AI-Setup-X.exe (unsigned, intermediate)
                                            │
                       sign ◄───────────────┘   (Uninstall.exe is written at install time by the
                         │                       signed installer; sign it via !uninstfinalize, below)
                         ▼
              Abhify.AI-Setup-X.exe (signed) ──► verify ──► SHA-256 ──► SHA256SUMS.txt / latest.json
```

Rules:
1. **Checksums are computed on the final signed file, never on the intermediate.** `build.py` currently writes `SHA256SUMS.txt` straight after `makensis`. When signing is added, move that step after signing, and refuse to write checksums for an unsigned file in `--release` mode.
2. **Sign the uninstaller too.** NSIS 3 supports `!uninstfinalize 'sign-command "%1"'`, which signs `Uninstall.exe` before it's embedded in the installer. Then sign the installer with `!finalize`, or with a separate signing step.
3. Optionally sign `pythonw.exe` and `python.exe` from the embeddable runtime: they're already **signed by the Python Software Foundation**. Don't re-sign third-party binaries; keep their original signatures.
4. **Always timestamp** with an RFC 3161 server (`/tr <url> /td sha256`).

Example command (Azure Trusted Signing):
```powershell
signtool sign /v /fd sha256 /tr http://timestamp.acs.microsoft.com /td sha256 `
  /dlib "Azure.CodeSigning.Dlib.dll" /dmdf metadata.json `
  dist\windows\Abhify.AI-Setup-2.0.0.exe
```
Example command (hardware token or cloud HSM certificate in the Windows certificate store):
```powershell
signtool sign /fd sha256 /tr http://timestamp.digicert.com /td sha256 /sha1 <CERT_THUMBPRINT> <file>
```

## 4. Protecting the key and secrets

- **The private key never exists as a file** in Git, the project folder, build artifacts or installers. With Trusted Signing or a cloud or USB HSM it can't be exported at all.
- `.gitignore` already excludes `*.pfx`, `*.p12`, `*.key` and `*.pem`. `check_package.py` refuses to package them.
- **CI (GitHub Actions):**
  - Authenticate to Azure with **OIDC federated credentials**, with no stored secret.
  - If a secret is unavoidable, store it as an *environment* secret on a `release` environment with **required reviewers**.
  - Never `echo` secrets; GitHub masks registered secrets, but don't rely on that alone.
  - Use `set +x` in shell steps, and don't upload signing logs as artifacts.
- Sign only from **protected release tags** created by a maintainer. Pull requests from forks never get access to the signing environment.
- Signing is done from a clean build of a tagged commit. The build log records the commit hash and the SHA-256 of the unsigned file before signing.

## 5. Verification (every release)

```powershell
signtool verify /pa /v dist\windows\Abhify.AI-Setup-X.exe     # chain + timestamp
Get-AuthenticodeSignature dist\windows\Abhify.AI-Setup-X.exe   # Status must be "Valid"
Get-FileHash dist\windows\Abhify.AI-Setup-X.exe -Algorithm SHA256   # must equal SHA256SUMS.txt
```

Check that the signer subject equals the expected publisher name and that a timestamp is present.

## 6. Tests that signing must not break (release checklist additions)
- [ ] Windows Sandbox clean install, `packaging\windows\sandbox\run-sandbox-test.ps1`: all steps PASS with the signed build
- [ ] Uninstall (signed `Uninstall.exe`) and reinstall: data retention still PASS
- [ ] Upgrade from the previous signed version: pre-upgrade backup created, data preserved
- [ ] Microsoft Defender scan of the signed installer and the installed folder: no detections. If there are false positives, submit at https://www.microsoft.com/wdsi/filesubmission
- [ ] Optionally, a VirusTotal scan of the *public* release asset (it's public anyway). Never upload private builds
- [ ] SmartScreen behaviour noted in the release notes (a warning is still possible for a new identity)

## 7. Lifecycle and incident response
- **Renewal:** OV/EV certificates are valid for at most about 1 year (current CA/B rules). Renew 30 days ahead. With Trusted Signing, certificates rotate automatically. Put the expiry date in the release checklist.
- **Expiration:** files that were timestamped stay valid after the certificate expires. Unsigned-timestamp builds don't, so always timestamp.
- **Key backup:** HSM or cloud keys can't be exported, so there's nothing to back up. Keep the account recovery methods (MFA devices, recovery codes) in a password manager.
- **Revocation:** if compromise is suspected, revoke through the CA or Trusted Signing portal immediately, with the revocation date set *before* the first malicious signing. This invalidates files signed after that date.
- **Incident response:**
  1. Revoke the certificate.
  2. Remove the affected release assets.
  3. Publish an advisory with the affected hashes.
  4. Rotate CI credentials.
  5. Re-sign the known-good builds with a new identity and publish new checksums.
  6. Investigate the build provenance (commit, CI logs).

## 8. Proposed implementation plan (each step needs approval)

| Step | Work | Needs your approval for |
|---|---|---|
| 1 | Choose the identity type (individual or organization) and the provider. Recommended: **Azure Trusted Signing** | Account creation and cost |
| 2 | Complete identity validation with the provider | Sharing identity documents with the provider |
| 3 | Add an optional `--sign` stage to `build.py`: sign via env-provided configuration only, then verify (`signtool verify /pa`), then checksum the signed file. Add `!uninstfinalize` and `!finalize` to `installer.nsi`. **No secrets in the repo** | Code change |
| 4 | Lab test of the *pipeline only*, with a throwaway self-signed certificate inside Windows Sandbox. Never published | |
| 5 | GitHub Actions release workflow: tag → build → test → sign (OIDC) → verify → checksums → **draft** release | Repository settings, environment protection |
| 6 | First signed prerelease, after a Sandbox and upgrade test with the signed build | Publishing |

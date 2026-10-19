# Automatic updates (2.0.0-dev.6)

**Status:** the update manager exists and is tested with local fixtures. **No release channel exists yet** (no hosted manifest, no publisher key, nothing published). Until you configure one, Abhify.AI says "no update source configured" and never contacts anything.

## Trust model: why a signature, not just a hash
A SHA-256 only proves that a file matches *a* checksum. If an attacker can change the manifest, they can change the checksum too. So:
1. The release manifest is **signed with Ed25519** by your publisher key (`backend/ed25519_verify.py`, checked against the RFC 8032 test vectors).
2. Abhify.AI trusts only public keys listed in `settings.json` → `updates.trusted_keys`. **No key ships in the repository. The private key never goes in the repository or the app.**
3. The installer is accepted only if its **size and SHA-256 match the signed manifest**, and, when the manifest names one, if its Windows Authenticode signature is **Valid for the named publisher**.
4. Only HTTPS URLs are fetched. Update requests carry no user data, identifiers, cookies or tokens; the test checks this.

## What happens
| When | What | Automatic? |
|---|---|---|
| Startup + every 24 h while running | Check the signed manifest | Only if you set `updates.check_on_start: true` |
| Settings → System & data → **Check for updates** | Check now | You click |
| **Download** | Into `<data>\updates\staging`, resumable, disk checked, then size + SHA-256 (+ Authenticode) verified; a bad file is deleted | You click |
| **Install…** | Re-verifies the staged file, archives it in `updates\archive`, starts the installer (visible, per-user, **no administrator rights**). The installer stops Abhify.AI, backs up your data and keeps it | You confirm |
| Next start | Records whether the new version is running | Automatic |
| Model updates (`POST /updates/models/check`, `…/apply`) | Compares local and registry digests; applying keeps the old model as `<name>-abhify-backup`, pulls, validates, and **restores the backup if validation fails** | You confirm; never silent |

Never done:
- forced updates
- installs without your confirmation
- bypassing Windows security prompts
- deleting the working model before the new one passes validation
- changing your chosen model or profile

An offline installation keeps working indefinitely.

## Setting up a release channel (when you decide to publish; needs your approval)
```powershell
# 1. once: create the publisher key OUTSIDE the repository, keep it offline and backed up
py -3.11 packaging\windows\release_manifest.py keygen --out D:\Keys\abhify-release.key
#    -> prints the PUBLIC key. Put it in the app's settings.json:
#       "updates": {"manifest_url": "https://<your host>/latest.json", "trusted_keys": {"release-2026": "<public key>"}}

# 2. per release, after building dist\windows\<version>\:
py -3.11 packaging\windows\release_manifest.py sign --key D:\Keys\abhify-release.key --key-id release-2026 ^
    --version <version> --url https://<your host>/Abhify.AI-Setup-<version>.exe [--authenticode "O=<publisher>"]
#    -> writes dist\windows\<version>\latest.json (signed and self-verified). Upload installer + latest.json yourself.
```
The tool **refuses** to create or read a private key inside the repository.

Key rotation: add the new public key under a new `key_id`, sign with it, and remove the old one later. If a key is compromised, remove it from `trusted_keys` (it needs an app update, or an edit to settings.json on each PC).

## Recovery and rollback
| Situation | Recovery |
|---|---|
| A download failed or was corrupted | It's deleted automatically; press Download again (a partial file resumes) |
| The installer failed or was cancelled | The old version is still installed (the installer replaces program files only after its pre-upgrade backup); start Abhify.AI normally |
| The new version misbehaves | Run the **previous** installer from `<data>\updates\archive` (or your saved copy). Your data stays. If the new version had migrated the database, restore `data\backups\abhify-pre-schema-v*.db` or the `abhify-backup-pre-upgrade-*.zip` (Settings → System & data → Restore) |
| A model update is worse | Its predecessor is kept as `<name>-abhify-backup`: `ollama cp <name>-abhify-backup <name>` |

Limitations:
- Rollback is manual (run the previous installer).
- The archive keeps installers until you delete them.
- The real install path (downloaded installer replacing a running installation) is tested in Sandbox only for manual installs (dev.3→dev.4→dev.5). The update-manager-driven flow is **NOT TESTED** end to end (`UPDATE_RECOVERY_TEST.md` §B).

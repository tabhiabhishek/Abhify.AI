# Setup, update and recovery: manual test procedure (2.0.0-dev.6)

Automated coverage: `test_setup_manager.py`, `test_updates.py`, `test_offline.py`, `test_model_setup.py`. These use stand-ins for downloads, installers and Ollama; **the rows below need a real run.** Status of every row: **NOT TESTED** (2026-10-19). Use Windows Sandbox (`packaging\windows\sandbox\run-sandbox-modeltest.ps1` style, networking on) or a spare PC, never your real profile.

## A. Automatic first-run setup (needs a dev.6 installer build, not built yet)
| # | Step | Expected | Result |
|---|---|---|---|
| A1 | Clean machine, no Ollama: install, start, "See what will be installed" | Ollama (~1.6 GB) + Minimal/Balanced models listed with sizes and disk check; nothing downloaded yet | NOT TESTED |
| A2 | Start automatic setup | Ollama downloaded, signature verified, installed silently, started; models pulled; validation passes; ✅ Setup complete; `config\components.json` written | NOT TESTED |
| A3 | Pause during a model download, then Resume | Continues; no restart from zero | NOT TESTED |
| A4 | Disconnect the network mid-download, reconnect, Retry | Clear error, then completes | NOT TESTED |
| A5 | Close and reopen the app; open the plan again | "Everything is already installed"; nothing downloaded | NOT TESTED |
| A6 | Disable networking; restart; chat, documents, history | Everything core works (`OFFLINE_OPERATION.md`) | NOT TESTED (automated equivalent passes) |
| A7 | Start with almost no free disk | Refused before downloading, with numbers | NOT TESTED |

## B. Application update (needs two built versions and a signed manifest on a local HTTPS test host)
| # | Step | Expected | Result |
|---|---|---|---|
| B1 | Configure `manifest_url` + test `trusted_keys`; Check | "vX available (signed…)" | NOT TESTED |
| B2 | Serve a manifest signed by another key | Rejected: "signature does not match" | NOT TESTED |
| B3 | Download | Verified; staged; size and SHA-256 match | NOT TESTED |
| B4 | Replace the staged file, then Install | Refused ("changed or is missing") | NOT TESTED |
| B5 | Install (confirm) | Installer runs per-user, backs up data, app updated; next start records "installed" | NOT TESTED |
| B6 | Roll back with the archived previous installer | Old version runs; data intact (restore backup if the schema changed) | NOT TESTED |

## C. Model update
| # | Step | Expected | Result |
|---|---|---|---|
| C1 | Check model updates | Each active model: update available / up to date / unknown | NOT TESTED |
| C2 | Apply; then `ollama list` | New model plus `<name>-abhify-backup` | NOT TESTED |
| C3 | Force a validation failure (e.g. a model that can't load) | Previous version restored automatically | NOT TESTED |

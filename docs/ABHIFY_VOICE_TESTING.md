# Abhify voice testing (5.4)

## Automated (executed on the dev PC, 2026-11-18)

| Suite | Result |
|---|---|
| Android JVM unit tests | **374 passed, 0 failed, 4 skipped** (17 new in `V54Test.kt`; one older settings test updated for the renamed Voice & Conversation group) |
| Android lint | 0 errors (119 warnings) |
| Debug build (incl. native) | OK, `build/Abhify-5.4.apk` |
| Backend pytest | not re-run for 5.4 (no backend change since the 5.3 run: 403 passed, 2 skipped) |

`V54Test.kt` covers:
- **Settings:** defaults (owner's decisions), save and restore, damaged or unknown values falling back to defaults, legacy speed, rate clamp, language mapping (recognition, reply, speech), quiet hours across midnight.
- **Female voice:** taken only from the engine's own labels; automatic choice.
- **Actions:** English and Hindi navigation; read-only and parameterised actions; **negatives** (ordinary questions stay questions); sensitive requests never executed by voice; parameter validation; the matcher has a single caller (the user's words); every registered action has an executor.
- **Loop and states:** neural states tied to real activity; waveform only from the real microphone level; conversation turns spoken while typed chat is unchanged; clearing touches only messages, conversations and memories (content blanked, confirmations present); spoken notifications opt-in, generic and quiet-hours aware; no fake wake word.

## On the phone (owner, manual): not yet performed

Install `build/Abhify-5.4.apk` over 5.3, then use checklist tests 33–44 in [ABHIFY_RAG_DEVICE_VALIDATION.md](ABHIFY_RAG_DEVICE_VALIDATION.md).

Measurements to note:
- seconds from finishing speaking to the first words of the answer;
- interruption response;
- whether the phone gets warm during a 5-minute conversation.

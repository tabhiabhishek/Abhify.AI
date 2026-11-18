# Google Drive setup (Abhify.AI Android 4.2)

Google Drive is **optional**. Abhify.AI works fully without it and without an Abhify server. Drive is completely independent of any Abhify server sign-in.

## For users
**Settings → Google Drive → Connect Google Drive.** Then:
1. Choose your Google account in Android's account picker. If it isn't listed, choose **Add account**.
2. Review Google's permission screen and tap **Allow**.
3. You're back in Abhify, and the screen shows **✓ Google Drive connected** with your account.

The same step is offered (optional) in the first-run setup, and in Settings → Connection mode.

You never type your Google password into Abhify, and you never need a Google Cloud project.

### Your folder
Everything Abhify puts on Drive is in **My Drive → Abhify.AI**:

| Sub-folder | What goes there |
|---|---|
| `Backups/` | App backups, memory backups, scheduled backups |
| `Documents/` | The text of library documents you send to Drive |
| `Imports/` | Files you pick to copy to Drive |
| `Exports/` | Conversations and notes you export |

**Open Abhify.AI folder** opens it in Google Drive.

### Moving files from earlier versions (optional)
Versions 3.4–4.1 used other sub-folders. **Organize older Abhify files → Find older files** lists them:

| Earlier sub-folder | New sub-folder |
|---|---|
| Application Backups, Memory Backups | Backups |
| Learning Library | Documents |
| Archived Conversations, Exported Notes | Exports |

You tick which files to move and confirm. Moving keeps each file's id and link. Nothing is deleted, the old folders stay, and files that can't be moved are left where they are.

### Account actions
| Action | What happens |
|---|---|
| **Check connection** | Confirms the account and finds the folder. It never creates anything. |
| **Reconnect** | Asks Google again for the same account. |
| **Switch account** | Opens the account picker. If uploads are waiting, you're asked first: they stay with their original account and are sent only when you switch back, never to the new account. |
| **Disconnect** | Revokes Abhify's access at Google. Files on Drive and on the phone are not touched; folder settings are kept for reconnecting. |
| **Recreate folder** | Shown if My Drive → Abhify.AI was deleted or moved. It creates the folder again and changes nothing else. |

## Access and security
- **Only the `drive.file` scope.** Abhify sees and changes only files it created, or files you open with it. It never sees the rest of your Drive.
- **No client secret in the app.** The Android OAuth client is matched by package name and signing certificate.
- **Tokens:** kept in memory only. They are never written to disk, to backups or to logs.
- **Account identity:** comes from Drive's `about` endpoint (permission id, email and name, shown in Settings).
- **Per-account folder ids:** stored per account (`drive_folders:<permission id>`). They are never used for another account, and they are re-checked before every use (exists, not trashed, still in the right parent).
- **Uploads are bound to their account:** each queued upload records its account (DB v16).
- **No hard-coded addresses:** no Gmail address or user-specific value exists in the code; a test checks this.

## One-time developer configuration (already done for the debug build)
1. Create a Google Cloud project: **Abhify AI Android**.
2. Enable the Google Drive API.
3. OAuth consent screen: External, in Testing, with your test users added.
4. Create an **Android** OAuth client: package `ai.abhify.android`, debug SHA-1 `AF:35:DE:7A:41:DF:C1:0B:78:14:B2:29:E3:FF:4E:25:50:1B:63:51`.
   - The debug key that signs these builds was checked with apksigner: SHA-1 `af35de7a…1b6351`, which matches.
5. **No Web client is needed:** the Android Authorization API with an Android client is enough to call Drive from the phone.
6. A future **release** key needs its own Android OAuth client with that key's SHA-1.
7. **Publishing to everyone:** while the consent screen is in "Testing", only listed test users can connect. Opening Drive to everyone needs Google's OAuth verification.

## Troubleshooting
| Message | Meaning or fix |
|---|---|
| "Google sign-in was cancelled. Nothing changed" | You closed the picker or the permission screen. Tap Connect again. |
| "Access wasn't granted" | You tapped Deny. Tap Connect and choose Allow. |
| "…isn't set up for this app build (developer configuration…)" | The OAuth client doesn't match this package or key (developer error 10). This isn't fixed on the phone. |
| "Access blocked" / app not verified | The account isn't a test user of the Google project. |
| "Google Play services need attention" | Update or enable Play services. |
| "sign-in expired or was revoked" | Tap Reconnect. |
| "The Abhify.AI folder is unavailable" | Tap Recreate folder. |
| "Google Drive is limiting requests" | Wait a minute; queued uploads retry by themselves. |

## Limits (honest)
- **Background uploads:** they run with WorkManager (since 3.5), continue after the app is closed, and need a network.
- **No automatic two-way sync.** Folder contents are listed when you ask.
- **Scheduled backups:** they exist (since 3.5) but are **off** until you enable them.
- **The setup flow was not tested on a phone.** Real-account checks are listed in [V4_2_DRIVE_AND_LOCAL_MODE.md](V4_2_DRIVE_AND_LOCAL_MODE.md).

# Google Cloud setup for Data & Sync (owner, one time): 7.2.0-dev.1

This is approved setup: the existing project, **Testing** mode, only your Google account, nothing published or submitted for verification. It needs your Google login, so it can't be done for you.

## 1. Open the existing project
1. Go to Google Cloud Console and choose the project **Abhify AI Android** (the one that already has the Android OAuth client).
2. Check that **APIs & Services → Enabled APIs** lists **Google Drive API**. It already should.

## 2. Consent screen: stay in Testing
**APIs & Services → OAuth consent screen** (Google Auth Platform → Audience):
- **Publishing status:** keep it on **Testing**.
- **Test users:** only your own Google account.
- **Scopes (Data access):** only `.../auth/drive.file`. Don't add other Drive scopes.

> In Testing mode Google ends a Windows sign-in after **7 days**. Abhify then shows "sign-in expired… Connect again", and nothing is lost. Android's sign-in isn't affected (it asks Google again silently).

## 3. Create the Windows client
**APIs & Services → Credentials → Create credentials → OAuth client ID**:
1. Set **Application type** to **Desktop app**.
2. Set **Name** to `Abhify.AI Windows`.
3. Click **Create**, then **Download JSON** (`client_secret_….json`).

Keep this file private. Google documents desktop client secrets as not confidential, but don't post the file publicly. In Abhify.AI it is stored encrypted with Windows DPAPI and never included in backups.

## 4. Create the Web client (for the standalone Web app, next phase)
**Create credentials → OAuth client ID**:
1. Set **Application type** to **Web application**.
2. Set **Name** to `Abhify.AI Web`.
3. Under **Authorized JavaScript origins**, add every address you will open the Web app from. Each must be exact; https is required except for localhost. For testing, add `http://localhost:8080`.
4. Leave **Authorized redirect URIs** empty: the browser token flow doesn't use one.

Send me only the **client ID** of this Web client (`….apps.googleusercontent.com`). The Web app needs no secret.

## 5. Requirement A: check that the three clients see each other's files
Google's documentation doesn't say whether files created through one OAuth client are visible to another client in the same project under `drive.file`. Some third-party reports say they are **not**. So this is tested, not assumed, before the cross-platform parts are finished.

1. **Phone (Abhify.AI 7.1.0-dev.2, already installed):**
   - Open **More → Google Drive** and make sure it says Connected.
   - Upload any harmless file with **Upload → A file → Imports**. This makes sure My Drive/Abhify.AI exists and holds a file the phone created.
2. **Windows:** run, with the `client_secret` file from step 3:
   ```
   cd D:\Abhify.AI
   backend\.venv\Scripts\python backend\tools\drive_interop_check.py C:\path\to\client_secret_….json
   ```
   - Sign in with the same Google account in the browser that opens.
   - The tool uses a temporary folder: it doesn't touch your Abhify.AI data. It lists what it can see, writes `abhify-interop-check-windows-<time>.txt` into Abhify.AI/Imports, then revokes its access.
3. **Phone:**
   - Open **More → Google Drive → Imports** (refresh).
   - Is `abhify-interop-check-windows-….txt` listed?

## 6. What the result means

| Windows sees the phone's folder | Phone lists the Windows file | Meaning | Next step |
|---|---|---|---|
| yes | yes | `drive.file` is shared across clients in one project | Continue as designed |
| no | no | Google keeps each client's files separate | Stop. I'll report the options (below); nothing is broadened without your approval |
| mixed | mixed | Unexpected | Send me the tool's output |

If the clients are isolated, the options are:
- **(a)** Use **one** OAuth client ID on all three platforms (a Web-type client for Windows and Web, and the same client's server auth code on Android). Scope stays `drive.file`, but this changes the approved client setup.
- **(b)** A broader Drive scope. This is a **restricted** scope that would need Google verification, so it's not recommended.
- **(c)** Have the user pick the sync folder once per device with Google's file picker. This needs the Picker API and some UI work.

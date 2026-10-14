; =====================================================================
; Abhify.AI Windows installer (NSIS 3, Unicode)
;
; Built by packaging/windows/build.py, which passes:
;   /DVERSION=<app version>  /DSTAGING=<staged files dir>  /DOUTFILE=<installer path>
;
; Design rules:
;   * per-user install, no administrator rights:  %LOCALAPPDATA%\Programs\Abhify.AI
;   * program files only: user data lives in %LOCALAPPDATA%\AbhifyAI and is
;     never touched by install / upgrade; uninstall asks (default: keep data)
;   * upgrade = stop running app -> back up user data -> replace program files
;   * no AI models are downloaded by the installer
; =====================================================================
Unicode true
ManifestDPIAware true
SetCompressor /SOLID lzma
RequestExecutionLevel user

!define APPNAME "Abhify.AI"
!define PUBLISHER "Abhify.AI"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APPNAME}"
!define APP_KEY "Software\${APPNAME}"

!ifndef VERSION
  !error "VERSION not defined (run build.py)"
!endif

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\${APPNAME}"
InstallDirRegKey HKCU "${APP_KEY}" "InstallDir"
BrandingText "${APPNAME} ${VERSION}"
VIProductVersion "${VIVERSION}"
VIAddVersionKey "ProductName" "${APPNAME}"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "${APPNAME} installer"
VIAddVersionKey "CompanyName" "${PUBLISHER}"
VIAddVersionKey "LegalCopyright" "${PUBLISHER}"

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "WinVer.nsh"
!include "x64.nsh"
!include "FileFunc.nsh"

!define MUI_ICON "${STAGING}\Abhify.AI.ico"
!define MUI_UNICON "${STAGING}\Abhify.AI.ico"
!define MUI_ABORTWARNING
!define MUI_WELCOMEPAGE_TITLE "Welcome to ${APPNAME} ${VERSION}"
!define MUI_WELCOMEPAGE_TEXT "Abhify.AI is a local-first AI/ML learning companion. Your chats, memories and documents stay on this computer.$\r$\n$\r$\n\
What you need:$\r$\n\
  - Windows 10 or 11 (64-bit), about 1.5 GB free for the app$\r$\n\
  - Ollama (free) for the AI models: the app helps you install it$\r$\n$\r$\n\
GPU or CPU: an NVIDIA GPU makes answers faster but is NOT required. On first start, Abhify.AI checks this computer and recommends models that fit (about 1.3 to 9 GB). Nothing is downloaded without your permission.$\r$\n$\r$\n\
No administrator rights are needed."
!define MUI_FINISHPAGE_RUN "$INSTDIR\runtime\pythonw.exe"
!define MUI_FINISHPAGE_RUN_PARAMETERS '"$INSTDIR\app\desktop\launcher.pyw"'
!define MUI_FINISHPAGE_RUN_TEXT "Start ${APPNAME} now"
!define MUI_FINISHPAGE_SHOWREADME "$INSTDIR\INSTALL.md"
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Open the installation guide"
!define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

; ---------------------------------------------------------------------- checks
Function .onInit
  ${IfNot} ${AtLeastWin10}
    MessageBox MB_OK|MB_ICONSTOP "${APPNAME} needs Windows 10 or Windows 11." /SD IDOK
    Abort
  ${EndIf}
  ${IfNot} ${RunningX64}
    MessageBox MB_OK|MB_ICONSTOP "${APPNAME} needs a 64-bit version of Windows." /SD IDOK
    Abort
  ${EndIf}
FunctionEnd

; ---------------------------------------------------------------------- install
Section "${APPNAME} (required)" SecMain
  SectionIn RO
  SetOutPath "$INSTDIR"

  ; Upgrade: stop the running app and back up user data with the OLD runtime first.
  ${If} ${FileExists} "$INSTDIR\runtime\python.exe"
    DetailPrint "Existing installation found: stopping ${APPNAME}..."
    nsExec::ExecToLog '"$INSTDIR\runtime\python.exe" "$INSTDIR\app\desktop\launcher.pyw" --stop'
    Pop $0
    DetailPrint "Backing up your data before upgrading..."
    nsExec::ExecToLog '"$INSTDIR\runtime\python.exe" "$INSTDIR\app\desktop\launcher.pyw" --backup pre-upgrade'
    Pop $0
    ; replace program files only (user data is elsewhere)
    RMDir /r "$INSTDIR\runtime"
    RMDir /r "$INSTDIR\app"
  ${EndIf}

  File /r "${STAGING}\*.*"

  ; Start Menu
  CreateDirectory "$SMPROGRAMS\${APPNAME}"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\app\desktop\launcher.pyw"' "$INSTDIR\Abhify.AI.ico"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Stop ${APPNAME}.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\app\desktop\launcher.pyw" --stop' "$INSTDIR\Abhify.AI.ico"
  CreateShortcut "$SMPROGRAMS\${APPNAME}\Uninstall ${APPNAME}.lnk" "$INSTDIR\Uninstall.exe"

  ; Uninstall entry (per user: Settings > Apps)
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "${APP_KEY}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${APP_KEY}" "Version" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${PUBLISHER}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\Abhify.AI.ico"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"
SectionEnd

Section "Desktop shortcut" SecDesktop
  CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\runtime\pythonw.exe" '"$INSTDIR\app\desktop\launcher.pyw"' "$INSTDIR\Abhify.AI.ico"
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SecMain} "The ${APPNAME} app and its private Python runtime (no AI models; those are chosen in the app)."
  !insertmacro MUI_DESCRIPTION_TEXT ${SecDesktop} "Put a ${APPNAME} shortcut on the desktop."
!insertmacro MUI_FUNCTION_DESCRIPTION_END

; ---------------------------------------------------------------------- uninstall
Section "Uninstall"
  nsExec::ExecToLog '"$INSTDIR\runtime\python.exe" "$INSTDIR\app\desktop\launcher.pyw" --stop'
  Pop $0

  Delete "$DESKTOP\${APPNAME}.lnk"
  RMDir /r "$SMPROGRAMS\${APPNAME}"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  Delete "$INSTDIR\Abhify.AI.ico"
  Delete "$INSTDIR\INSTALL.md"
  Delete "$INSTDIR\RELEASE_NOTES.md"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "${APP_KEY}"

  ; User data is kept unless the user explicitly says yes (silent uninstall keeps it).
  MessageBox MB_YESNO|MB_ICONQUESTION|MB_DEFBUTTON2 \
    "Also delete your personal Abhify.AI data?$\r$\n$\r$\nThis removes chats, memories, quiz progress, settings, backups and the document index in:$\r$\n$LOCALAPPDATA\AbhifyAI$\r$\n$\r$\nYour knowledge folder files and Ollama models are NOT deleted.$\r$\n$\r$\nChoose No to keep your data for a later reinstall." \
    /SD IDNO IDNO keep_data
    RMDir /r "$LOCALAPPDATA\AbhifyAI"
  keep_data:
SectionEnd

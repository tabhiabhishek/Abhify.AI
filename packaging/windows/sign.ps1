# =====================================================================
# sign.ps1: Authenticode-sign ONE file, then verify it. Used by build.py --sign (NSIS calls it
# for Uninstall.exe and the installer) and usable by hand. See docs/CODE_SIGNING.md.
#
# Nothing secret lives in this file or the repo. Configuration comes from parameters or env vars:
#   ABHIFY_SIGN_MODE        Store | TrustedSigning
#   ABHIFY_SIGN_THUMBPRINT  (Store) SHA-1 thumbprint of the code-signing cert in the Windows cert
#                           store (hardware token / cloud HSM: the private key is not exportable)
#   ABHIFY_SIGN_DLIB        (TrustedSigning) path to Azure.CodeSigning.Dlib.dll
#   ABHIFY_SIGN_METADATA    (TrustedSigning) path to metadata.json (endpoint, account, profile)
#   ABHIFY_SIGN_TIMESTAMP   RFC 3161 timestamp URL (default: DigiCert; Trusted Signing: Microsoft)
#   ABHIFY_SIGN_SUBJECT     expected publisher text in the signer subject (verification fails otherwise)
#   ABHIFY_SIGNTOOL         path to signtool.exe (default: newest Windows SDK x64)
# There is no "lab" shortcut: the lab test (sandbox-signlab.ps1) trusts its throw-away root
# inside the VM only, so the same /pa verification runs as for a real release.
# Exit code 0 only if the file is signed, timestamped and verifies.
# =====================================================================
param(
    [Parameter(Mandatory)][string]$File,
    [string]$Mode = $env:ABHIFY_SIGN_MODE,
    [string]$Thumbprint = $env:ABHIFY_SIGN_THUMBPRINT,
    [string]$Dlib = $env:ABHIFY_SIGN_DLIB,
    [string]$Metadata = $env:ABHIFY_SIGN_METADATA,
    [string]$TimestampUrl = $env:ABHIFY_SIGN_TIMESTAMP,
    [string]$ExpectedSubject = $env:ABHIFY_SIGN_SUBJECT,
    [string]$Signtool = $env:ABHIFY_SIGNTOOL
)
$ErrorActionPreference = "Stop"
function Fail($msg) { Write-Host "SIGN FAILED: $msg"; exit 1 }

if (-not (Test-Path -LiteralPath $File)) { Fail "file not found: $File" }
if (-not $Signtool) {
    $Signtool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin\*\x64\signtool.exe" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Signtool -or -not (Test-Path $Signtool)) { Fail "signtool.exe not found (install the Windows SDK or set ABHIFY_SIGNTOOL)" }
if (-not $ExpectedSubject) { Fail "set ABHIFY_SIGN_SUBJECT to the expected publisher name (verification needs it)" }

switch ($Mode) {
    "Store" {
        if ($Thumbprint -notmatch '^[0-9A-Fa-f]{40}$') { Fail "ABHIFY_SIGN_THUMBPRINT must be a 40-hex SHA-1 thumbprint" }
        if (-not $TimestampUrl) { $TimestampUrl = "http://timestamp.digicert.com" }
        $signArgs = @("sign", "/fd", "sha256", "/tr", $TimestampUrl, "/td", "sha256", "/sha1", $Thumbprint)
    }
    "TrustedSigning" {
        if (-not (Test-Path "$Dlib") -or -not (Test-Path "$Metadata")) { Fail "set ABHIFY_SIGN_DLIB and ABHIFY_SIGN_METADATA" }
        if (-not $TimestampUrl) { $TimestampUrl = "http://timestamp.acs.microsoft.com" }
        $signArgs = @("sign", "/fd", "sha256", "/tr", $TimestampUrl, "/td", "sha256", "/dlib", $Dlib, "/dmdf", $Metadata)
    }
    default { Fail "ABHIFY_SIGN_MODE must be Store or TrustedSigning (got '$Mode')" }
}

$before = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLower()
Write-Host "signing $File (unsigned sha256 $before)"
& $Signtool @signArgs $File
if ($LASTEXITCODE -ne 0) { Fail "signtool sign exited $LASTEXITCODE" }

# ---- verify: chain policy, timestamp, publisher. Always /pa (the Authenticode policy): without it
# signtool applies the Windows *driver* policy, which an ordinary program never satisfies.
& $Signtool verify /pa /v $File | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "signtool verify exited $LASTEXITCODE" }
$sig = Get-AuthenticodeSignature -LiteralPath $File
if ($sig.Status -ne "Valid") { Fail "Get-AuthenticodeSignature: $($sig.Status) ($($sig.StatusMessage))" }
if ($sig.SignerCertificate.Subject -notlike "*$ExpectedSubject*") { Fail "signer '$($sig.SignerCertificate.Subject)' does not contain '$ExpectedSubject'" }
if (-not $sig.TimeStamperCertificate) { Fail "no timestamp: the signature would stop being valid when the certificate expires" }
$after = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLower()
Write-Host "signed OK: $($sig.SignerCertificate.Subject) | timestamp $($sig.TimeStamperCertificate.Subject) | signed sha256 $after"
exit 0

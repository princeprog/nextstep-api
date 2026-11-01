<#
.SYNOPSIS
  Saves the Gmail address and app password that .\scripts\run-api.ps1 -Gmail sends email through (local testing only).

.DESCRIPTION
  Gmail accepts SMTP sign-ins only with an app password, which needs 2-Step Verification on the Google account. Create
  one at https://myaccount.google.com/apppasswords, then run this script and enter the address and the app password.

  Both are saved to %LOCALAPPDATA%\NextStep\gmail-smtp.json, outside the repository. The app password is encrypted with
  Windows DPAPI for your Windows account, so only you, on this computer, can decrypt it; it is never shown, logged, or
  written in plain text. Run the script again to replace the saved values. To stop using them, delete the file and
  revoke the app password at https://myaccount.google.com/apppasswords.

.EXAMPLE
  .\scripts\set-gmail-app-password.ps1
  .\scripts\run-api.ps1 -Gmail
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')

$address = (Read-Host 'Gmail address').Trim()
# The address also becomes the sender ("NextStep <address>"), so only a bare address is accepted.
if ($address -notmatch '^[^\s@<>"]+@[^\s@<>"]+\.[^\s@<>"]+$') {
    throw "That isn't an email address. Run the script again and enter the Gmail address, for example you@gmail.com."
}
$typed = Read-Host 'App password (16 letters, masked)' -AsSecureString

# Google shows app passwords in groups of four letters. The spaces aren't part of the password, so they are dropped
# character by character, without turning the password into a plain string.
$appPassword = New-Object System.Security.SecureString
$buffer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($typed)
try {
    for ($i = 0; $i -lt $typed.Length; $i++) {
        $character = [char]([Runtime.InteropServices.Marshal]::ReadInt16($buffer, $i * 2) -band 0xFFFF)
        if (-not [char]::IsWhiteSpace($character)) { $appPassword.AppendChar($character) }
    }
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($buffer)
}
$appPassword.MakeReadOnly()
if ($appPassword.Length -eq 0) {
    throw 'No app password was entered, so nothing was saved.'
}

Save-NextStepGmailSmtp -Address $address -AppPassword $appPassword
Write-Output "Saved to $NextStepGmailFile, with the app password encrypted for your Windows account."
Write-Output 'Start the API with .\scripts\run-api.ps1 -Gmail to send email through this account.'

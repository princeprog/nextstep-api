<#
.SYNOPSIS
  Runs the API locally against the development database (http://127.0.0.1:8080).
  Loads the generated database password from %LOCALAPPDATA%\NextStep\dev-db.json into this process only.

.PARAMETER RelaxedLimits
  Raises the demo, registration, sign-in, email-request, link, and hourly email limits for local end-to-end test runs,
  which create many accounts and demos from the same address. Never use this for a shared or public deployment.

.PARAMETER Mailpit
  Sends email over SMTP to a local Mailpit (127.0.0.1:1025, inbox at http://127.0.0.1:8025) instead of writing it to
  the log. The end-to-end tests read confirmation and reset links from Mailpit.

.PARAMETER Gmail
  Sends email to real inboxes through your own Gmail account (smtp.gmail.com, port 587, STARTTLS), for local testing
  only. Needs Google 2-Step Verification and an app password, saved once with .\scripts\set-gmail-app-password.ps1.
  Messages come from that account, and their links point at the app on this computer, so open them here.
  Can't be combined with -Mailpit.
#>
param([switch]$RelaxedLimits, [switch]$Mailpit, [switch]$Gmail)

$ErrorActionPreference = 'Stop'
if ($Mailpit -and $Gmail) {
    throw 'Use -Mailpit or -Gmail, not both: -Mailpit keeps email in the local Mailpit inbox, and -Gmail sends it to real inboxes through your Gmail account.'
}
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')
# Read before anything else, so a missing app password stops the script before the API starts.
$gmailSmtp = if ($Gmail) { Get-NextStepGmailSmtp } else { $null }
$secrets = Get-NextStepSecrets
$env:JAVA_HOME = Get-NextStepJavaHome
$env:DATABASE_URL = "jdbc:postgresql://127.0.0.1:$($secrets.port)/$($secrets.database)"
$env:DATABASE_USER = $secrets.appUser
$env:DATABASE_PASSWORD = $secrets.appPassword
if ($RelaxedLimits) {
    $env:DEMO_RATE_LIMIT_PER_HOUR = '1000'
    $env:DEMO_GLOBAL_RATE_LIMIT_PER_HOUR = '1000'
    $env:REGISTER_RATE_LIMIT_PER_HOUR = '1000'
    $env:LOGIN_RATE_LIMIT_PER_WINDOW = '1000'
    $env:EMAIL_REQUEST_RATE_LIMIT_PER_HOUR = '1000'
    $env:LINK_RATE_LIMIT_PER_WINDOW = '1000'
    $env:EMAIL_GLOBAL_LIMIT_PER_HOUR = '100000'
}
# The email settings last only for this run, so a later run in this window neither sends real email by accident nor
# keeps the app password in its environment.
$mailSettings = @('MAIL_MODE', 'MAIL_HOST', 'MAIL_PORT', 'MAIL_SMTP_AUTH', 'MAIL_STARTTLS', 'MAIL_USERNAME', 'MAIL_PASSWORD',
    'MAIL_FROM')
$previousMailSettings = @{}
foreach ($name in $mailSettings) { $previousMailSettings[$name] = [Environment]::GetEnvironmentVariable($name) }
if ($Mailpit) {
    $env:MAIL_MODE = 'smtp'
    $env:MAIL_HOST = '127.0.0.1'
    $env:MAIL_PORT = '1025'
    $env:MAIL_SMTP_AUTH = 'false'
    $env:MAIL_STARTTLS = 'false'
}
if ($Gmail) {
    $env:MAIL_MODE = 'smtp'
    $env:MAIL_HOST = 'smtp.gmail.com'
    $env:MAIL_PORT = '587'
    $env:MAIL_SMTP_AUTH = 'true'
    $env:MAIL_STARTTLS = 'true'
    $env:MAIL_USERNAME = $gmailSmtp.Address
    $env:MAIL_PASSWORD = $gmailSmtp.Password
    $env:MAIL_FROM = "NextStep <$($gmailSmtp.Address)>"
    Write-Output "Email goes to real inboxes through Gmail, from $($gmailSmtp.Address). Open the links in it on this computer."
}
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd -q spring-boot:run
} finally {
    Pop-Location
    foreach ($name in $mailSettings) { [Environment]::SetEnvironmentVariable($name, $previousMailSettings[$name]) }
}


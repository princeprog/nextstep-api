<#
.SYNOPSIS
  Runs the API locally against the development database (http://127.0.0.1:8080).
  Loads the generated database password from %LOCALAPPDATA%\NextStep\dev-db.json into this process only.

.PARAMETER RelaxedLimits
  Raises the demo, registration, and login rate limits for local end-to-end test runs, which create many
  accounts and demos from the same address. Never use this for a shared or public deployment.

.PARAMETER Mailpit
  Sends email over SMTP to a local Mailpit (127.0.0.1:1025, inbox at http://127.0.0.1:8025) instead of writing it to
  the log. The end-to-end tests read confirmation and reset links from Mailpit.
#>
param([switch]$RelaxedLimits, [switch]$Mailpit)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')
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
}
if ($Mailpit) {
    $env:MAIL_MODE = 'smtp'
    $env:MAIL_HOST = '127.0.0.1'
    $env:MAIL_PORT = '1025'
    $env:MAIL_SMTP_AUTH = 'false'
    $env:MAIL_STARTTLS = 'false'
}
Push-Location (Join-Path $PSScriptRoot '..')
try {
    & .\mvnw.cmd -q spring-boot:run
} finally {
    Pop-Location
}


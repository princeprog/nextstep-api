# Shared helpers for the NextStep local scripts. Dot-source this file; it does not change anything by itself.

$script:NextStepRoot = Join-Path $env:LOCALAPPDATA 'NextStep'
$script:NextStepDataDir = Join-Path $script:NextStepRoot 'pgdata-dev'
$script:NextStepLogFile = Join-Path $script:NextStepRoot 'postgres-dev.log'
$script:NextStepSecretFile = Join-Path $script:NextStepRoot 'dev-db.json'
$script:NextStepGmailFile = Join-Path $script:NextStepRoot 'gmail-smtp.json'
$script:NextStepDbPort = if ($env:NEXTSTEP_DB_PORT) { [int]$env:NEXTSTEP_DB_PORT } else { 55432 }

function Get-NextStepPgBin {
    if ($env:NEXTSTEP_PG_BIN) { return $env:NEXTSTEP_PG_BIN }
    $onPath = Get-Command initdb -ErrorAction SilentlyContinue
    if ($onPath) { return Split-Path -Parent $onPath.Source }
    $installed = Get-ChildItem -LiteralPath 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [int]($_.Name -replace '\D.*$', '0') } -Descending |
        Select-Object -First 1
    if ($installed) { return Join-Path $installed.FullName 'bin' }
    throw 'PostgreSQL binaries not found. Install PostgreSQL 17+ or set NEXTSTEP_PG_BIN to the folder containing initdb.exe.'
}

function Get-NextStepJavaHome {
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) { return $env:JAVA_HOME }
    $local = Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'Programs\Java') -Directory -Filter 'jdk-25*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($local) { return $local.FullName }
    throw 'Java 25 not found. Install a JDK 25 and set JAVA_HOME.'
}

function Get-NextStepSecrets {
    if (-not (Test-Path -LiteralPath $script:NextStepSecretFile)) {
        throw 'Development database is not initialized. Run: .\scripts\db-dev.ps1 init'
    }
    return Get-Content -LiteralPath $script:NextStepSecretFile -Raw | ConvertFrom-Json
}

# The Gmail account that run-api.ps1 -Gmail sends email through, saved by set-gmail-app-password.ps1. The app password
# is encrypted with Windows DPAPI for the current user (ConvertFrom-SecureString without a key), so only this Windows
# account on this computer can decrypt it, and it is never written in plain text.
function Save-NextStepGmailSmtp {
    param(
        [Parameter(Mandatory = $true)][string]$Address,
        [Parameter(Mandatory = $true)][System.Security.SecureString]$AppPassword,
        [string]$Path = $script:NextStepGmailFile
    )
    $folder = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $folder)) { New-Item -ItemType Directory -Force -Path $folder | Out-Null }
    $saved = [ordered]@{ address = $Address; appPassword = (ConvertFrom-SecureString -SecureString $AppPassword) }
    $saved | ConvertTo-Json | Set-Content -LiteralPath $Path -Encoding UTF8
}

# Returns the saved Gmail address and the decrypted app password. Only for handing them to the API; never print them.
function Get-NextStepGmailSmtp {
    param([string]$Path = $script:NextStepGmailFile)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw 'No Gmail app password is saved. Run .\scripts\set-gmail-app-password.ps1 first, then start the API with -Gmail again.'
    }
    try {
        $saved = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        $secure = ConvertTo-SecureString -String $saved.appPassword -ErrorAction Stop
    } catch {
        $saved = $null
    }
    if (-not $saved -or -not $saved.address) {
        throw "The saved Gmail app password in $Path can't be read. It was saved by another Windows user or on another computer, or the file is damaged. Run .\scripts\set-gmail-app-password.ps1 again."
    }
    return [pscustomobject]@{
        Address  = $saved.address
        Password = (New-Object System.Net.NetworkCredential -ArgumentList '', $secure).Password
    }
}

function New-NextStepSecret {
    $bytes = New-Object byte[] 24
    # RandomNumberGenerator.Fill exists only in PowerShell 7 (.NET Core); Create().GetBytes also works in Windows PowerShell 5.1.
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

# Runs pg_ctl with its output sent to files. PostgreSQL inherits pg_ctl's handles, so a captured pipe would keep
# the caller waiting for as long as the server runs; waiting on the pg_ctl process alone avoids that.
function Invoke-NextStepPgCtl {
    param([string]$PgCtl, [string[]]$Arguments)
    $outFile = Join-Path $script:NextStepRoot 'pg_ctl.out'
    $errFile = Join-Path $script:NextStepRoot 'pg_ctl.err'
    $quoted = $Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }
    $process = Start-Process -FilePath $PgCtl -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $errFile
    $process.WaitForExit()
    return $process.ExitCode
}


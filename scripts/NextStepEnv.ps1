# Shared helpers for the NextStep local scripts. Dot-source this file; it does not change anything by itself.

$script:NextStepRoot = Join-Path $env:LOCALAPPDATA 'NextStep'
$script:NextStepDataDir = Join-Path $script:NextStepRoot 'pgdata-dev'
$script:NextStepLogFile = Join-Path $script:NextStepRoot 'postgres-dev.log'
$script:NextStepSecretFile = Join-Path $script:NextStepRoot 'dev-db.json'
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

function New-NextStepSecret {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
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


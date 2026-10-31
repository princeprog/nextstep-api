<#
.SYNOPSIS
  Manages the dedicated NextStep development PostgreSQL cluster.

.DESCRIPTION
  Uses the locally installed PostgreSQL binaries to run a separate cluster under %LOCALAPPDATA%\NextStep\pgdata-dev,
  listening only on 127.0.0.1:55432 (override with NEXTSTEP_DB_PORT). It never touches other PostgreSQL services.
  Generated credentials are stored in %LOCALAPPDATA%\NextStep\dev-db.json, outside the repository.

.EXAMPLE
  .\scripts\db-dev.ps1 init     # create the cluster, role, and database (once)
  .\scripts\db-dev.ps1 start
  .\scripts\db-dev.ps1 status
  .\scripts\db-dev.ps1 stop
#>
param(
    [Parameter(Position = 0)]
    [ValidateSet('init', 'start', 'stop', 'status')]
    [string]$Command = 'status'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')
$pgBin = Get-NextStepPgBin
$pgCtl = Join-Path $pgBin 'pg_ctl.exe'
$psql = Join-Path $pgBin 'psql.exe'

function Start-Cluster {
    if ((Invoke-NextStepPgCtl $pgCtl @('status', '-D', $NextStepDataDir)) -eq 0) {
        Write-Output 'Development database is already running.'
        return
    }
    $code = Invoke-NextStepPgCtl $pgCtl @('start', '-D', $NextStepDataDir, '-l', $NextStepLogFile, '-w', '-t', '60')
    if ($code -ne 0) { throw "pg_ctl start failed. See $NextStepLogFile" }
    Write-Output "Development database running on 127.0.0.1:$NextStepDbPort"
}

switch ($Command) {
    'init' {
        if (Test-Path -LiteralPath $NextStepDataDir) {
            Write-Output "Already initialized at $NextStepDataDir"
            break
        }
        New-Item -ItemType Directory -Force -Path $NextStepRoot | Out-Null
        $superPassword = New-NextStepSecret
        $appPassword = New-NextStepSecret
        $pwFile = Join-Path $NextStepRoot ('init-' + [guid]::NewGuid().ToString('N') + '.tmp')
        Set-Content -LiteralPath $pwFile -Value $superPassword -NoNewline -Encoding ascii
        $initArgs = @('-D', $NextStepDataDir, '-U', 'postgres', '--auth=scram-sha-256', "--pwfile=$pwFile",
            '-E', 'UTF8', '--locale=C', '--locale-provider=builtin', '--builtin-locale=C.UTF-8')
        try {
            & (Join-Path $pgBin 'initdb.exe') @initArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'initdb failed' }
        } finally {
            Remove-Item -LiteralPath $pwFile -Force
        }
        Add-Content -LiteralPath (Join-Path $NextStepDataDir 'postgresql.conf') -Value @(
            '', "listen_addresses = '127.0.0.1'", "port = $NextStepDbPort")
        $secretData = @{ superUser = 'postgres'; superPassword = $superPassword; appUser = 'nextstep';
            appPassword = $appPassword; database = 'nextstep'; port = $NextStepDbPort }
        $secretData | ConvertTo-Json | Set-Content -LiteralPath $NextStepSecretFile
        Start-Cluster
        $env:PGPASSWORD = $superPassword
        $psqlArgs = @('-h', '127.0.0.1', '-p', $NextStepDbPort, '-U', 'postgres', '-d', 'postgres',
            '-v', 'ON_ERROR_STOP=1', '-q',
            '-c', "CREATE ROLE nextstep LOGIN PASSWORD '$appPassword'",
            '-c', 'CREATE DATABASE nextstep OWNER nextstep')
        try {
            & $psql @psqlArgs | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Creating the nextstep role or database failed' }
        } finally {
            Remove-Item Env:\PGPASSWORD
        }
        Write-Output "Initialized. Credentials saved to $NextStepSecretFile (not in the repository)."
    }
    'start' { Start-Cluster }
    'stop' {
        Invoke-NextStepPgCtl $pgCtl @('stop', '-D', $NextStepDataDir, '-m', 'fast', '-w') | Out-Null
        Write-Output 'Development database stopped.'
    }
    'status' {
        if ((Invoke-NextStepPgCtl $pgCtl @('status', '-D', $NextStepDataDir)) -eq 0) {
            Write-Output 'Development database cluster is running.'
        } else {
            Write-Output 'Development database cluster is stopped.'
        }
        & (Join-Path $pgBin 'pg_isready.exe') -h 127.0.0.1 -p $NextStepDbPort
    }
}


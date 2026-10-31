<#
.SYNOPSIS
  Local backup/restore smoke test for the development database.

.DESCRIPTION
  Dumps the development database with pg_dump, restores it into a scratch database on the same local cluster,
  compares row counts for the NextStep tables, and drops the scratch database. Use only with fictional data.
  This verifies the mechanics locally; it is not a production backup or retention policy.
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')
$secrets = Get-NextStepSecrets
$pgBin = Get-NextStepPgBin
$psqlExe = Join-Path $pgBin 'psql.exe'
$dump = Join-Path $NextStepRoot ('backup-smoke-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.dump')
$scratch = 'nextstep_restore_smoke'
$tables = @('accounts', 'applications', 'tasks', 'interviews')
$connection = @('-h', '127.0.0.1', '-p', $secrets.port, '-U', $secrets.superUser)
$psqlArgs = $connection + @('-v', 'ON_ERROR_STOP=1', '-At')

$env:PGPASSWORD = $secrets.superPassword
try {
    & (Join-Path $pgBin 'pg_dump.exe') @connection -Fc -f $dump $secrets.database
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
    & $psqlExe @psqlArgs -d postgres -c "DROP DATABASE IF EXISTS $scratch" -c "CREATE DATABASE $scratch" | Out-Null
    & (Join-Path $pgBin 'pg_restore.exe') @connection --no-owner -d $scratch $dump
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed' }
    $mismatch = $false
    foreach ($table in $tables) {
        $original = & $psqlExe @psqlArgs -d $secrets.database -c "SELECT count(*) FROM $table"
        $restored = & $psqlExe @psqlArgs -d $scratch -c "SELECT count(*) FROM $table"
        if ($original -eq $restored) { $status = 'match' } else { $status = 'MISMATCH'; $mismatch = $true }
        Write-Output ('{0,-13} original={1,-6} restored={2,-6} {3}' -f $table, $original, $restored, $status)
    }
    & $psqlExe @psqlArgs -d postgres -c "DROP DATABASE $scratch" | Out-Null
    Remove-Item -LiteralPath $dump -Force
    if ($mismatch) { throw 'Row counts differ after restore' }
    Write-Output 'Backup and restore smoke test passed.'
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}


<#
.SYNOPSIS
  Backup and restore drill for a NextStep database (the local development database by default).

.DESCRIPTION
  1. Dumps the database with pg_dump (custom format).
  2. Restores the dump into a scratch database on the same cluster with pg_restore.
  3. Compares every table in the public schema: row count and an order-independent checksum of all row contents.
  4. Rehearses the restore runbook's deletion step: records one account in account_deletions (as if it had been deleted
     after the backup was taken), runs nextstep_reapply_deletions(), and checks that the account and its records are
     gone from the restored copy.
  5. Drops the scratch database and deletes the dump.
  Use it only with fictional data. The source database is only read.

.PARAMETER Database
  Database to back up. Defaults to the development database from %LOCALAPPDATA%\NextStep\dev-db.json.

.PARAMETER KeepDump
  Keep the dump file instead of deleting it.
#>
param([string]$Database, [switch]$KeepDump)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'NextStepEnv.ps1')
$secrets = Get-NextStepSecrets
$pgBin = Get-NextStepPgBin
$psqlExe = Join-Path $pgBin 'psql.exe'
if (-not $Database) { $Database = $secrets.database }
$dump = Join-Path $NextStepRoot ('backup-drill-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.dump')
$scratch = 'nextstep_restore_drill'
$connection = @('-h', '127.0.0.1', '-p', $secrets.port, '-U', $secrets.superUser)
$psqlArgs = $connection + @('-v', 'ON_ERROR_STOP=1', '-At', '-q')

function Invoke-Sql([string]$Db, [string]$Sql) {
    $output = & $psqlExe @psqlArgs -d $Db -c $Sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed on $Db" }
    return $output
}

$env:PGPASSWORD = $secrets.superPassword
try {
    $started = Get-Date
    & (Join-Path $pgBin 'pg_dump.exe') @connection --format=custom --no-owner --file=$dump $Database
    if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed' }
    $size = [Math]::Round((Get-Item -LiteralPath $dump).Length / 1KB, 1)
    Write-Output ("Backed up {0} to {1} ({2} KB)" -f $Database, $dump, $size)

    Invoke-Sql 'postgres' "DROP DATABASE IF EXISTS $scratch" | Out-Null
    Invoke-Sql 'postgres' "CREATE DATABASE $scratch" | Out-Null
    & (Join-Path $pgBin 'pg_restore.exe') @connection --no-owner --exit-on-error -d $scratch $dump
    if ($LASTEXITCODE -ne 0) { throw 'pg_restore failed' }
    Write-Output ("Restored into {0} in {1:N1} s" -f $scratch, ((Get-Date) - $started).TotalSeconds)

    $tables = Invoke-Sql $Database "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename"
    $checksum = "SELECT count(*) || ' ' || coalesce(md5(string_agg(md5(t::text), '' ORDER BY md5(t::text))), '-') FROM {0} t"
    $mismatch = $false
    foreach ($table in $tables) {
        $original = Invoke-Sql $Database ($checksum -f $table)
        $restored = Invoke-Sql $scratch ($checksum -f $table)
        if ($original -eq $restored) { $status = 'match' } else { $status = 'MISMATCH'; $mismatch = $true }
        $rows = ($original -split ' ')[0]
        Write-Output ('{0,-28} rows={1,-7} {2}' -f $table, $rows, $status)
    }
    if ($mismatch) { throw 'The restored copy differs from the source' }

    # Rehearse the runbook: a deletion made after the backup must be applied again to the restored copy.
    $account = Invoke-Sql $scratch "SELECT id FROM accounts WHERE kind = 'NORMAL' ORDER BY created_at LIMIT 1"
    if ($account) {
        Invoke-Sql $scratch "INSERT INTO account_deletions (account_id, deleted_at, reason) VALUES ('$account', now(), 'REQUESTED') ON CONFLICT DO NOTHING" | Out-Null
        $removed = Invoke-Sql $scratch 'SELECT nextstep_reapply_deletions()'
        $left = Invoke-Sql $scratch "SELECT (SELECT count(*) FROM accounts WHERE id = '$account') + (SELECT count(*) FROM applications WHERE owner_id = '$account')"
        if ([int]$left -ne 0) { throw 'Re-applying deletions left the account in the restored copy' }
        Write-Output "Re-applied deletions in the restored copy: $removed account(s) removed, none left behind."
    } else {
        Write-Output 'No normal account in the backup, so the deletion re-apply step was skipped.'
    }

    Invoke-Sql 'postgres' "DROP DATABASE $scratch" | Out-Null
    if (-not $KeepDump) { Remove-Item -LiteralPath $dump -Force }
    Write-Output 'Backup and restore drill passed.'
} finally {
    Remove-Item Env:\PGPASSWORD -ErrorAction SilentlyContinue
}

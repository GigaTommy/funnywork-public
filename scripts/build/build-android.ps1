$ErrorActionPreference = 'Stop'
$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
python (Join-Path $Root 'scripts/build/build-android.py') @args
exit $LASTEXITCODE

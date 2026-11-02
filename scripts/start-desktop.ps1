param(
    [switch]$Build,
    [switch]$Check,
    [string]$Server
)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$projectRoot = Split-Path -Parent $PSScriptRoot
$pythonExecutable = Join-Path $projectRoot '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonExecutable)) {
    throw 'Run python -m venv .venv and .venv\Scripts\python.exe -m pip install -r requirements.txt first.'
}
$desktopArguments = @('-m', 'funnyworker.desktop')
if ($Build) { $desktopArguments += '--build' }
if ($Check) { $desktopArguments += '--check' }
if ($Server) { $desktopArguments += @('--server', $Server) }
Push-Location -LiteralPath $projectRoot
try {
    if ($Build) {
        & pnpm.cmd --dir (Join-Path $projectRoot 'apps/desktop') --filter '@deepseek-ai/dsh-desktop' exec install-electron
        if ($LASTEXITCODE -ne 0) { throw 'Electron development runtime preparation failed' }
    }
    & $pythonExecutable @desktopArguments
    if ($LASTEXITCODE -ne 0) { throw "FunnyWorker desktop exited with code $LASTEXITCODE" }
} finally {
    Pop-Location
}

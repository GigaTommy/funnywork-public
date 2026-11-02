param([switch]$Build)
$ErrorActionPreference = 'Stop'
$env:PYTHONIOENCODING = 'utf-8'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectRoot
try {
    & '.venv\Scripts\python.exe' scripts/prepare-ruoyi.py
    if ($LASTEXITCODE -ne 0) { throw 'Local database preparation failed' }
    $agentJar = Join-Path $projectRoot 'services\business\target\funnyworker-server-0.1.0-SNAPSHOT.jar'
    $ruoyiJar = Join-Path $projectRoot 'services\identity\ruoyi-admin\target\ruoyi-admin.jar'
    if ($Build -or -not (Test-Path -LiteralPath $agentJar) -or -not (Test-Path -LiteralPath $ruoyiJar)) { & "$PSScriptRoot\build-server.ps1" }
    $tokenFile = Join-Path $projectRoot '.local\java-admin.token'
    if (-not (Test-Path -LiteralPath $tokenFile)) {
        & '.venv\Scripts\python.exe' -c 'import secrets; from pathlib import Path; from funnyworker.model_config import private_write; private_write(Path(".local/java-admin.token"), secrets.token_hex(32).encode())'
        if ($LASTEXITCODE -ne 0) { throw 'Bootstrap token preparation failed' }
    }
    $env:FUNNYWORK_BOOTSTRAP_TOKEN = (Get-Content -LiteralPath $tokenFile -Raw).Trim()
    $env:FUNNYWORK_RUOYI_ACCOUNT_ENABLED = 'true'
    $env:FUNNYWORK_LOCAL_MANAGEMENT_BOOTSTRAP = 'true'
    if (-not $env:FUNNYWORK_PORT) { $env:FUNNYWORK_PORT = '8765' }
    & '.venv\Scripts\python.exe' -c 'import json,os; from pathlib import Path; from funnyworker.model_config import private_write; private_write(Path(".local/prototype/client/control-connection.json"), json.dumps({"url":"http://127.0.0.1:"+os.environ["FUNNYWORK_PORT"]}).encode())'
    if ($LASTEXITCODE -ne 0) { throw 'Client connection preparation failed' }
    $ruoyiConfig = (Join-Path $projectRoot '.local\ruoyi\application-funnyworker.yml').Replace('\', '/')
    $ruoyiProcess = Start-Process -FilePath 'java.exe' -ArgumentList @('-jar', ('"' + $ruoyiJar + '"'), '--spring.profiles.active=dev,funnyworker', ('"--spring.config.additional-location=file:' + $ruoyiConfig + '"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput '.local\ruoyi-server.log' -RedirectStandardError '.local\ruoyi-server-error.log'
    try { & java.exe -jar $agentJar } finally { if (-not $ruoyiProcess.HasExited) { Stop-Process -Id $ruoyiProcess.Id } }
    if ($LASTEXITCODE -ne 0) { throw "Agent server exited with code $LASTEXITCODE" }
} finally { Pop-Location }

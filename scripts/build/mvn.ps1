param([Parameter(ValueFromRemainingArguments = $true)][string[]]$MavenArguments)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$toolsRoot = Join-Path $projectRoot '.local\tools'
$mavenExecutable = Join-Path $toolsRoot 'apache-maven-3.9.9\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $mavenExecutable)) {
    New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null
    $archive = Join-Path $toolsRoot 'apache-maven-3.9.9-bin.zip'
    $downloadRoot = if ($env:FUNNYWORKER_MAVEN_DOWNLOAD_ROOT) { $env:FUNNYWORKER_MAVEN_DOWNLOAD_ROOT } else { 'https://repo.maven.apache.org/maven2' }
    $archiveUrl = "$downloadRoot/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
    Invoke-WebRequest -Uri $archiveUrl -OutFile $archive
    $checksum = (Invoke-WebRequest -Uri "$archiveUrl.sha512").Content.Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA512).Hash -ne $checksum) { throw 'Maven archive checksum mismatch' }
    Expand-Archive -LiteralPath $archive -DestinationPath $toolsRoot -Force
}
$settings = Join-Path $toolsRoot 'settings.xml'
$arguments = @()
if (Test-Path -LiteralPath $settings) { $arguments += @('-s', $settings) }
Push-Location -LiteralPath $projectRoot
try {
    & $mavenExecutable @arguments @MavenArguments
    if ($LASTEXITCODE -ne 0) { throw "Maven exited with code $LASTEXITCODE" }
} finally { Pop-Location }

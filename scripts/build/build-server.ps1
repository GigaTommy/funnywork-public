$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location -LiteralPath $projectRoot
try {
    & '.venv\Scripts\python.exe' scripts/prepare-config-templates.py
    if ($LASTEXITCODE -ne 0) { throw 'Configuration preparation failed' }
    & '.venv\Scripts\python.exe' scripts/setup/prepare-management.py
    if ($LASTEXITCODE -ne 0) { throw 'Management dependency preparation, build or typecheck failed' }
    $adminPath = Join-Path $projectRoot 'services\business\src\main\resources\static\admin'
    $resolvedAdminPath = [System.IO.Path]::GetFullPath($adminPath)
    if (-not $resolvedAdminPath.StartsWith($projectRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid admin build path' }
    if (Test-Path -LiteralPath $resolvedAdminPath) { Remove-Item -LiteralPath $resolvedAdminPath -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $resolvedAdminPath | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $projectRoot 'apps\management\dist') -Force | Copy-Item -Destination $resolvedAdminPath -Recurse
    & "$PSScriptRoot\mvn.ps1" -f services/identity/pom.xml -pl ruoyi-admin -am -q '-DskipTests=false' "-Dtest=DirectorySyncTest,HttpDirectoryAdapterTest" '-Dsurefire.failIfNoSpecifiedTests=false' package
    if ($LASTEXITCODE -ne 0) { throw 'Identity build or directory tests failed' }
    $desktopTests = 'ManagementBindingsIntegrationTest,AdminWebIntegrationTest,EmployeeAccountsIntegrationTest,ManagementIdentityIntegrationTest,LocalManagementBootstrapIntegrationTest,EmployeeChatsIntegrationTest,ControlIntegrationTest,OfficeDocumentsTest,OfficeToolsIntegrationTest,ModelProfilesIntegrationTest,WorkContextsIntegrationTest,DesktopDeviceLinksTest,DesktopHistorySyncTest,DesktopPoliciesIntegrationTest,DesktopModelChecksTest,DesktopModelPublicationTest,DesktopModelReleaseIntegrationTest,DesktopModelReleasesTest,DesktopResultsTest,DesktopSessionForkTest,ManagedDesktopDevicesTest,MobileMenuHttpTest,ModelMiddlewareIntegrationTest,ModelBudgetsIntegrationTest'
    & "$PSScriptRoot\mvn.ps1" -f services/business/pom.xml -q "-Dtest=$desktopTests" verify
} finally { Pop-Location }

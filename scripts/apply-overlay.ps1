# Copies overlay into a full QMSync checkout and adds the 2-line hook.
# Usage: .\apply-overlay.ps1 -ModDir "C:\path\to\QMSync" [-MinecraftVersion "1.21.11"]
param(
    [Parameter(Mandatory=$true)][string]$ModDir,
    [string]$OverlayDir = (Join-Path $PSScriptRoot "..\client\overlay\src\client\java\red\jackf\chesttracker\impl\cmsync")
)
$ErrorActionPreference = "Stop"
$dest = Join-Path $ModDir "src\client\java\red\jackf\chesttracker\impl\cmsync"
New-Item -ItemType Directory -Path $dest -Force | Out-Null
Copy-Item (Join-Path $OverlayDir "*.java") -Destination $dest -Force
Write-Host "copied $(@(Get-ChildItem $dest\*.java).Count) files -> $dest"

$ct = Join-Path $ModDir "src\client\java\red\jackf\chesttracker\impl\ChestTracker.java"
if (-not (Test-Path -LiteralPath $ct)) { Write-Warning "ChestTracker.java not found, hook skipped"; exit 0 }
$text = Get-Content -LiteralPath $ct -Raw
if ($text -notmatch "cmsync\.CMSyncManager") {
    $text = $text -replace "(QMSyncManager\.INSTANCE\.setup\(\);)",
        "`$1`r`n        red.jackf.chesttracker.impl.cmsync.CMSyncManager.INSTANCE.setup();`r`n        red.jackf.chesttracker.impl.cmsync.CMSyncCommand.register();"
    Set-Content -LiteralPath $ct -Value $text -NoNewline
    Write-Host "hooked ChestTracker.onInitializeClient()"
} else {
    Write-Host "hook already present"
}
Write-Host "next: cd $ModDir; .\gradlew.bat check build"

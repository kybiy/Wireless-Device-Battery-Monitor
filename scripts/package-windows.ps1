param(
    [string] $Jdk = 'dist/toolchain/jdk-25.0.5.1+2',
    [string] $Wix = 'dist/toolchain/wix',
    [string] $OutputDirectory = 'dist/v1.1.1'
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousLocation = (Get-Location).Path
$previousPath = $env:Path

try {
    Set-Location -LiteralPath $repoRoot
    $JdkPath = (Resolve-Path -LiteralPath $Jdk).Path
    $WixPath = (Resolve-Path -LiteralPath $Wix).Path
    $packager = Join-Path $JdkPath 'bin\jpackage.exe'
    if (!(Test-Path -LiteralPath $packager)) {
        throw "jpackage.exe not found at: $packager"
    }

    [xml]$pom = Get-Content -LiteralPath (Join-Path $repoRoot 'pom.xml')
    $appVersion = $pom.project.version.Trim()
    $upgradeUuid = '7c2f4b5e-3d9a-4e8c-91f2-8a6b4d2e1c90'
    $mainJarName = "wireless-battery-java-$appVersion.jar"
    $mainJarSource = Join-Path $repoRoot "target\$mainJarName"
    if (!(Test-Path -LiteralPath $mainJarSource)) {
        throw "Built JAR not found at $mainJarSource. Run 'mvn clean package' first."
    }

    # Compile immediate CloseHelper.exe (windowless x64 GUI subsystem) using built-in .NET Framework csc.exe
    $cscPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (!(Test-Path -LiteralPath $cscPath)) {
        throw "csc.exe not found at: $cscPath"
    }

    $wixStagingDir = Join-Path $repoRoot 'target\wix-resources'
    if (Test-Path -LiteralPath $wixStagingDir) {
        Remove-Item -LiteralPath $wixStagingDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $wixStagingDir -Force | Out-Null
    Copy-Item -Path (Join-Path $repoRoot 'packaging\wix\*.wxs') -Destination $wixStagingDir -Force
    Copy-Item -Path (Join-Path $repoRoot 'packaging\wix\*.wxl') -Destination $wixStagingDir -Force

    $closeHelperExe = Join-Path $wixStagingDir 'closehelper.exe'
    $closeHelperCs = Join-Path $repoRoot 'packaging\wix\CloseHelper.cs'
    & $cscPath /nologo /target:winexe /platform:x64 /optimize+ "/out:$closeHelperExe" $closeHelperCs
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $closeHelperExe)) {
        throw 'Failed to compile CloseHelper.cs'
    }

    # Generate staged overrides.wxi pointing to compiled closehelper.exe and disabling accidental downgrades
    $escapedHelperPath = $closeHelperExe.Replace('&', '&amp;').Replace('"', '&quot;')
    @(
        '<?xml version="1.0" encoding="utf-8"?>',
        '<Include xmlns="http://schemas.microsoft.com/wix/2006/wi">',
        '  <?undef JpAllowDowngrades ?>',
        "  <?define WbCloseHelperPath=`"$escapedHelperPath`" ?>",
        '</Include>'
    ) | Set-Content -Encoding UTF8 -LiteralPath (Join-Path $wixStagingDir 'overrides.wxi')

    $env:Path = "$WixPath;$previousPath"
    if (Test-Path -LiteralPath $OutputDirectory) {
        Remove-Item -LiteralPath $OutputDirectory -Recurse -Force
    }
    $output = (New-Item -ItemType Directory -Path $OutputDirectory -Force).FullName

    $inputDirectory = Join-Path $repoRoot 'target\jpackage-input'
    Get-ChildItem -LiteralPath $inputDirectory -Filter 'wireless-battery-java-*.jar' -ErrorAction SilentlyContinue | Remove-Item -Force
    Copy-Item -LiteralPath $mainJarSource -Destination $inputDirectory -Force

    $licensesDir = New-Item -ItemType Directory -Force -Path (Join-Path $inputDirectory 'licenses')
    Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination (Join-Path $licensesDir.FullName 'WirelessBattery-GPL-3.0.txt') -Force
    if (Test-Path -LiteralPath (Join-Path $repoRoot 'docs\licenses')) {
        Copy-Item -Path (Join-Path $repoRoot 'docs\licenses\*') -Destination $licensesDir.FullName -Force
    }

    $imageRoot = (New-Item -ItemType Directory -Path (Join-Path $output 'app-image') -Force).FullName
    $commonArgs = @(
        '--name', 'WirelessBattery',
        '--app-version', $appVersion,
        '--vendor', 'kybiy',
        '--description', 'Wireless Device Battery Monitor for Windows 11 (Delux M800 Pro, Akko S9 Ultra, GameSir Cyclone 2)'
    )

    Write-Host "Building app-image for WirelessBattery v$appVersion..."
    & $packager --type app-image @commonArgs `
        --input $inputDirectory `
        --dest $imageRoot `
        --main-jar $mainJarName `
        --main-class 'br.com.wirelessbattery.Main' `
        --icon (Join-Path $repoRoot 'src\main\resources\icons\app-icon.ico') `
        --java-options '-Dfile.encoding=UTF-8' `
        --java-options '--enable-native-access=ALL-UNNAMED'
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage app-image failed with exit code $LASTEXITCODE"
    }

    $appImageDir = Join-Path $imageRoot 'WirelessBattery'
    $rootLicenses = New-Item -ItemType Directory -Force -Path (Join-Path $appImageDir 'licenses')
    Copy-Item -Path (Join-Path $licensesDir.FullName '*') -Destination $rootLicenses.FullName -Force

    Write-Host "Building upgradable Windows EXE installer for WirelessBattery v$appVersion (UpgradeUUID=$upgradeUuid)..."
    & $packager --type exe @commonArgs `
        --app-image $appImageDir `
        --dest $output `
        --resource-dir $wixStagingDir `
        --install-dir 'WirelessBattery' `
        --icon (Join-Path $repoRoot 'src\main\resources\icons\app-icon.ico') `
        --win-dir-chooser `
        --win-menu `
        --win-menu-group 'Wireless Battery Monitor' `
        --win-shortcut `
        --win-shortcut-prompt `
        --win-per-user-install `
        --win-upgrade-uuid $upgradeUuid
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage exe installer failed with exit code $LASTEXITCODE"
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $portableZip = Join-Path $output "WirelessBattery-$appVersion-portable-win-x64.zip"
    if (Test-Path -LiteralPath $portableZip) {
        Remove-Item -LiteralPath $portableZip -Force
    }
    [IO.Compression.ZipFile]::CreateFromDirectory($appImageDir, $portableZip, [IO.Compression.CompressionLevel]::Optimal, $true)

    $installerExe = Join-Path $output "WirelessBattery-$appVersion.exe"
    $sumsPath = Join-Path $output 'SHA256SUMS.txt'
    Get-FileHash -LiteralPath $installerExe, $portableZip -Algorithm SHA256 |
        ForEach-Object { '{0}  {1}' -f $_.Hash.ToLowerInvariant(), (Split-Path $_.Path -Leaf) } |
        Set-Content -Encoding ascii -LiteralPath $sumsPath

    Copy-Item -LiteralPath $installerExe -Destination (Join-Path $repoRoot "dist\WirelessBattery-$appVersion.exe") -Force
    Copy-Item -LiteralPath $portableZip -Destination (Join-Path $repoRoot "dist\WirelessBattery-$appVersion-portable-win-x64.zip") -Force
    Copy-Item -LiteralPath $sumsPath -Destination (Join-Path $repoRoot 'dist\SHA256SUMS.txt') -Force

    Write-Host "`nArtifacts generated successfully:"
    Get-Content -LiteralPath $sumsPath
} finally {
    $env:Path = $previousPath
    Set-Location -LiteralPath $previousLocation
}

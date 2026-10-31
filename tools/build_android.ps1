# Build the APK: web build -> cap sync -> Gradle -> artifacts/.
#   npm run android:build                    debug build (com.smartstudy.dev)
#   npm run android:release                  signed release build (com.smartstudy), see docs/decisions/003
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/build_android.ps1 [-Release] [-JavaHome <jdk21>] [-SdkRoot <sdk>] [-SkipWebBuild] [-UseChinaMirror] [-Install [-Serial <adb serial>]]
# JAVA_HOME / ANDROID_HOME are set for this process only and restored afterwards.
param(
    [string]$JavaHome,
    [string]$SdkRoot,
    [switch]$Release,
    [switch]$SkipWebBuild,
    [switch]$UseChinaMirror,
    [switch]$Install,
    # Which device -Install uses when several are connected (see adb devices).
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$saved = @{}
foreach ($name in @('JAVA_HOME', 'ANDROID_HOME', 'GRADLE_USER_HOME')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

try {
    $javaCandidates = if ($JavaHome) { @($JavaHome) } else {
        @($env:JAVA_HOME,
          "$env:ProgramFiles\Android\Android Studio\jbr",
          "$env:LOCALAPPDATA\Programs\Android Studio\jbr") +
        @(Get-ChildItem "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Java" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk-?2[1-9]' } | Sort-Object Name | ForEach-Object FullName)
    }
    $selectedJava = $null
    foreach ($candidate in $javaCandidates) {
        if (!$candidate -or !(Test-Path -LiteralPath "$candidate\bin\javac.exe")) { continue }
        # javac prints its version on stderr; run it through cmd so Windows PowerShell 5 does not treat that as an error.
        $version = (& cmd.exe /c "`"$candidate\bin\javac.exe`" -version 2>&1") -join ' '
        if ($version -match 'javac (\d+)' -and [int]$Matches[1] -ge 21) { $selectedJava = (Resolve-Path -LiteralPath $candidate).Path; break }
    }
    if (!$selectedJava) { throw 'JDK 21+ required. Pass -JavaHome.' }

    if (!$SdkRoot) {
        $SdkRoot = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") |
            Where-Object { $_ -and (Test-Path -LiteralPath "$_\platforms\android-36") } | Select-Object -First 1
    }
    if (!$SdkRoot) { throw 'Android SDK platform 36 required. Pass -SdkRoot.' }
    $SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path

    $env:JAVA_HOME = $selectedJava
    $env:ANDROID_HOME = $SdkRoot
    if (!$env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $projectRoot '.local\gradle' }
    Write-Host "JDK: $selectedJava"
    Write-Host "Android SDK: $SdkRoot"
    [IO.File]::WriteAllText("$projectRoot\android\local.properties", "sdk.dir=$($SdkRoot.Replace('\', '/'))`n")

    Push-Location $projectRoot
    try {
        if (!$SkipWebBuild) {
            & npm.cmd run build
            if ($LASTEXITCODE -ne 0) { throw 'Web build failed.' }
        } else {
            # The existing dist/ is packaged as it is; the version on the settings page comes from it.
            $index = Get-Item -LiteralPath 'dist\index.html' -ErrorAction SilentlyContinue
            if (!$index) { throw 'No dist/: run without -SkipWebBuild.' }
            if ((Get-Item -LiteralPath 'package.json').LastWriteTime -gt $index.LastWriteTime) { Write-Warning 'package.json is newer than dist/: the settings page may show an old version. Run without -SkipWebBuild.' }
        }
        & npx.cmd cap sync android
        if ($LASTEXITCODE -ne 0) { throw 'Capacitor sync failed.' }
        Push-Location android
        try {
            $task = if ($Release) { ':app:assembleRelease' } else { ':app:assembleDebug' }
            # Remove earlier output first, so an old APK can never be copied as this build's result.
            Remove-Item -LiteralPath (Join-Path 'app\build\outputs\apk' $(if ($Release) { 'release' } else { 'debug' })) -Recurse -Force -ErrorAction SilentlyContinue
            $arguments = @($task, '--no-daemon', '--console=plain')
            if ($UseChinaMirror) { $arguments += @('--init-script', (Join-Path $PSScriptRoot 'gradle-china-mirror.init.gradle')) }
            & .\gradlew.bat @arguments
            if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
        } finally { Pop-Location }

        $version = (Get-Content -Raw package.json | ConvertFrom-Json).version
        if ($Release) {
            $built = 'android\app\build\outputs\apk\release\app-release.apk'
            if (!(Test-Path -LiteralPath $built)) { throw 'No signed release APK: keystore.properties was not found (see docs/decisions/003-release-signing.md).' }
            $output = Join-Path $projectRoot "artifacts\smart-study-$version.apk"
        } else {
            $built = 'android\app\build\outputs\apk\debug\app-debug.apk'
            $output = Join-Path $projectRoot "artifacts\smart-study-$version-debug.apk"
        }
        New-Item -ItemType Directory -Force -Path (Split-Path $output -Parent) | Out-Null
        Copy-Item -LiteralPath $built -Destination $output -Force
        if ($Release) {
            # Show the signing certificate, so a build signed with the wrong key is noticed before installing.
            $apksigner = Get-ChildItem "$SdkRoot\build-tools" -Directory | Sort-Object { [version]($_.Name -replace '[^\d.].*$', '') } | Select-Object -Last 1
            & "$($apksigner.FullName)\apksigner.bat" verify --print-certs $output
            if ($LASTEXITCODE -ne 0) { throw 'The release APK is not signed correctly.' }
        }
        Get-Item -LiteralPath $output | Select-Object FullName, Length
        Get-FileHash -LiteralPath $output -Algorithm SHA256 | Select-Object Hash

        if ($Install) {
            $adb = Join-Path $SdkRoot 'platform-tools\adb.exe'
            if (!$Serial -and !$env:ANDROID_SERIAL) {
                $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' } | ForEach-Object { ($_ -split '\t')[0] })
                if ($devices.Count -gt 1) { throw "Several devices are connected ($($devices -join ', ')). Pass -Serial <serial>." }
            }
            $target = if ($Serial) { @('-s', $Serial) } else { @() }
            & $adb @target install -r $output
            if ($LASTEXITCODE -ne 0) { throw 'adb install failed (is USB debugging authorised on the device?).' }
        }
    } finally { Pop-Location }
} finally {
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}

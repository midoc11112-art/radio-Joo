$ErrorActionPreference = "Stop"
Set-Location "C:\Users\X\Desktop\radio-Joo"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Radio Joo - Portable Android Builder" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

$toolsRoot = "C:\Users\X\Desktop\radio-Joo\.local_tools"
New-Item -ItemType Directory -Force -Path $toolsRoot | Out-Null

# 1. البحث عن Java 17 أو تحميل نسخة محمولة خفيفة (Microsoft OpenJDK 17) لمرة واحدة
$javaExe = Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if ($javaExe) {
    $env:JAVA_HOME = Split-Path (Split-Path $javaExe)
} else {
    $localJava = Get-ChildItem -Path $toolsRoot -Filter "java.exe" -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $localJava) {
        Write-Host "[1/3] Downloading Portable OpenJDK 17 (once)..." -ForegroundColor Yellow
        $jdkZip = Join-Path $toolsRoot "jdk17.zip"
        Invoke-WebRequest -Uri "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip" -OutFile $jdkZip
        Expand-Archive -Path $jdkZip -DestinationPath $toolsRoot -Force
        Remove-Item $jdkZip -Force
        $localJava = Get-ChildItem -Path $toolsRoot -Filter "java.exe" -Recurse | Select-Object -First 1
    }
    $env:JAVA_HOME = Split-Path (Split-Path $localJava.FullName)
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}
Write-Host "[OK] JAVA_HOME: $env:JAVA_HOME" -ForegroundColor Green

# 2. البحث عن Android SDK أو تجهيز نسخة محمولة خفيفة
$sdkDir = $env:ANDROID_HOME
if (-not $sdkDir -or -not (Test-Path $sdkDir)) {
    if (Test-Path "$env:LOCALAPPDATA\Android\Sdk") {
        $sdkDir = "$env:LOCALAPPDATA\Android\Sdk"
    } else {
        $sdkDir = Join-Path $toolsRoot "android-sdk"
        $sdkManager = Join-Path $sdkDir "cmdline-tools\latest\bin\sdkmanager.bat"
        if (-not (Test-Path $sdkManager)) {
            Write-Host "[2/3] Downloading Portable Android SDK Tools (once)..." -ForegroundColor Yellow
            $cmdZip = Join-Path $toolsRoot "cmdtools.zip"
            Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $cmdZip
            $tempExtract = Join-Path $toolsRoot "temp_cmd"
            Expand-Archive -Path $cmdZip -DestinationPath $tempExtract -Force
            Remove-Item $cmdZip -Force
            New-Item -ItemType Directory -Force -Path (Join-Path $sdkDir "cmdline-tools\latest") | Out-Null
            Move-Item -Path (Join-Path $tempExtract "cmdline-tools\*") -Destination (Join-Path $sdkDir "cmdline-tools\latest") -Force
            Remove-Item $tempExtract -Recurse -Force
        }
    }
}
$env:ANDROID_HOME = $sdkDir
$env:ANDROID_SDK_ROOT = $sdkDir
Write-Host "[OK] ANDROID_HOME: $sdkDir" -ForegroundColor Green

# ربط المشروع بمسار Android SDK المحلي
$sdkEscaped = $sdkDir.Replace("\", "\\")
Set-Content -Path "C:\Users\X\Desktop\radio-Joo\local.properties" -Value "sdk.dir=$sdkEscaped" -Encoding ASCII

# 3. تحديد Gradle المحلي الجاهز وبناء التطبيق
$gradleBin = (Get-ChildItem -Path "C:\Users\X\Desktop\radio-Joo\.local_gradle" -Filter "gradle.bat" -Recurse | Select-Object -First 1).FullName
$env:GRADLE_OPTS = "-Xmx2048m -XX:+UseParallelGC"

Remove-Item -Force "C:\Users\X\Desktop\radio-Joo\app\build\outputs\apk\debug\app-debug.apk" -ErrorAction SilentlyContinue; Write-Host "[3/3] Building Radio Joo APK locally..." -ForegroundColor Cyan
# قبول تراخيص Android SDK تلقائياً أثناء البناء
$yesInput = "y`n" * 20
$yesInput | & $gradleBin :app:assembleDebug --no-daemon

$apkSource = "C:\Users\X\Desktop\radio-Joo\app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apkSource) {
    $desktop = [Environment]::GetFolderPath("Desktop")
    $baseName = "Radio Joo"
    $destFile = Join-Path $desktop "$baseName.apk"
    $counter = 1

    while (Test-Path $destFile) {
        $destFile = Join-Path $desktop "$baseName $counter.apk"
        $counter++
    }

    Copy-Item -Path $apkSource -Destination $destFile -Force
    $selfPath = "C:\Users\X\Desktop\radio-Joo\build_local.ps1"
    $selfCode = Get-Content $selfPath -Raw -Encoding UTF8
    if ($selfCode -notmatch "--offline") {
        $selfCode = $selfCode -replace ":app:assembleDebug --no-daemon", ":app:assembleDebug --no-daemon --offline"
        Set-Content -Path $selfPath -Value $selfCode -Encoding UTF8
    }
    Write-Host "  [OK] Offline mode locked for future builds (0 MB data usage)!" -ForegroundColor Cyan
    Write-Host "`n==========================================" -ForegroundColor Green
    Write-Host "  [SUCCESS] APK Ready on Desktop:" -ForegroundColor Green
    Write-Host "  -> $destFile" -ForegroundColor Yellow
    Write-Host "==========================================" -ForegroundColor Green
} else {
    Write-Host "`n[ERROR] APK build failed! Check messages above." -ForegroundColor Red
}



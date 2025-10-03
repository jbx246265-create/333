Param(
  [string]$SdkApiLevel = "34",
  [string]$BuildTools = "34.0.0",
  [string]$GradleVersion = "8.7"
)

$ErrorActionPreference = "Stop"

function Ensure-Dir($p) { if (-not (Test-Path $p)) { New-Item -ItemType Directory -Force -Path $p | Out-Null } }

$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Tools = Join-Path $ProjectRoot ".tools"
$Dist = Join-Path $ProjectRoot "dist"
Ensure-Dir $Tools
Ensure-Dir $Dist

# 1) JDK 17 (Temurin)
$JdkZip = Join-Path $Tools "jdk17.zip"
$JdkDir = Join-Path $Tools "jdk17"
if (-not (Test-Path $JdkDir)) {
  Write-Host "Downloading JDK 17..."
  $jdkUrls = @(
    "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64",
    "https://github.com/adoptium/temurin17-binaries/releases/latest/download/OpenJDK17U-jdk_x64_windows_hotspot.zip",
    "https://download.java.net/java/GA/jdk17/latest/binaries/openjdk-17_windows-x64_bin.zip"
  )
  $downloaded = $false
  foreach ($u in $jdkUrls) {
    try {
      Invoke-WebRequest -Uri $u -OutFile $JdkZip -UseBasicParsing
      $downloaded = $true; break
    } catch { Write-Host "JDK URL failed: $u" }
  }
  if (-not $downloaded) { throw "Failed to download JDK 17 from all URLs" }
  Expand-Archive -Force -Path $JdkZip -DestinationPath $JdkDir
}
$JdkHome = (Get-ChildItem $JdkDir | Where-Object { $_.PSIsContainer -and ($_.Name -like "jdk-*") } | Select-Object -First 1).FullName
$env:JAVA_HOME = $JdkHome
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

# 2) Gradle
$GradleZip = Join-Path $Tools "gradle-$GradleVersion-bin.zip"
$GradleDir = Join-Path $Tools "gradle"
if (-not (Test-Path (Join-Path $GradleDir "gradle-$GradleVersion"))) {
  Ensure-Dir $GradleDir
  Write-Host "Downloading Gradle $GradleVersion..."
  Invoke-WebRequest -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $GradleZip
  Expand-Archive -Force -Path $GradleZip -DestinationPath $GradleDir
}
$GradleExe = Join-Path (Join-Path $GradleDir "gradle-$GradleVersion") "bin/gradle.bat"

# 3) Android SDK (cmdline-tools)
$SdkRoot = Join-Path $Tools "android-sdk"
$CmdlineDir = Join-Path $SdkRoot "cmdline-tools"
$CmdlineLatest = Join-Path $CmdlineDir "latest"
if (-not (Test-Path (Join-Path $CmdlineLatest "bin"))) {
  Ensure-Dir $CmdlineDir
  $CmdZip = Join-Path $Tools "cmdline-tools.zip"
  Write-Host "Downloading Android commandline-tools..."
  # 备用链接可能更新版本号，如失败请手动替换为最新版本号
  $cmdUrl = "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  try {
    Invoke-WebRequest -Uri $cmdUrl -OutFile $CmdZip
  } catch {
    # 备选常见版本
    $cmdUrl = "https://dl.google.com/android/repository/commandlinetools-win-9477386_latest.zip"
    Invoke-WebRequest -Uri $cmdUrl -OutFile $CmdZip
  }
  $TmpExtract = Join-Path $Tools "cmd-tmp"
  if (Test-Path $TmpExtract) { Remove-Item -Recurse -Force $TmpExtract }
  Expand-Archive -Force -Path $CmdZip -DestinationPath $TmpExtract
  # 解压后通常为 cmdline-tools/*，我们将其放到 latest/
  Ensure-Dir $CmdlineLatest
  Copy-Item -Recurse -Force (Join-Path $TmpExtract "cmdline-tools/*") $CmdlineLatest
  Remove-Item -Recurse -Force $TmpExtract
}
$env:ANDROID_SDK_ROOT = $SdkRoot
$SdkManager = Join-Path $CmdlineLatest "bin\sdkmanager.bat"

# 4) 接受许可 & 安装组件
Write-Host "Installing Android SDK packages..."
& $SdkManager "platform-tools" "platforms;android-$SdkApiLevel" "build-tools;$BuildTools"

# 接受许可证（循环输入 y）
$y = ("y`n" * 200)
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $SdkManager
$psi.Arguments = "--licenses"
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$proc = [System.Diagnostics.Process]::Start($psi)
$proc.StandardInput.Write($y)
$proc.StandardInput.Close()
$proc.WaitForExit()
Write-Host $proc.StandardOutput.ReadToEnd()
Write-Host $proc.StandardError.ReadToEnd()

# 5) 写入 local.properties
$LocalProps = @()
$LocalProps += "sdk.dir=$($SdkRoot -replace '\\','/')"
Set-Content -Path (Join-Path $ProjectRoot "local.properties") -Value ($LocalProps -join "`n")

# 6) 构建 APK
Write-Host "Building debug APK..."
& $GradleExe -p $ProjectRoot :app:assembleDebug

# 7) 拷贝与压缩产物
$ApkPath = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $ApkPath)) { throw "APK not found: $ApkPath" }
$OutApk = Join-Path $Dist "elder-stt-app-debug.apk"
Copy-Item -Force $ApkPath $OutApk
$ZipPath = Join-Path $Dist "elder-stt-debug.zip"
if (Test-Path $ZipPath) { Remove-Item -Force $ZipPath }
Compress-Archive -Force -Path $OutApk -DestinationPath $ZipPath

Write-Host "Done. APK: $OutApk"
Write-Host "ZIP:  $ZipPath"

# 在受限环境里引导 Android SDK 与 Gradle
#
# 这个脚本的存在理由是一个很具体的环境问题：
#   Windows 的 schannel 在 TLS 握手时要往 %APPDATA%\Microsoft\Crypto 写临时密钥容器。
#   如果那个目录不可写，shell 里所有 HTTPS 请求都会失败，报
#       schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS
#   这看起来像"网络被墙"，其实不是：DNS 正常、TCP 443 也能连上，只是 TLS 起不来。
#   JDK 的 JSSE 不用 schannel，所以用 tools/Download.java 就能正常下载。
#
# 不需要这个环境问题的普通机器，直接装 Android Studio 即可，不必运行本脚本。
#
# 用法： pwsh -File tools/setup-sdk.ps1

param(
    [string]$SdkDir = (Join-Path $PSScriptRoot '..\.android-sdk'),
    [string]$ToolsDir = (Join-Path $PSScriptRoot '..\_tools'),
    [string]$GradleVersion = '8.11.1',
    [string]$CmdlineToolsZip = 'commandlinetools-win-16111833_latest.zip',
    [string]$PlatformPackage = 'platforms/android-35',
    [string]$BuildToolsPackage = 'build-tools/35.0.0'
)

$ErrorActionPreference = 'Stop'

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if (-not (Get-Command $java -ErrorAction SilentlyContinue)) {
    throw "找不到 java。请先安装 JDK 17 并设置 JAVA_HOME。"
}

$download = Join-Path $PSScriptRoot 'Download.java'
if (-not (Test-Path $download)) { throw "找不到 $download" }

Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-File($url, $out) {
    Write-Host "### 下载 $url" -ForegroundColor Cyan
    & $java $download $url $out
    if ($LASTEXITCODE -ne 0) { throw "下载失败：$url" }
}

# ---------------------------------------------------------------- SDK 命令行工具
$sdkAbs = [System.IO.Path]::GetFullPath($SdkDir)
$toolsAbs = [System.IO.Path]::GetFullPath($ToolsDir)
New-Item -ItemType Directory -Force -Path $sdkAbs, $toolsAbs | Out-Null

$ctZip = Join-Path $toolsAbs $CmdlineToolsZip
if (-not (Test-Path $ctZip)) {
    Get-File "https://dl.google.com/android/repository/$CmdlineToolsZip" $ctZip
}

$ctLatest = Join-Path $sdkAbs 'cmdline-tools\latest'
if (-not (Test-Path (Join-Path $ctLatest 'bin\android.exe'))) {
    Write-Host "### 解压命令行工具" -ForegroundColor Cyan
    $tmp = Join-Path $toolsAbs 'ct-extract'
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::ExtractToDirectory($ctZip, $tmp)
    New-Item -ItemType Directory -Force -Path (Split-Path $ctLatest) | Out-Null
    Remove-Item -LiteralPath $ctLatest -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath (Join-Path $tmp 'cmdline-tools') -Destination $ctLatest
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# ---------------------------------------------------------------- SDK 组件
# 注意：这些环境变量必须在调用 android.exe 之前设好。
# sdkmanager / android 默认往 ~/.android 写状态，那个目录在受限环境里不可写。
$env:ANDROID_SDK_ROOT = $sdkAbs
$env:ANDROID_HOME = $sdkAbs
$env:ANDROID_USER_HOME = Join-Path $sdkAbs '.user'
New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null

$androidExe = Join-Path $ctLatest 'bin\android.exe'
if (-not (Test-Path (Join-Path $sdkAbs 'platforms\android-35'))) {
    Write-Host "### 安装 $PlatformPackage" -ForegroundColor Cyan
    & $androidExe --sdk="$sdkAbs" sdk install $PlatformPackage
    if ($LASTEXITCODE -ne 0) { throw "安装 $PlatformPackage 失败" }
}
if (-not (Test-Path (Join-Path $sdkAbs 'build-tools\35.0.0'))) {
    Write-Host "### 安装 $BuildToolsPackage" -ForegroundColor Cyan
    & $androidExe --sdk="$sdkAbs" sdk install $BuildToolsPackage
    if ($LASTEXITCODE -ne 0) { throw "安装 $BuildToolsPackage 失败" }
}
if (-not (Test-Path (Join-Path $sdkAbs 'platform-tools\adb.exe'))) {
    Write-Host "### 安装 platform-tools" -ForegroundColor Cyan
    & $androidExe --sdk="$sdkAbs" sdk install 'platform-tools'
}

# ---------------------------------------------------------------- Gradle
$gradleHome = Join-Path $toolsAbs "gradle-$GradleVersion\gradle-$GradleVersion"
if (-not (Test-Path (Join-Path $gradleHome 'bin\gradle.bat'))) {
    $gradleZip = Join-Path $toolsAbs "gradle-$GradleVersion-bin.zip"
    if (-not (Test-Path $gradleZip)) {
        # 官方地址是 https://services.gradle.org/distributions/...，国内通常很慢，
        # 这里默认走腾讯镜像；需要时换成官方地址即可。
        Get-File "https://mirrors.cloud.tencent.com/gradle/gradle-$GradleVersion-bin.zip" $gradleZip
    }
    Write-Host "### 解压 Gradle" -ForegroundColor Cyan
    Remove-Item -LiteralPath (Join-Path $toolsAbs "gradle-$GradleVersion") -Recurse -Force -ErrorAction SilentlyContinue
    [System.IO.Compression.ZipFile]::ExtractToDirectory($gradleZip, (Join-Path $toolsAbs "gradle-$GradleVersion"))
}

Write-Host ""
Write-Host "完成。SDK: $sdkAbs" -ForegroundColor Green
Write-Host "接着运行： pwsh -File build.ps1" -ForegroundColor Green

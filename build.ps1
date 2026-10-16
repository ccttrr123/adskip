# 构建本工程。用法：
#   pwsh -File build.ps1                      # 跑单元测试 + 打 debug APK
#   pwsh -File build.ps1 :app:testDebugUnitTest
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @(':app:testDebugUnitTest', ':app:assembleDebug')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# 1) JDK：优先环境变量，其次若干常见安装位置
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($candidate in @(
        'D:\java',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Android\Android Studio\jbr'
    )) {
        if (Test-Path (Join-Path $candidate 'bin\java.exe')) {
            $env:JAVA_HOME = $candidate
            break
        }
    }
}
if (-not $env:JAVA_HOME) { throw '找不到 JDK 17，请设置 JAVA_HOME' }
Write-Host "JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Cyan

# 2) Android SDK：工程内自带的优先（由 tools/setup-sdk.ps1 安装）
$localSdk = Join-Path $root '.android-sdk'
if (Test-Path $localSdk) {
    $env:ANDROID_HOME = $localSdk
    $env:ANDROID_SDK_ROOT = $localSdk
    $env:ANDROID_USER_HOME = Join-Path $localSdk '.user'
    New-Item -ItemType Directory -Force -Path $env:ANDROID_USER_HOME | Out-Null
}

# 3) 所有缓存都留在工程目录内，便于在受限环境下构建、也便于整体清理
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $root '.gradle-home' }
$env:GRADLE_OPTS = '-Dfile.encoding=UTF-8'

# 4) 选择 Gradle：工程内的独立发行版优先（无需联网下载），否则退回 wrapper
$localGradle = Join-Path $root '_tools\gradle-8.11.1\gradle-8.11.1\bin\gradle.bat'
if (Test-Path $localGradle) {
    Write-Host "使用工程内 Gradle：$localGradle" -ForegroundColor Cyan
    & $localGradle -p $root @Tasks --console=plain
} else {
    Write-Host '使用 Gradle Wrapper（首次运行会下载发行版）' -ForegroundColor Cyan
    & (Join-Path $root 'gradlew.bat') @Tasks --console=plain
}

exit $LASTEXITCODE

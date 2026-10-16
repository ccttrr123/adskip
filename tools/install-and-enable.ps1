# 通过 USB 把 APK 装到手机，并直接开启无障碍服务。
#
# 为什么需要这个脚本：从 Android 13 起，侧载安装的应用默认不允许开启无障碍服务
# （开关是灰的，提示「受限制的设置」）。而这个限制**只存在于设置界面**，
# 无障碍服务是否启用最终是读的系统 secure 设置，所以用 adb 写一次就绕过去了。
#
# 用法：
#   powershell -File tools/install-and-enable.ps1
#   powershell -File tools/install-and-enable.ps1 -Logcat     # 装完顺便看实时日志
#   powershell -File tools/install-and-enable.ps1 -StatusOnly # 只看当前状态，不做修改

param(
    [string]$Adb = (Join-Path $PSScriptRoot '..\.android-sdk\platform-tools\adb.exe'),
    [string]$Apk = (Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'),
    [string]$Component = 'io.github.adskip/io.github.adskip.service.AdSkipService',
    [switch]$Logcat,
    [switch]$StatusOnly
)

$ErrorActionPreference = 'Stop'

function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }
function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

if (-not (Test-Path $Adb)) { Fail "找不到 adb：$Adb`n请先运行 tools/setup-sdk.ps1" }

# ---------------------------------------------------------------- 检查设备
Step '检查设备连接'
$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_.Trim() -ne '' }
if (-not $devices) {
    Fail @"
没有检测到设备。按下面步骤来（OPPO / ColorOS）：

  1. 手机：设置 → 关于本机 → 版本信息 → 连点「版本号」7 次，开启开发者模式
  2. 手机：设置 → 系统设置 → 开发者选项 → 打开「USB 调试」
  3. 用数据线连接电脑，手机上会弹「是否允许 USB 调试」→ 勾选「始终允许」→ 允许
  4. 再运行本脚本

注：数据线要能传数据，纯充电线不行。连上后手机通知栏应显示"正在通过 USB 充电/传输文件"。
"@
}

$line = $devices[0]
if ($line -match 'unauthorized') {
    Fail '设备未授权。请在手机屏幕上点「允许 USB 调试」（勾选"始终允许"），然后重跑本脚本。'
}
if ($line -match 'offline') {
    Fail '设备处于 offline 状态。拔掉数据线重插一次，或重启手机的 USB 调试开关。'
}
if ($devices.Count -gt 1) {
    Fail "检测到多台设备，请只连接一台：`n$($devices -join "`n")"
}

$model = (& $Adb shell getprop ro.product.model).Trim()
$androidVer = (& $Adb shell getprop ro.build.version.release).Trim()
$sdkVer = (& $Adb shell getprop ro.build.version.sdk).Trim()
$colorOsRaw = (& $Adb shell getprop ro.build.version.oplusrom 2>$null)
if (-not $colorOsRaw) { $colorOsRaw = (& $Adb shell getprop ro.oppo.version 2>$null) }
$colorOsVer = ($colorOsRaw -join '').Trim()
if (-not $colorOsVer) { $colorOsVer = '未知' }
Write-Host "设备：$model    Android $androidVer (API $sdkVer)    ColorOS $colorOsVer" -ForegroundColor Green

# ---------------------------------------------------------------- 当前状态
function Get-EnabledServices {
    $raw = (& $Adb shell settings get secure enabled_accessibility_services) -join ''
    $raw = $raw.Trim()
    if ($raw -eq 'null' -or $raw -eq '') { return @() }
    return $raw -split ':' | Where-Object { $_ -ne '' }
}

Step '当前无障碍服务列表'
$before = Get-EnabledServices
if ($before.Count -eq 0) {
    Write-Host '（空 —— 手机上还没有启用任何无障碍服务）'
} else {
    $before | ForEach-Object { Write-Host "  - $_" }
}
$alreadyOn = $before -contains $Component
Write-Host ("本应用：" + $(if ($alreadyOn) { '已启用' } else { '未启用' })) -ForegroundColor $(if ($alreadyOn) { 'Green' } else { 'Yellow' })

if ($StatusOnly) { exit 0 }

# ---------------------------------------------------------------- 安装
Step '安装 APK'
if (-not (Test-Path $Apk)) { Fail "找不到 APK：$Apk`n请先运行 build.ps1" }
$install = & $Adb install -r $Apk 2>&1
$install | ForEach-Object { Write-Host "  $_" }
if ($install -notmatch 'Success') {
    Fail @"
安装失败。常见原因：
  - INSTALL_FAILED_USER_RESTRICTED：开发者选项里关掉「USB 安装」限制，或打开「允许通过 USB 安装应用」
  - INSTALL_FAILED_UPDATE_INCOMPATIBLE：手机上已装过不同签名的同名应用，先卸载再装
"@
}

# ---------------------------------------------------------------- 开启无障碍
Step '开启无障碍服务'
# 关键：读出已有列表再追加，绝不直接覆盖 —— 覆盖会把手机上其它无障碍服务（例如 TalkBack）一并关掉
$newList = @($before | Where-Object { $_ -ne $Component }) + $Component
$joined = ($newList -join ':')
& $Adb shell settings put secure enabled_accessibility_services $joined | Out-Null
& $Adb shell settings put secure accessibility_enabled 1 | Out-Null
Write-Host "已写入服务列表：$joined"

Step '校验结果'
$after = Get-EnabledServices
$ok = ($after -contains $Component)
$enabledFlag = ((& $Adb shell settings get secure accessibility_enabled) -join '').Trim()
Write-Host ("  accessibility_enabled = $enabledFlag")
$after | ForEach-Object { Write-Host "  - $_" }

if ($ok -and $enabledFlag -eq '1') {
    Write-Host "`n无障碍服务已开启。" -ForegroundColor Green
} else {
    Write-Host "`n写入后校验未通过。多数情况是 ColorOS 拦截了写入，请改用手动开启：`n" -ForegroundColor Yellow
    Write-Host '  设置 → 其他设置 → 无障碍 → 已安装的服务 → 广告跳过 → 开启「使用服务」'
}

# ---------------------------------------------------------------- 收尾提示
Step '接下来手机会自己失效？先关掉这两个开关'
Write-Host @'
ColorOS 有两个会把无障碍服务悄悄关掉的功能，务必处理：

  1. 「权限自动回收」—— ColorOS 会在一段时间后自动撤销长期未"使用"的权限，
      无障碍权限也会被回收，表现为"用着用着就不跳广告了"。
      路径：手机管家 → 权限隐私 → 权限自动回收 → 关闭
      （或在 设置 顶部搜索框直接搜「自动回收」）

  2. 后台被清理 —— 让本应用允许后台活动：
      在 设置 顶部搜索框搜「自启动」，把「广告跳过」打开；
      再搜「耗电管理」或「电池优化」，把「广告跳过」设为「允许后台活动 / 不优化」
'@

if ($Logcat) {
    Step '实时日志（Ctrl+C 退出）'
    Write-Host '现在去手机上打开一个有开屏广告的应用，这里会打印判定过程。'
    & $Adb logcat -s AdSkip:V
} else {
    Write-Host "`n想看实时判定日志，直接运行：" -ForegroundColor Cyan
    Write-Host "  & '$Adb' logcat -s AdSkip:V"
}

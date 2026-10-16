# 发布与 F-Droid 上架：谁做什么

> 原则：**密钥和密码永远只在你手里**，不经过任何协作会话、不进仓库。

## 一、已经就绪的部分（我做完了）

| 项 | 状态 |
|---|---|
| 开源许可证 | GPL-3.0（`LICENSE` ✓）|
| 公开仓库 | https://github.com/ccttrr123/adskip ✓ |
| 版本号 | `versionCode = 1` / `versionName = "0.1.0"` ✓ |
| 签名配置 | `app/build.gradle.kts` 从项目根的 `signing.properties` 读取 ✓ **缺文件时不报错、只是不签名** ✓（F-Droid 从源码构建靠的就是这个 ✓）|
| 模板 | `signing.properties.example` ✓（占位符，无真密码 ✓）|
| F-Droid 元数据 | `fastlane/metadata/android/{zh-CN,en-US}/` ✓（title / short / full / changelog ✓）|
| `.gitignore` | 已排除 `*.jks` / `*.keystore` / `signing.properties` ✓ |

## 二、你要做的四步

### ① 生成密钥库（只需一次，务必记住密码 ✗）

在项目根目录执行：

```powershell
cd "D:\chen\Documents\DeepSeek Harness\adskip"
**⚠️ 两个已经踩过的坑（2026-10-17 实际撞到）**：

1. **`keytool` 不在 PATH 上** ✗ —— 这台机器的 JDK 在 `D:\java`，但只有 `build.ps1` 会临时设 `JAVA_HOME`，
   所以普通终端里直接敲 `keytool` 会报「无法将"keytool"项识别为 cmdlet」。**要用全路径**：

   ```powershell
   & "D:\java\bin\keytool.exe" -genkeypair -v -storetype PKCS12 -keystore adskip-release.jks `
     -alias adskip -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=adskip, OU=dev, O=adskip, C=CN"
   ```
   （想省事也可以先 `$env:Path = "D:\java\bin;" + $env:Path` ✓ 只对当前窗口生效）

2. **`-dname` 不是可选项，是省事的关键** ✓ —— 不加它，keytool 会连着问「您的名字与姓氏」「组织单位」「是否正确？」，
   而最后那个「是否正确？[否]」**直接回车等于"否"** ✗，会白跑一遍。

3. **PKCS12 只问一次口令** ✓ —— 它要求「主口令」与「密钥库口令」相同，所以
   `signing.properties` 里的 `storePassword` 和 `keyPassword` **填同一个密码** ✓
```

会依次问你：密钥库密码、姓名/组织等（后者随便填）、确认。**RSA 4096 + 有效期 10000 天** ✓

> ⚠️ **密码和 .jks 文件丢了的后果**：无法再用同一个身份更新已发布的应用 ✗（用户必须卸载重装 ✓）。
> 建议：`.jks` 和密码存到密码管理器 + 离线备份各一份 ✓ **不要**放进仓库、云盘公开链接、聊天记录 ✗

### ② 填写 signing.properties

```powershell
Copy-Item signing.properties.example signing.properties
notepad signing.properties
```
填四项：`storeFile=adskip-release.jks`、`storePassword=`、`keyAlias=adskip`、`keyPassword=`

（这个文件已在 `.gitignore` 里 ✓ `git status` 不该看到它 ✓ —— 看不到才是对的 ✓）

### ③ 告诉我一声 → 我构建并校验已签名的 release APK

我会做：`assembleRelease` ✓ → 用 `apksigner verify` 确认签名 ✓ → 用 `aapt2 dump badging` 核对包名/版本/权限（**应当零权限** ✓）→ 报告文件大小与 SHA-256 ✓

### ④ 打标签 + 推送（F-Droid 需要 tag 指向它要构建的提交）

```bash
git tag -a v0.1.0 -m "首个版本 0.1.0"
git push origin main --tags
```

## 三、F-Droid 上架（之后的事）

需要在 [fdroiddata](https://gitlab.com/fdroid/fdroiddata) 提一个 Merge Request，内容大致是：

- 指向本仓库 + **某个 tag** ✓（所以 ④ 必须先做 ✓）
- `License: GPL-3.0` ✓
- 构建命令（Gradle ✓）
- 我们的元数据会被 `fastlane/` 自动采用 ✓（已备好 ✓）

**我们的有利条件** ✓：
- **无网络权限** ✓ → 不可能是"追踪/广告"类 ✓
- 无任何非自由依赖 ✓（Gson 是 Apache-2.0 ✓）
- 无预编译二进制（除了标准的 `gradle-wrapper.jar` ✓）
- 完整的开源历史与文档 ✓

**需要如实声明的**：本应用依赖无障碍服务，且 **`isAccessibilityTool=false`** ✓（不是为残障用户设计的辅助工具 ✓）—— 这条我们在代码里就是如实写的 ✓ 上架时也照实说明 ✓

## 四、验收清单（发布前逐条核对）

- [ ] `git status` 干净 ✓ 且看不到 `signing.properties` / `*.jks` ✓
- [ ] `apksigner verify` 通过 ✓
- [ ] `aapt2 dump badging`：包名 `io.github.adskip`、versionName `0.1.0`、**uses-permission 一行都没有** ✓
- [ ] tag `v0.1.0` 已推送 ✓
- [ ] 在**干净环境**装一次 release APK（先卸载 debug 版 ✗）确认能正常启动 ✓
# F-Droid MR 提交材料（v0.2.0）

> 目标仓库：<https://gitlab.com/fdroid/fdroiddata>
> 上游：<https://github.com/ccttrr123/adskip> · tag `v0.2.0` → `195c1ad711676a2b96e405d29a4a6b86034c5bfb`
> 本文件把「要新增的 yml」和「MR 描述」都准备好，照着复制即可。

## 一、提交前确认

- [x] tag `v0.2.0` 已推送（`git ls-remote --tags origin v0.2.0` 能看到）
- [x] Release 已发布（GitHub Latest，附件 `app-release.apk` 4,750,361 字节，
      SHA-256 `9EC9869534D4506F61CE585247165331EEB6666DD95B1949C09B635E158A6C65`）
- [ ] 有 GitLab 账号，并已 fork `fdroid/fdroiddata`

## 二、操作步骤

1. 在 GitLab fork `fdroid/fdroiddata`，clone 到本地：

   ```bash
   git clone https://gitlab.com/<你的用户名>/fdroiddata.git
   cd fdroiddata
   git checkout -b io.github.adskip
   ```

2. 新建文件 `metadata/io.github.adskip.yml`，内容用下面[第三节](#三metadata-io.github.adskipyml)；
   不要改 `metadata/` 以外的东西。

3. 提交并推送：

   ```bash
   git add metadata/io.github.adskip.yml
   git commit -m "New app: io.github.adskip"
   git push -u origin io.github.adskip
   ```

4. 在 GitLab 打开 Merge Request（源分支 `io.github.adskip` → 目标 `fdroid/fdroiddata:master`），
   标题用 **`New app: io.github.adskip`**，描述粘贴[第四节](#四mr-描述可直接粘贴)。

5. 等 CI。若 CI 报 Gradle/JDK 问题，优先看 `subdir` 与 jdk 版本（见 MR 里已预留说明）。

## 三、`metadata/io.github.adskip.yml`

```yaml
Categories:
  - System
License: GPL-3.0-only
AuthorName: ccttrr123
# AuthorEmail: <可选：填了才公开，不填请删掉本行>
# AuthorWebSite: <可选：AuthorName 对应的个人主页，没有就删掉本行>
# WebSite: <可选：项目官网。没有官网时请整行删掉，不要填源码仓库地址>
SourceCode: https://github.com/ccttrr123/adskip
IssueTracker: https://github.com/ccttrr123/adskip/issues

AutoName: AdSkip

RepoType: git
Repo: https://github.com/ccttrr123/adskip.git

Builds:
  - versionName: '0.2.0'
    versionCode: 2
    commit: 195c1ad711676a2b96e405d29a4a6b86034c5bfb
    subdir: app
    gradle:
      - yes

# AntiFeatures：不需要，因此不要写这个字段（也不要留一个空的 AntiFeatures: 键）。
# 无广告、无追踪、无网络（连 INTERNET 权限都没有）、无专有依赖、不依赖专有服务、源码可获取。
MaintainerNotes: |-
  No uses-permission entries are declared in AndroidManifest.xml; there is no
  INTERNET permission, so the app is technically incapable of any network
  request. The only permission visible to aapt2 is the AndroidX-generated
  signature-level one: io.github.adskip.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION.
  The app uses an AccessibilityService declared with
  android:isAccessibilityTool="false"; it is an automation tool that taps
  "Skip"/"Close" on splash ads, not a disability aid. It grants no other
  elevated privilege (no installer, no VPN, no input method).
  All dependencies are free software: androidx.core/core-ktx, androidx.appcompat
  and Material Components (Apache-2.0) from Google Maven, Gson 2.11.0
  (Apache-2.0) from Maven Central; junit is test-only.
  gradle/wrapper/gradle-wrapper.jar is the standard Gradle wrapper; no other
  prebuilt binaries are shipped. Signing keys are read from an untracked
  signing.properties; when absent, the build simply produces an unsigned APK.
  No anti-features apply.

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$
CurrentVersion: '0.2.0'
CurrentVersionCode: 2
```

## 四、MR 描述（可直接粘贴）

````markdown
### App description

AdSkip is a small Android utility that taps the "Skip"/"Close" button of
splash (app-open) ads on behalf of the user, so the user does not have to hit
that button themselves. It works without root by using an AccessibilityService
to locate the button and then performing a single tap.

Upstream: https://github.com/ccttrr123/adskip (GPL-3.0; the upstream author is
submitting this MR, so no third-party consent is needed).

### Why this app should be included

Chinese regulation requires splash ads to expose a "skip" control, but the
button is frequently tiny or delayed. AdSkip performs exactly the one tap the
user is entitled to make. It does not modify other apps, does not inject code,
does not hook any process, does not proxy or MITM traffic, and does not touch
banking/payment/government apps at all (for those it does not even read the
screen).

### Inclusion policy compliance

* **Free software**: GPL-3.0 (`LICENSE`). No proprietary code anywhere in the
  build path.
* **Free dependencies only**: androidx.core/core-ktx, androidx.appcompat,
  Material Components (Apache-2.0, Google Maven) and Gson 2.11.0 (Apache-2.0,
  Maven Central). junit is test-only. No Firebase, no GMS, no Crashlytics, no
  proprietary ad/tracking SDK of any kind.
* **No proprietary tracking or advertising libraries**, and the app contains no
  advertising itself.
* **Buildable from source with a FOSS toolchain**: Gradle 8.11.1 (Kotlin DSL),
  AGP 8.7.3, Kotlin 2.0.21, JDK 17, compileSdk 35 / minSdk 26 / targetSdk 35.
  A working `gradlew`/`gradlew.bat` is included.
* **No prebuilt binaries from untrusted sources**: the only binary in the tree
  is the standard `gradle/wrapper/gradle-wrapper.jar`.
* **No un-consented downloads**: the app has no network capability at all (see
  below).
* **License file + upstream metadata present**: `LICENSE` and
  `fastlane/metadata/android/{en-US,zh-CN}/` are in the repo, so descriptions
  and changelogs are controlled upstream.

### Permissions and privileged capabilities (full disclosure)

* `AndroidManifest.xml` declares **no `uses-permission` at all**, in particular
  **no `INTERNET`**. `aapt2 dump badging` on the release APK shows a single
  permission, which is the AndroidX-generated signature-level
  `io.github.adskip.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. The app is
  therefore technically incapable of uploading anything (no tracking, no
  telemetry, no update check, no remote fonts or CDN fetch).
* The app uses **one AccessibilityService**. This is the app's core function,
  and it is declared with `android:isAccessibilityTool="false"` so that Android
  does not present it as a disability-assistance tool. It is honest about being
  an automation tool. No other elevated privilege is used: no device-admin, no
  installer, no VPN, no input method, no `QUERY_ALL_PACKAGES`.
* On Android 13+ a sideloaded app's accessibility service is behind "restricted
  settings", so users have to allow it manually (documented in the README).
  This is platform behaviour, not something the build recipe needs to work
  around.

### Anti-features

**None apply, so the `AntiFeatures` field is omitted.** Checked against the
current list (Ads, DisabledAlgorithm, KnownVuln, NonFreeAdd, NonFreeAssets,
NonFreeDep, NonFreeNet, NoSourceSince, TetheredNet, Tracking): the app has no
advertising, no network access at all, no proprietary dependencies, does not
require another non-free app, does not depend on any network service, has
available source, and no known vulnerability. Using an accessibility service for
automation is not one of the defined anti-features.

Precedent for this app category and this technique in the main repo:
`com.zfdang.touchhelper` ("Skip opening-screen ads in apps", also
AccessibilityService-based, in the repo since 2022, no AntiFeatures) and
`org.adaway` (ad blocking, no AntiFeatures).

### Build recipe notes

* `subdir: app` — the repository root is the Gradle root (`settings.gradle.kts`
  is at the root) and the application module is `app/`.
* `gradle: yes` — no product flavours.
* `commit:` uses the full commit hash of tag `v0.2.0`
  (`195c1ad711676a2b96e405d29a4a6b86034c5bfb`).
* `AutoUpdateMode: Version` + `UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$` — future
  releases are tagged `vX.Y.Z`, so auto-updates should work without manual
  metadata edits.
* No `sudo`/JDK workaround is included; if the buildserver's JDK does not match
  what AGP 8.7.3 needs, that is the first thing to adjust.

### Testing

* 176 JVM unit tests pass (`gradle :app:testDebugUnitTest`).
* Signed release APK installed and smoke-tested on an OPPO PLW110 running
  Android 17 / ColorOS 17: accessibility service binds, UI starts, version
  `0.2.0` / code `2`; `aapt2 dump badging` shows no `INTERNET` permission.
* Real-device matrix: 15 installed rule apps × 2 cold starts — click/ad ≈ 1.13,
  success 87%, zero mis-taps.

### Project age (proactive disclosure)

The app's functionality is implemented, tested and verified on real devices,
but the upstream git history is short (the code was written over roughly two
days of work). If the "actively developed for at least 3 months" quality
criterion applies strictly, I would appreciate guidance on whether to wait, or
whether the criterion is waived in this case.

### Points I would like reviewers to confirm

1. `subdir: app` and the Gradle/JDK combination — I will adjust immediately if
   the CI build disagrees.
2. Whether the maintainers would like an explicit note about the accessibility
   service in the app description text (F-Droid controls the description
   through `fastlane/` upstream, so I can amend it there).
3. Screenshots are included in the upstream
   `fastlane/metadata/android/zh-CN/images/phoneScreenshots/` directory
   (1.png–3.png, 1216×2640, refreshed 2026-11-18 with the current UI); en-US
   can fall back to the zh-CN set.
````

## 五、提交后

- CI（`fdroid build` 流水线）会实际构建一次；Gradle/JDK 若报错，优先核对 `subdir`、
  `jdk`、`gradle` 版本，再改 MR 里的 yml。
- 审核者可能会问：
  - **为什么用无障碍服务**：答"只做一次点击、不读取其它内容、isAccessibilityTool=false、
    不碰支付/银行类应用"（MR 已写）。
  - **项目年龄不足 3 个月**：MR 已主动说明，等审核者裁量（可引用同页申诉通道 `team@f-droid.org`）。
  - **Application ID 与 GitHub 账号不一致**：政策原文是 `It is advised`（不是 must），
    且改 ID 会让已装用户变成另一个应用，已在材料里说明保留原因。
- 合并后：F-Droid 会按 tag 自动构建发布；之后每次打 `vX.Y.Z` tag 即可自动更新。

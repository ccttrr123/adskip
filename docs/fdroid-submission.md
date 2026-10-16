# F-Droid 提交材料（查证完成，可直接使用）

> 这份文档由协作会话于 2026-10-17 查证并整理，所有政策条款都附了官方 URL 与英文原句。
> 提交前请先读完「一、需要你决定的三件事」。

## 一、需要你决定的三件事

| # | 事项 | 现状 / 建议 |
|---|---|---|
| 1 | **Application ID 改不改** | 政策原文用的是 **`It is advised to`**（建议）而不是 `must` —— 同页"唯一性"那条才用 `must`。所以 `io.github.adskip` 与账号 `ccttrr123` 不一致只是**建议性偏差**，不构成自动拒绝。**建议保持不变**（改 ID 会让已安装的应用变成"另一个应用"，统计与无障碍设置都要重来）。 |
| 2 | **AuthorName** | 下面 yml 里暂填 `ccttrr123`（GitHub handle，不含个人信息）。想换就改这一行。 |
| 3 | **截图** | ✅ **已备齐**（2026-10-17 03:50 补，见提交 `6990547`）。`fastlane/metadata/android/zh-CN/images/phoneScreenshots/` 下 1~3.png，各 1216×2640（主界面 / 统计 / 深色模式），**就在本仓库内** —— 位置正合政策要求（必须在应用自己的仓库，**不能**放进 fdroiddata）。<br>⚠️ 只做了 **zh-CN** 一套；政策不要求每个 locale 都有图，`en-US` 缺图时回退用 zh-CN 那套 ✓。想给 en-US 单独一套再补即可。 |

另外**提交前必须做的一步**：把 yml 里的 `commit:` 占位符换成真实哈希 —— `git rev-list -n1 v0.1.0`。

## 二、`metadata/io.github.adskip.yml` 全文

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
  - versionName: '0.1.0'
    versionCode: 1
    commit: <TAG_V0.1.0_的提交哈希>
    subdir: app
    gradle:
      - yes

# AntiFeatures：不需要，因此**不要**写这个字段（也不要留一个空的 AntiFeatures: 键）。
# 现有负面特征没有一个适用于本应用：无广告、无追踪、无网络（连 INTERNET 权限都没有）、
# 无专有依赖、不需要其他非自由应用、不依赖专有网络服务、源码可获取、
# 不是签名算法问题、无已知漏洞、不绑定特定服务实例。
# 无障碍服务的用途不构成任何负面特征（先例 com.zfdang.touchhelper 同样未标注）。

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
CurrentVersion: '0.1.0'
CurrentVersionCode: 1
```

**`subdir: app` 是必需的**，不能省：fdroiddata 官方 gradle 模板写着 "The `subdir` is the parent dir of `src/main` which is generally `app`"
（<https://gitlab.com/fdroid/fdroiddata/-/blob/master/templates/build-gradle.yml>），
且 AdAway / NewPipe / Termux 这类"仓库根=Gradle 根、模块叫 app/"的项目**全都写了 `subdir: app`**。

## 三、英文 MR 说明全文（可直接粘贴）

**MR 标题**：`New App: io.github.adskip`

````markdown
### App description

AdSkip is a small Android utility that taps the "Skip"/"Close" button of
splash (app-open) ads on behalf of the user, so the user does not have to hit
that button themselves. It works without root by using an AccessibilityService
to locate the button and then performing a single tap.

Upstream: https://github.com/ccttrr123/adskip (GPL-3.0, the upstream author is
submitting this MR, so no third-party consent is needed).

### Why this app

Chinese regulation requires splash ads to expose a "skip" control, but the
button is frequently tiny or delayed. AdSkip performs exactly the one tap the
user is entitled to make. It does not modify other apps, does not inject code,
does not hook any process, does not proxy or MITM traffic, and does not touch
banking/payment/government apps at all (for those it does not even read the
screen).

### Inclusion policy compliance

* **Free software**: GPL-3.0 (`LICENSE`). No proprietary code anywhere in the
  build path.
* **Free dependencies only**: androidx.core-ktx, androidx.appcompat, Material
  Components (Apache-2.0, Google Maven) and Gson 2.11.0 (Apache-2.0, Maven
  Central). junit is test-only. No Firebase, no GMS, no Crashlytics, no
  proprietary ad/tracking SDK of any kind.
* **No proprietary tracking or advertising libraries**, and the app contains no
  advertising itself.
* **Buildable from source with a FOSS toolchain**: Gradle (Kotlin DSL), AGP
  8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 26 / targetSdk 35, JDK 17. There
  is a working `gradlew`/`gradlew.bat`.
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
  telemetry, no update check, no fonts/CDN fetch).
* The app uses **one AccessibilityService**. This is the app's core function,
  and it is declared with `android:isAccessibilityTool="false"` so that Android
  does not present it as a disability-assistance tool. It is honest about being
  an automation tool. No other elevated privilege is used: no device-admin, no
  installer, no VPN, no input method, no `QUERY_ALL_PACKAGES`.
* On Android 13+ a sideloaded app's accessibility service is behind "restricted
  settings", so users have to allow it manually (documented in the README).
  This is a platform behaviour, not something the build recipe needs to work
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
* `commit:` uses the full commit hash of tag `v0.1.0`.
* `AutoUpdateMode: Version` + `UpdateCheckMode: Tags ^v\d+\.\d+\.\d+$` — future
  releases are tagged `vX.Y.Z`, so auto-updates should work without manual
  metadata edits.
* No `sudo`/JDK workaround is included; if the buildserver's JDK does not match
  what AGP 8.7.3 needs, that is the first thing to adjust.

### Points I would like reviewers to confirm

1. `subdir: app` and the Gradle/JDK combination — I will adjust immediately if
   the CI build disagrees.
2. Whether the maintainers would like an explicit note about the accessibility
   service in the app description text (F-Droid controls the description
   through `fastlane/` upstream, so I can amend it there).
3. Screenshots are not yet in the upstream `fastlane/.../images/` directory; I
   will add them upstream (not in this repo) in the next release.

<!-- 可选：关于"活跃开发 3 个月"门槛的主动说明。要用就取消注释并粘进 MR（见第四节）：
The app's functionality is implemented, tested (135 unit tests) and verified on 15 real apps. The upstream git history is short — the code was written over roughly two days of work — so if the "actively developed for at least 3 months" criterion applies strictly, I would appreciate guidance on whether to wait, or whether the criterion is waived in this case.
-->
````

## 四、政策原文与 URL（逐字查证）

### 4.1 活跃开发 3 个月（Quality Control 第 6 条）

```markdown
1. The application should have been actively developed for at least 3 months, unless:
    1. The application has been proven to have some users.
    1. The author has been maintaining other apps on F-Droid.
    1. The application is developed only for an event.
```

- 逐字原文：<https://gitlab.com/fdroid/fdroid-website/-/raw/master/_docs/Inclusion_Policy.md>
- 渲染页：<https://f-droid.org/en/docs/Inclusion_Policy/>（锚点 `#quality-control`）

**注意措辞**：它衡量的是**项目的开发活跃跨度**，不是 git 历史的形状或 commit 数量。我们代码实际写了约 2 天、仓库今晚才建立 —— 与 3 个月的差距需要在 MR 里**主动说明**，由审核者裁量（同页明确写了裁量权与申诉通道 `team@f-droid.org`）。

**没有公开先例可查**：F-Droid 不公开逐应用的拒绝裁定；"仓库年轻但被接受/被拒"的记录在官方文档、fdroiddata/rfp 的 issue 与 MR、内部审核清单里**都查不到**。所以唯一可靠的判断方式是提交后看审核者反馈。

### 4.2 Application ID（Build & Verification Workflow）

```markdown
* __Unique ID:__ All applications must have their own distinct Android "Application ID".
* __Your domain:__ It is advised to use an Application ID that stems from a domain name owned by the developer.
```

- 逐字原文与渲染页同 4.1（锚点 `#build--verification-workflow`）

**advised 还是 mandatory**：**advised（建议）**。判据是同两行的用词差异 —— 唯一性用 `must`，域名对应用 `It is advised to`。
唯一会**自动拒绝**的 ID 条款是关于"与其他域名重名/侵犯第三方权利"的，与"命名空间与账号不符"无关。

### 4.3 一个准确性提醒

搜索结果里常见的 *"Inclusion How-To"* 页面**已从官方文档移除**（`https://fdroid.gitlab.io/fdroid-website/en/docs/Inclusion_How-To/` 返回 404；`_docs/` 目录下 44 个文件里没有它）。写材料时**不要引用它**。

## 五、其他已核对的事实

- fastlane 元数据两个 locale 均已齐备（title / short_description / full_description / changelogs/1）✓
  - short description 不以句点结尾 ✓（官方 Quick Start 要求）
- 仓库里唯一的预编译二进制是 `gradle/wrapper/gradle-wrapper.jar` ✓（标准 wrapper，AdAway/NewPipe/Termux 都没为它写 scanignore，无需特殊处理，但已在 MaintainerNotes 里说明）
- `build.gradle.kts`（Kotlin DSL）受 fdroidserver 支持 ✓（`checkupdates.py` 的 `dirs_with_manifest()` 把它列为 manifest 文件），所以 `UpdateCheckMode: Tags` 能正确读出 `app/build.gradle.kts` 里的版本号
- AGP 8.7.3 无已知 buildserver 问题 ✓，但**必须实测** `fdroid build -v -l io.github.adskip` 或走 fdroiddata 的 CI pipeline（gradle/JDK 匹配只有实测能确认）
- `signing.properties` 缺失时只是不签名、不报错 ✓ —— F-Droid 从源码构建、用自己的签名，这点项目里已经做对了

## 六、诚实结论

技术上**已经准备就绪** ✓（元数据齐备、依赖全自由、零网络权限、有同类先例 ✓）。
**唯一实质障碍是第四节 4.1 那条 3 个月门槛** ✗ —— 而它只能靠**时间**或**审核者裁量**解决 ✓。
建议：继续在 GitHub 上开发与发版 ✓ 等历史自然变长再提交 ✓（或现在就提交，但**主动**说明开发时长 ✓ 不要等审核者从 git 历史里发现 ✗）。
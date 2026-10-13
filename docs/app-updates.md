# 应用内更新与发布

手机从公开版本配置发现新版，从启动提醒或「我的 → 微信读书 → 应用与同步更新 → 查看新版应用」进入更新流程，查看版本、更新说明和安装包大小，下载后点击「安装新版」。首次安装更新时，按提示允许拾句安装应用，返回后由 Android 确认安装。首次需要手动覆盖安装带此功能的 1.2.0，以后使用应用内更新。

启动/进入更新页自动检查，联网检查间隔为 6 小时；手动检查不受间隔限制。同一版本的启动提醒每天最多一次，可以稍后再说。应用版本检测成功不依赖微信读书官方规范文件是否可访问。

## 签名与旧版过渡

本项目现有手机安装包来自此电脑的调试签名。为保留密钥、收藏与笔记，先固定原签名；release 构建关闭调试，但沿用原证书。初始化一次：

```powershell
.\scripts\initialize-signing.ps1
```

私有文件是 `.signing/wenotes.keystore` 和 `signing.properties`，均已忽略，不提交 Git。请一起保存在私人备份中，另一台电脑开发时还原这两份文件。debug 与 release 都使用固定签名，发布脚本校验原证书指纹，App 也会检查新 APK 与当前安装签名一致。丢失原签名时不要生成替代签名后覆盖发布。

这一步保留的是历史调试证书，适合当前个人分发。正式公开发行前若要换为专用发行证书，应另做签名和数据迁移设计，不能直接换签名覆盖当前安装。

## 本地准备安装包

版本由根目录 `version.properties` 管理。编辑 `release-notes.md` 填写更新说明。准备当前 1.2.0（不提交、不推送、不发布）：

```powershell
.\scripts\publish-app.ps1 -UseCurrentVersion
```

输出在 `output/releases/v1.2.0/`，包括 `WeNotes-1.2.0.apk`、`compatibility.json` 与更新说明。默认会运行单元测试、lint 和 release 构建，并检查 APK 包名、版本、SHA-256 与固定签名。普通 `gradlew.bat assembleDebug` 也可构建，但给手机日常升级推荐使用经过检查的 release 包。

## 一键发布下一版

先把功能改动与更新说明提交到 `main`，保持工作区干净，并确认使用的 GitHub 登录有仓库写入权限。执行：

```powershell
.\scripts\publish-app.ps1 -Publish
```

脚本默认递增 `versionCode` 和补丁版本，例如 1.2.0 → 1.2.1。指定版本用 `-VersionName 1.3.0`；发布已经准备/提交的当前版本用 `-UseCurrentVersion -Publish`。

`-Publish` 会构建验证、提交版本文件、推送 `main`、创建 GitHub Release 草稿、上传 APK 与版本配置，然后公开 Release；最后才通过 Contents API 更新线上 `updates/compatibility.json` 并快进拉取。不会先发布没有可下载 APK 的新版提示。构建时生成单独的内置配置，使用 APK 自己的版本，移除远端安装包校验信息，避免 APK 包含自己的哈希。

脚本使用已有 GitHub credential helper 登录，令牌不会写入文件。APK、发布输出、签名与本机配置均不提交。版本配置只包含公开信息。

上传失败时 Release 保持草稿，可以检查后用当前版本重试。若 Release 已公开但最后的版本公告失败，用保存的配置重试公告，不重新构建或覆盖该 Release：

```powershell
.\scripts\publish-compatibility.ps1 -ConfigPath .\output\releases\v1.2.0\compatibility.json -Publish
git pull --ff-only origin main
```

## 下载与验证边界

- WorkManager 保存下载任务，等待网络、离开页面与进程重新启动后可继续调度；网络失败自动重试两次，之后可手动重新下载。
- 中断的下载从头重试，第一版没有断点续传。已完成的安装包保存在 App 私有目录，可重新打开页面继续安装。取消会停止下载，不影响阅读数据。
- 元数据限制安装包为本仓库 HTTPS Release 直链，重定向只允许 GitHub HTTPS 资源域名。文件大小上限 150 MB。
- 下载必须满足公布的大小与 SHA-256；安装前再次检查哈希、包名、版本、当前签名和 APK 签名。系统安装器最终验证签名并确认覆盖升级。
- 旧配置没有 APK 信息时仍能检测版本，提供原有 Releases 网页入口。接口兼容更新继续使用原配置格式。

验证命令：`gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease`。单元测试覆盖新旧版本配置、下载域名、完整性、截断/超长内容、取消下载和接口检查失败时的应用版本检测。

Android 原生验证：构建并安装 debug 与 `assembleDebugAndroidTest` 产物，运行 `adb shell am instrument -w com.wenotes.app.test/com.wenotes.app.AppUpdateInstrumentation`，检查实际安装证书、版本限制和 FileProvider 的目录隔离。可选的 `upgradeFixture=true` 使用 `app/src/androidTest/assets/upgrade-fixture.apk` 中同签名的更高版本 APK；加 `updateUi=true` 验证下载完成后的授权提示/系统安装入口，测试结束会恢复原更新状态，不安装测试新版。

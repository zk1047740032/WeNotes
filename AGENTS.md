# WeNotes 项目协作与发布约定

本文件适用于整个仓库。项目是原生 Java Android 应用「拾句」，包名 `com.wenotes.app`，源码远程为 `https://github.com/zk1047740032/WeNotes.git`，日常维护分支为 `main`。

## 用户指令的含义

- 用户说「发布」「发版」「发布新版」时，直接完成正式发布。该指令已经授权检查与修复、更新发布说明、提交本次功能改动、递增版本、构建验证、推送 `main`、创建并公开 GitHub Release、上传 APK、更新手机读取的线上版本公告；无需再逐项征求确认。
- 用户说「保存」「同步」「提交并推送」时，检查并提交当前相关改动，推送到 `origin/main`，确认远端已保存。这类指令不触发新版本发布。
- 用户限定「只保存本地」「只构建」「先说方案」「只建草稿」时，按该限定执行。当前用户的明确要求优先于这里的默认流程。
- 只实施用户明确要求的功能和界面改动。不要自行增加导航入口、页面、按钮或其他用户可见功能；确实需要扩大范围时，先说明并取得用户同意。实现已授权功能所需的内部代码细节可自行处理。

## 发布流程

1. 阅读 `docs/app-updates.md` 和发布脚本，检查工作区、当前分支和远程状态。先 `git fetch origin main`，有远端更新时保留本地改动并合并；禁止用重置或强制推送覆盖任一电脑的工作。
2. 检查本次实际改动，把用户可理解的更新内容写入 `release-notes.md`。完成相关验证后提交功能改动与说明，使发布脚本开始时工作区干净。不要把不相关改动悄悄丢弃或夹带进发布。
3. 确认 `signing.properties` 与 `.signing/wenotes.keystore` 存在，使用原固定签名。缺失时优先恢复私有备份；另一台电脑不能用新生成的调试签名替代现有发布签名。
4. 读取 `version.properties`、线上 `updates/compatibility.json` 和已有 Releases，选择尚未发布且版本码高于线上版本的版本。当前版本已准备、尚未发布时执行 `./scripts/publish-app.ps1 -UseCurrentVersion -Publish`；正常下一版执行 `./scripts/publish-app.ps1 -Publish`，脚本默认递增补丁版本和版本码；用户指定版本时使用 `-VersionName`。不要对同一次发布重复递增版本。
   已交付手机安装的 APK 也视为已分发版本，即使没有 GitHub Release 或线上公告，也不能沿用其版本号和版本码发布新改动。常规发版必须递增；`-UseCurrentVersion` 只用于明确尚未分发的预备版本或同一次中断发布的恢复，不能仅凭 GitHub 没有 Release 判断版本可复用。
5. 发布脚本会执行 `testDebugUnitTest lintDebug assembleRelease`，校验 APK 包名、版本、大小、SHA-256 和固定签名，提交版本变更、推送 `main`、上传 Release 草稿附件、公开 Release，然后更新线上版本配置并快进拉取。必须先让 APK 可下载，再向手机公告新版。
6. 发布后实际核对公开 Release、APK 下载地址、线上版本配置的版本码/哈希/大小，以及 Git 工作区和远端提交。报告最终版本、Release 链接、验证结果与本机 APK 路径，只有核对完成才称为发布成功。

## 中断与恢复

- 构建、签名或测试失败时，先修复并重新验证，通过后继续原发布。
- 上传中断且 Release 仍是草稿时，检查草稿和源代码提交，用当前版本恢复；不删除已公开版本，不重新生成签名。
- Release 已公开但版本公告失败时，使用该版本保存的 `output/releases/vX.Y.Z/compatibility.json` 执行 `./scripts/publish-compatibility.ps1 -ConfigPath <配置路径> -Publish`，再拉取 `main`；不要重新构建一个不同哈希的 APK 冒充已上传文件。
- 网络或登录异常时，继续完成可做的本地工作并进行合理重试。仍无法同步时，保留提交和产物，明确报告失败阶段和恢复方式，不能把本地保存说成远端保存。

## 开发与私有文件

- 版本集中在根目录 `version.properties`；内置兼容配置由 Gradle 生成，不要手工把 APK 自身哈希塞入其内置配置。
- 使用项目 Gradle wrapper、JDK 17 或 21 和本机 Android SDK。需要定位 JDK 时优先复用 `scripts/release-common.ps1` 中的 `Find-WeNotesJava`，不要把另一台电脑的路径写入仓库。
- Android 代码改动运行适当的验证；涉及更新/发布流程时至少验证单元测试、lint 和签名 release 构建。纯说明文字改动不必重复完整构建。
- 不提交 APK、`output/`、构建目录、`local.properties`、`signing.properties`、`.signing/`、keystore、访问令牌、微信读书/AI 密钥和个人笔记。签名只通过私人备份在两台电脑间恢复。
- 更新机制要兼容旧版本配置，保留手机密钥、收藏与阅读缓存；版本检测和 APK 下载只请求公开信息，不携带账号密钥。

用户授权记录：2026-10-13 明确要求将以上规则保存在项目中，今后说「发布」即可执行完整流程。

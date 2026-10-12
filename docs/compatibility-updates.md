# 拾句的兼容配置更新

App 启动、进入「我的」或开始同步时检查更新，自动检查间隔为 6 小时。「我的 → 微信读书 → 应用与同步更新 → 检查更新」可立即重查。检查下载公开元数据，不发送微信读书或 AI 密钥。

配置的固定地址是：

`https://raw.githubusercontent.com/zk1047740032/WeNotes/main/updates/compatibility.json`

App 从微信读书官方 `Tencent/WeChatReading/main/skills/SKILL.md` 的元数据读取最新规范版本，只用它检查是否有待适配更新。版本号本身不会批准新版本。

优先读取 GitHub 原始文件直链，直链不可用时改用同一仓库的 GitHub Contents API 读取原文；两个通道都只获取公开文件，不携带账号密钥。每次检查绕过旧的文件缓存，避免刚发布的配置仍读到旧内容。

## 配置约定

- `schema` 固定为 1，保留现有格式以便旧 App 继续读取。
- `revision` 每次发布实际改动时递增；旧 revision 不会替换本机已保存的配置。
- `profiles[].skillVersion` 是已核对相关官方文档、并通过测试的接口版本。
- `adapter` 必须是当前 APK 已实现的适配器；目前为 `weread-library-v1`。
- `minAppVersionCode` 指定能使用此适配的最低 APK 版本。
- `pageSize` 为 1–100，只限制笔记本与个人想法的分页大小；不会更改字段解释或统计口径。
- `app` 提供新版应用的版本及更新地址，地址限制在本项目的 GitHub Releases 中。新版 APK 发布后，再更新对应应用版本信息。

配置会随 APK 打包作为初始值。下载失败、非法配置、未知适配器或较高最低 App 版本，都不会替换可用的适配配置。收到服务端升级通知后，重新检查一次；只有取得适用的、已批准的新版本才重试请求，否则停止本次同步、保留已有阅读缓存并提示检查更新。

## 维护步骤

1. 阅读最新官方 `SKILL.md` 及 App 使用的 `shelf.md`、`notes.md`，比较接口、参数、返回字段、分页和数据含义。
2. 若现有适配器可兼容，增加 profile 并递增 revision。新增字段或 AI 使用说明变化不一定需要修改 App。
3. 若需要新代码，先更新适配器和 APK 的 versionCode，并发布对应新版 APK；配置中为该接口版本指定新适配器和最低 App 版本。
4. 运行 `gradlew.bat testDebugUnitTest lintDebug assembleDebug`，验证配置选择、升级通知、完整分页、缓存保留和实际同步。
5. 用 `scripts/publish-compatibility.ps1` 检查配置；加 `-Publish` 时仅发布公开兼容配置这一份文件到 main，不提交或推送其他本地改动。脚本使用已有 GitHub 登录，不输出或保存访问令牌。

这套机制自动分发我们已验证的配置，不会自动执行下载的 Markdown，也不会自行改写或安装 App 代码。需要安装新 APK 时，「我的」中会显示「查看新版应用」入口。

## 首次验证

2026-10-12 已核对官方 1.0.5。相对本机原 1.0.4 文档，笔记与分页请求保持一致；书籍链接规范新增 readerLink 优先规则，App 目前不使用书籍跳转链接，因此现有书架、笔记和最近阅读处理保持兼容。

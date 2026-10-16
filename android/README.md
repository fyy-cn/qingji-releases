# 清记 · 安卓个人记账

原生 Kotlin / Jetpack Compose / Room 应用，Android 10及以上。默认人民币、北京时间和周一起始周。无需账号，账本保存在本机；1.3.0完善分类排序、内置分类删除，并公开完整应用源码，联网仅获取版本信息和安装包。

统一下载入口：https://fyy-cn.github.io/qingji-releases/ 。发布状态见验证报告；完整流程见 [更新说明](docs/Updates.md)。

## 使用

1. 从统一下载入口安装测试APK，打开“清记”。已有1.0.0请直接覆盖安装一次，不要卸载。新账本为空，不含演示消费。
2. 在首页或设置中开启通知记账。系统授权前会说明读取范围；也可直接手动记账和导入账单。
3. 从微信、支付宝主动导出个人对账文件，解压后在“设置 → 导入账单”选择 CSV / XLSX，检查预览，再确认导入。
4. 在“账单 → 待确认”补全金额、交易类型，或处理疑似重复。确定是同一笔时合并；连续两笔真实消费应分别保留。
5. 查看日、周、月、年分析。点击柱形、分类和商家进入对应明细。历史周期可用左右箭头切换。
6. 定期创建 `.qjb` 口令加密备份。恢复前先预览摘要；恢复会替换本机账本，口令无法找回。
7. 在设置 → 应用更新中允许新版通知提醒。打开应用自动检查，后台默认每天定期检查一次；发现新版后确认下载和安装。后台时间受系统限制。
8. 在记账时新增自定义分类，或在设置 → 管理消费分类中新增、重命名、上下排序或删除分类。账单页搜索/筛选后点击“导出CSV”；设置中可导出全部账单。CSV用于表格查看，完整恢复使用加密备份。

你使用的小米17 Pro Max，另见 [小米安装与验证](docs/Xiaomi.md)。

## 已实现

- 微信、支付宝支付候选通知解析；明确成功且金额完整的人民币交易自动入账，否则待确认。
- CSV（UTF-8 / BOM / GB18030）、XLSX（共享字符串、内联字符串、数值日期）导入预览；保留人工分类与备注。
- 手动补记、编辑、搜索，按渠道和确认状态筛选；商家分类规则优先于关键词。
- 自定义消费分类，支持新增、重命名、上下排序和删除；内置分类也可删除（保留未分类），可恢复内置分类。排序与删除状态保存在本机并进入加密备份。删除前确认影响，相关账单转为未分类，移除对应规则，账单与金额保留。旧账本通过非破坏性迁移升级。
- 导出全部账单或当前筛选结果为UTF-8 BOM CSV，包含金额、商家、分类、渠道、状态、交易号、备注和来源；转义引号/换行，保护长交易号精度和潜在公式文本。
- 渠道交易号、通知事件、文件行去重；相似记录交由用户确认，保留多笔同金额交易。
- 单独记录退款、部分退款与原交易关联；按退款发生日期抵减。收入、转账、充值、提现和还款不算消费。
- 日周月年净消费、支出、退款、日均、分类占比、商家排名、分类和商家变化；本期按相同日历进度对比。
- 本机 Room 存储；完整备份包括账单、商家规则、导入记录。AES-256-GCM 认证加密，PBKDF2-HMAC-SHA256 / 210000次迭代，随机盐与随机nonce。
- 真机信息、授权状态、最近候选通知时间和最近导入时间；小米设备上显示后台设置建议。
- 公开统一下载页、同源HTTPS版本检查、后台每日检查、每版本一次的新版通知、更新说明、APK下载进度与取消，大小/哈希/包名/版本/签名校验后调用系统安装器。

## 必须通过真机确认的边界

- 尚未连接你的小米手机，也未取得你的实际通知和账单。**通知覆盖率、当前微信/支付宝导出格式，以及7天真实对账尚未完成验证。** 合成样例与自动测试不代表真实覆盖率。
- 没有通知、内容隐藏、后台服务被限制的支付无法凭空补全；历史记录用文件导入，现金用手动补记。不支持读取支付App内部数据库。
- 微信仅处理标题包含微信支付/微信收款/支付凭证的通知，避免把普通聊天当账单。通知模板不同的交易可能进入待确认或未被采集。
- 支持格式以 [格式说明](docs/Formats.md) 和 `samples/` 合成文件为准。不猜测缺失的收支列，不支持PDF、旧XLS、加密工作簿或直接导入ZIP。
- 原始支出行仅标“已退款”但没有退款金额和到账时间时，进入待确认。用户应保留原支出并按真实时间另记退款，不把退款时间假定为购买时间。
- 没有云同步或自动系统备份。卸载/清除数据前务必手动备份。应用本地库依赖Android应用沙箱；它不是额外口令加密的数据库，加密保护适用于导出的备份文件。
- APK 为自用测试构建。后续覆盖升级必须使用相同签名；更换电脑/签名之前先导出备份。

## 开发与构建

需要 JDK17或21、Android SDK35、Build Tools35.0.0。使用 Android Studio 打开本目录，或设置本机 `ANDROID_HOME` / `local.properties`。Gradle Wrapper 已包含，依赖版本固定，首次构建需联网。

Windows：

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

macOS / Linux：

```sh
chmod +x gradlew
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

测试APK：`app/build/outputs/apk/debug/app-debug.apk`。分发APK使用`:app:assembleRelease`生成`app/build/outputs/apk/release/app-release.apk`，开启代码与资源压缩。维护者发布时沿用已分发1.0.0的签名，私钥不在源码中。其他开发者首次构建会生成自己的调试签名，无法覆盖官方测试包；请使用独立设备/备份验证。不要把自己的私钥提交到源码或网站。

- `core/`：不依赖Android的模型、解析、分类、去重、统计、加密备份与单元测试。
- `app/`：Room事务仓库、通知监听、文件接入、Compose界面及数据库/XLSX集成测试。
- `app/schemas/`：Room导出的数据库结构。升级数据库版本需增加迁移并保留历史数据，禁止破坏性重建。
- `samples/`：明确标记的合成测试数据。验证后可将其忽略或从备份恢复原账本。

自动测试和打包结果见 [验证报告](docs/Verification.md)。通知与真实交易内容不写入构建日志。仅交易候选记录会在本地保存识别依据，便于人工核对。

源码位于公开仓库的 `android/`，Releases同时提供对应版本的源码ZIP；依赖库仍遵循各自许可证。

如需重新渲染页面预览，可运行 `./gradlew :app:testDebugUnitTest -PqingjiScreenshots=build/previews`（Windows使用 `gradlew.bat`）。预览由Robolectric / Roborazzi渲染，测试面馆等记录为合成数据，不会打包进新安装的账本。

## 参考文档

- [Android通知监听](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android应用架构](https://developer.android.com/topic/architecture/recommendations)
- [Room](https://developer.android.com/training/data-storage/room)
- [微信交易记录下载说明](https://www.tenpay.com/v3/helpcenter/low/privacy.shtml)
- [小米后台自启动说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624)

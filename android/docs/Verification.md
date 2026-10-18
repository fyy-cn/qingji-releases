# 清记1.6.0 · 快捷归类验证

版本1.6.0 / versionCode7，Android10起。138项自动测试通过，无失败或跳过；Android Lint无问题；精简Release构建成功。

| 测试组 | 通过 | 失败 |
|---|---:|---:|
| CaptureRegressionTests | 7 | 0 |
| CategoryCsvTests | 8 | 0 |
| CategoryLayoutTests | 3 | 0 |
| LedgerTests | 11 | 0 |
| ParserTests | 18 | 0 |
| PaymentPageTests | 12 | 0 |
| QuickClassificationTests | 12 | 0 |
| ReportBackupTests | 12 | 0 |
| SampleAcceptanceTest | 2 | 0 |
| UpdateTests | 9 | 0 |
| MigrationTest | 3 | 0 |
| NotificationCaptureTest | 6 | 0 |
| PaymentPageServiceTest | 8 | 0 |
| QuickClassificationRepositoryTest | 7 | 0 |
| QuickClassificationUiTest | 1 | 0 |
| RepositoryTest | 12 | 0 |
| UiFlowTest | 1 | 0 |
| UpdateFlowTest | 3 | 0 |
| XlsxTest | 3 | 0 |
| 合计 | 138 | 0 |

纯逻辑测试覆盖直接归类、模板补空白商家/备注、保留已有内容、金额/时间/类型/币种/来源/交易号不变、未知商家不由场景名称虚构、退款类型与净消费保持、待确认/缺金额/疑似重复不自动批准，以及人工分类在后续通知和导入中保留。

数据库测试覆盖模板新增/编辑/排序/删除、同名拒绝、使用记录、批量操作原子性、过期选择拒绝、分类重命名同步模板、删除分类后停止套用、格式5完整加密备份、空模板列表如实恢复与旧格式4提供常用模板。

真实旧表结构迁移测试覆盖数据库1/2/3升级至4，保留账单、校园渠道、商家规则、导入记录、自定义分类与删除状态，新增模板时不恢复已删除分类。没有破坏性重建。

Compose测试覆盖模板新增、编辑、排序、删除、套用到现有账单、待归类入口、多选批量分类、未核实金额仍保留待确认，以及手动记账商家留空并应用场景。保留原分类管理、CSV、报表、校园来源选择、页面实验说明取消和更新流程。截图使用合成数据并经视觉检查，不进入新安装账本。

本版归类功能不依赖商家自动识别，不增加读取权限、截图、定位或后台分类提醒。沿用现有通知采集和默认关闭的页面实验，实际支付来源覆盖仍受通知和页面内容限制。所有模板和分类均在本机处理。

数据库4，备份格式5，兼容旧备份1至4。待归类包含未分类支出、退款及未知类型候选；归类操作不改变确认状态和统计总额。模板只补空白商家和备注，分类按本次用户选择修改。删除模板保留历史账单。

Android测试在Robolectric API35运行，尚未连接小米Android16真机完成本次覆盖安装或触摸验证。自动测试验证代码与合成界面，不能替代手机验证。

APK沿用原测试签名，prepare_release.py发布前比对已分发1.5.0。源码不含私钥、凭据、本机SDK路径、用户账单或构建缓存。发布后匿名检查页面、图片、清单、APK、源码ZIP和源码树，并核对SHA-256。

运行：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。操作见[快捷归类](QuickClassification.md)。

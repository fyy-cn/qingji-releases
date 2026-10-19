# 清记1.7.0 · 分类与备注验证

版本1.7.0 / versionCode8，Android10起。138项自动测试通过，无失败或跳过；Android Lint无问题；Release构建成功。

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

本版移除记账表单、模板管理、快捷归类、账单、导入预览和设置中的商家填写与展示。账单以分类为标题、显示备注；搜索分类、备注和交易号；分析按分类展示占比及增减。CSV去掉商家列，保留分类、备注、金额和身份信息。

纯逻辑与仓库测试覆盖旧模板含商家时也不再填入账单、只补空白备注、保留人工备注、金额/时间/类型/币种/来源/交易号不变、退款统计不变、待确认/缺金额/疑似重复不自动批准。分类和备注在后续通知及导入中保留。CSV验证中文、13列、BOM、准确分值与北京时间、逗号/引号/换行、退款/状态、公式防护和长交易号。

Compose测试覆盖没有商家输入框的记账与模板表单、模板新增/编辑/排序/删除、现有账单套用、待归类入口、多选批量分类、待确认保持、仅填写金额/分类/备注保存、新模板不写商家，以及旧账单修改备注后保留旧商家元数据与金额。保留分类管理、搜索后CSV导出、报表、校园来源与更新流程测试。

数据库4、备份格式5保持不变；旧账单、模板商家和旧规则完整保存在本机与加密备份。升级不清库，不批量改写历史备注。真实旧结构迁移1/2/3→4及旧备份兼容测试仍通过。来源解析与去重继续使用原交易身份信息；CSV不作为完整备份。

截图使用合成数据，经视觉检查，不进入新安装账本。Android测试在Robolectric API35运行；未连接小米Android16真机验证本次覆盖安装或触摸。自动测试不代表真实支付通知覆盖率。

APK沿用原测试签名；发布工具与已分发1.6.0比对。源码包不含私钥、凭据、本机SDK路径、用户账单与构建缓存。发布后匿名验证页面、图片、版本清单、APK、源码ZIP和标签源码并核对哈希。

运行：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。使用见[分类与备注](QuickClassification.md)。

# 清记1.4.0 · 页面识别实验验证

版本1.4.0 / versionCode5，Android10起。102项自动测试通过，无失败或跳过；Android Lint无问题；精简Release构建成功。

| 测试组 | 通过 | 失败 |
|---|---:|---:|
| CategoryCsvTests | 8 | 0 |
| CategoryLayoutTests | 3 | 0 |
| LedgerTests | 11 | 0 |
| ParserTests | 18 | 0 |
| PaymentPageTests | 12 | 0 |
| ReportBackupTests | 12 | 0 |
| SampleAcceptanceTest | 2 | 0 |
| UpdateTests | 9 | 0 |
| MigrationTest | 2 | 0 |
| PaymentPageServiceTest | 6 | 0 |
| RepositoryTest | 12 | 0 |
| UiFlowTest | 1 | 0 |
| UpdateFlowTest | 3 | 0 |
| XlsxTest | 3 | 0 |
| 合计 | 102 | 0 |

新增纯逻辑测试覆盖微信/支付宝合成支付模板、成功支出限制、缺商家/缺时间/金额歧义、退款转账收入外币排除、完整页面文本不落盘、渠道号与商户订单号区分、重复页面更新、连续同金额不同交易号、通知补全与人工修改保护。

Android集成测试覆盖默认关闭、应用开关与系统授权分离、公共系统授权入口、未授权开关/其他App/聊天窗口不读取节点、密码/输入框/隐藏/敏感/其他App节点跳过、服务事件→读取页面→保存待确认账单，以及服务声明仅限两个包名、未申请截图/手势能力、诊断排除交易字段。实际窗口内容用合成AccessibilityNodeInfo模拟，不是微信/支付宝真机页面。

Compose测试验证默认关闭和授权说明取消后仍关闭，并保留原分类排序/删除、手动记账、筛选CSV、消费分析与更新窗口流程。

读取权限由用户在手机系统设置手动开启。实验默认关闭；新候选记录待确认，不自动增加已确认消费。相同交易号可补全已有记录；无交易号疑似重复人工核实。没有新增数据库版本或云端识别服务。原备份、分类与CSV功能保留。

尚未连接小米17 Pro Max验证实际支付页面、系统授权入口、后台留存或覆盖安装。真实页面可能不暴露文字或与模板不同，当前不承诺全覆盖。请按[手机验证步骤](PageRecognition.md)先打开一笔已有成功支出的详情检查，无需专门消费。未完成的真机验证不能用自动测试替代。

APK使用同一公开测试签名；发布前由prepare_release.py与原APK核对。源码不包含私钥、凭据、本机SDK路径、用户账单或构建缓存。发布后需匿名下载核对APK与源码ZIP的SHA-256，记录位于Release校验文件。

运行：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。UI截图为合成测试数据，不进入新安装的账本。

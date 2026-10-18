# 清记1.5.0 · 校园来源与采集修复验证

版本1.5.0 / versionCode6，Android10起。117项自动测试通过，无失败或跳过；Android Lint无问题；精简Release构建成功。

| 测试组 | 通过 | 失败 |
|---|---:|---:|
| CaptureRegressionTests | 7 | 0 |
| CategoryCsvTests | 8 | 0 |
| CategoryLayoutTests | 3 | 0 |
| LedgerTests | 11 | 0 |
| ParserTests | 18 | 0 |
| PaymentPageTests | 12 | 0 |
| ReportBackupTests | 12 | 0 |
| SampleAcceptanceTest | 2 | 0 |
| UpdateTests | 9 | 0 |
| MigrationTest | 2 | 0 |
| NotificationCaptureTest | 6 | 0 |
| PaymentPageServiceTest | 8 | 0 |
| RepositoryTest | 12 | 0 |
| UiFlowTest | 1 | 0 |
| UpdateFlowTest | 3 | 0 |
| XlsxTest | 3 | 0 |
| 合计 | 117 | 0 |

新增回归覆盖微信支付副标题、普通聊天不入账、成功付款附优惠券、消息样式发送方、分组汇总排除、通知栏连接后补读、重复回调不重复入账、同金额不同交易保留、关闭页面实验仍可通知记账、权限不自动授予、诊断不含交易内容。

校园来源测试覆盖手动选择安装包、未选择或其他学校包不采集、校园消费先待确认、余额与公告排除、充值不算消费、消费详情金额与余额区分、明确消费类型但缺成功状态的核实提示、停止采集保留历史，以及格式4备份校园渠道与格式3兼容。

页面服务测试使用反馈中的MMWebViewUI类模拟事件，验证读取→待确认保存；隐藏容器的可见子节点可读，密码/输入框/敏感/其他App节点仍跳过。动态来源范围用模拟的系统服务信息连接测试，默认两个包，选择后仅增加一个校园包，移除后恢复。实际系统IPC、后台存活和真实App模板不能由这些模拟测试证明。

Compose流程验证搜索/选择/移除合成学校App，并保留原记账、分类排序/删除、筛选CSV、报表、实验授权取消和更新窗口流程。新界面截图经检查，均为合成数据，不进入新安装账本。

用户提供的小米2509FPN0BC、Android16/API36、微信8.0.78、支付宝10.8.30.8000只作为故障线索。本次Android测试运行于Robolectric API35，尚未连接该手机，也没有真实交我办通知或支付页样例。修复了已确认的窗口入口遗漏；通知具体漏记原因还需新版诊断与手机核实，不能宣称真机故障已全部解决。

数据库保持版本3，沿用原账本。加密备份格式4，读取旧格式1至3；校园选择和系统权限不进入账本备份。页面实验默认关闭，所有新页面/校园候选待确认；不接入学校后台、云端识别、截图或手势。完整操作见[通知与校园来源](NotificationCapture.md)、[页面实验说明](PageRecognition.md)。

APK沿用已分发版本签名，发布前由prepare_release.py核对。源码不含私钥、凭据、本机SDK路径、用户账单或构建缓存。发布后匿名核对下载页、图片、清单、APK、源码和SHA-256，校验结果单独记录。

运行：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。

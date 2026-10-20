# 清记1.7.1 · 通知稳定性验证

版本1.7.1 / versionCode9。152项自动测试通过，无失败或跳过；Android Lint无问题；Release构建成功。

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
| NotificationCaptureTest | 15 | 0 |
| NotificationRecoveryTest | 5 | 0 |
| PaymentPageServiceTest | 8 | 0 |
| QuickClassificationRepositoryTest | 7 | 0 |
| QuickClassificationUiTest | 1 | 0 |
| RepositoryTest | 12 | 0 |
| UiFlowTest | 1 | 0 |
| UpdateFlowTest | 3 | 0 |
| XlsxTest | 3 | 0 |
| 合计 | 152 | 0 |

新增恢复测试覆盖断开后10秒/30秒重试、连接成功停止重试、未收到断开回调但服务销毁仍恢复、通知读取null/异常清理假连接、撤销权限停止重试且不重新授权、健康空通知列表与长时间无消费不重绑、旧实例回调不影响新连接、请求失败退避与频繁检查节流、后台请求在主线程读取服务。

WorkManager测试覆盖唯一周期任务、不依赖网络或低电量限制、不受更新关闭影响、撤销权限取消安排、进程连接引用丢失后的后台重连、无授权不请求、开机和覆盖更新广播触发恢复及无关广播忽略。

原通知采集测试仍覆盖通知栏补读、重复回调不重复记账、消息样式中两笔同金额消费、忽略聊天与分组汇总、校园来源切换和待确认处理。保留全部分类、模板、批量归类、CSV、退款统计、数据库迁移、备份、页面实验与更新测试。数据库4、备份5不变。

构建沿用已分发签名，发布工具对比1.7.0。源码不含私钥、凭据、本机SDK路径、个人账单或构建缓存。截图来自合成数据，发布前检查界面；公开后匿名核对页面、图片、清单、APK、源码ZIP和源码树。

测试使用Robolectric API35，未连接小米Android16/API36真机，也未完成长时间锁屏、7天支付对账或实际开机恢复验证。周期任务存在系统调度延迟；强行停止、厂商后台限制和已清除通知不在可保证恢复范围。代码中的恢复缺口不等于已证实手机唯一根因。

运行：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。详见[通知恢复说明](NotificationCapture.md)。

# 清记 · 下载、分类与导出

固定下载页：https://fyy-cn.github.io/qingji-releases/

发布仓库：https://github.com/fyy-cn/qingji-releases

1.3.0提供分类排序与内置分类删除，修复发布页图片，并公开对应版本的完整Android源码包。发布状态见[验证报告](Verification.md)。旧Sites地址实际访问403，已不再使用。

## 分类与CSV

- 记一笔 → 新增自定义分类：命名后保存账单。设置 → 管理消费分类也可先新增未使用的分类。
- 支持重命名和删除。重命名同步历史账单和商家规则。删除前会提示受影响记录；确认后账单归为未分类，相关商家规则移除，账单和金额保留。每个名称1至20字，最多100个自定义分类，内置分类也可删除，“未分类”始终保留。上下箭头调整顺序并立即保存，记账分类选择列表同步；“恢复内置”重新添加已删除的内置分类，不改变已有账单。
- 账单页 → 导出CSV：导出当前搜索、渠道、确认状态和分析筛选后的结果。设置 → 导出全部账单（CSV）：导出所有记录，包括待确认与已忽略记录，各自状态会保留。
- CSV采用UTF-8 BOM、北京时间，包含金额、币种、商家、分类、渠道、交易类型、确认状态、交易号、备注及来源。逗号、引号、换行正确转义；潜在公式文本和长数字交易号加前导单引号，避免公式执行或表格软件丢失编号精度。
- CSV未加密，用于表格查看，不是完整恢复备份；完整迁移仍使用带口令的加密备份。备份3保存自定义分类、顺序和内置分类删除状态；新版兼容原备份1、2。旧版应用不支持新备份格式。

## 更新使用

1. 原1.0.0没有联网更新功能，需要从统一链接手动覆盖安装引导版一次。不要卸载旧版。
2. 打开清记会检查公开版本信息，同一分钟内重复返回前台会节流。设置中可随时手动检查。
3. 允许新版通知提醒。Android13及以上的显示通知权限，与支付通知读取授权分开。
4. 后台默认每天尝试检查一次，需要联网且电量不低，每个版本最多提醒一次。系统省电、强制停止和网络限制可能推迟运行，用户可关闭后台检查。
5. 查看说明后确认下载；下载完成校验大小、SHA-256、包名、版本、系统要求、签名，再调用系统安装器。可能需要允许清记安装未知应用，返回后继续确认安装。
6. 覆盖安装保留账本；拒绝通知、安装取消和网络故障不影响本地记账。“稍后再说”停止该版本自动弹窗，仍可从设置查看。

Pages部署和缓存可能使新版信息延迟数分钟，当前不是即时推送。国内各手机网络可达性需实测。

## 数据兼容与发布

数据库1→2新增分类表，2→3新增分类偏好表；旧账单、规则、自定义分类和导入记录保留。原分类字符串如FOOD保持兼容。不得使用破坏性数据库重建。

包名保持cn.qingji.app，沿用原公开测试渠道签名。私钥保存在本机Android密钥库，不在APK、公开仓库或源码包内；务必私下保管同一密钥，换电脑不能直接生成新签名后发布。该渠道尚未迁移到应用商店正式发行签名。

后续修改功能后递增versionCode与versionName，运行 `gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。默认地址已经指向本仓库；迁移可设置Gradle属性qingjiUpdateUrl、qingjiDownloadPage、qingjiGithubRepository并重新验证。

使用tools/prepare_release.py指定新APK、以前分发的可信APK、QingJi-GitHub目录、本机aapt/apksigner及更新说明。脚本比对签名，生成docs/releases/latest.json和不提交Git的release-assets安装包。公开仓库的android/保存经过筛选的Android源码，docs/保存下载页与版本资料，Releases另附同版本源码ZIP。不提交私钥、凭据、本机SDK路径、构建缓存、用户账单或备份。

先创建草稿Release、上传APK/清单/SHA256SUMS，确认一致后发布，APK不放进Git历史。Pages从main分支/docs目录提供页面与JSON。同一已发布版本不可替换安装包。公开后匿名验证页面、JSON、APK均无需登录，重新计算下载文件哈希，再用真实手机覆盖升级验证账本保留。

版本请求仅允许同源HTTPS；APK额外允许固定仓库Release路径及GitHub官方Release CDN重定向，仍强制哈希与签名校验。FileProvider仅分享私有cache/updates目录。联网不上传账单、原始通知、规则或备份。

参考：[GitHub最新版链接](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases)、[Android周期任务](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work)。

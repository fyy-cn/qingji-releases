# 清记1.3.0 · 构建与功能验证

版本1.3.0 / versionCode4，包名cn.qingji.app，Android10及以上。

## 已完成的验证

83项自动测试通过，无失败或跳过；Android Lint无问题；精简Release APK构建成功。

| 测试组 | 通过 | 失败 |
|---|---:|---:|
| CategoryCsvTests | 8 | 0 |
| CategoryLayoutTests | 3 | 0 |
| LedgerTests | 11 | 0 |
| ParserTests | 18 | 0 |
| ReportBackupTests | 12 | 0 |
| SampleAcceptanceTest | 2 | 0 |
| UpdateTests | 9 | 0 |
| MigrationTest | 2 | 0 |
| RepositoryTest | 11 | 0 |
| UiFlowTest | 1 | 0 |
| UpdateFlowTest | 3 | 0 |
| XlsxTest | 3 | 0 |
| 合计 | 83 | 0 |

新测试覆盖：分类顺序持久化、重命名后保留顺序、内置分类删除后通知与导入不再归入此类、保留账单金额、恢复内置分类、加密备份保留排序和删除状态、旧备份1/2兼容，以及数据库1→3、2→3迁移。

Compose操作测试完成新增分类、上下移动、重命名、删除内置分类、保存账单、筛选并导出CSV、进入分析和更新窗口。文件选择返回由测试模拟。截图由Robolectric/Roborazzi渲染，展示合成数据，不进入新安装的账本。

发布页图标改为内嵌SVG，避免GitHub项目站点根路径错误；增加分类、账单与分析截图。源码中排除签名私钥、本机SDK路径、构建缓存、凭据、真实账单和备份。

## 安装与边界

已有版本直接覆盖安装，不要卸载。安装包沿用原测试签名，数据库保留账单。源码不包含维护者签名私钥，自己构建生成的签名不能覆盖原测试包。请定期创建加密备份。

这次未连接小米手机完成真实安装、系统文件选择器或后台完整周期测试。通知覆盖、真实账单格式和连续7天对账仍需真机验证。

自动识别商家与分类的进一步优化尚在讨论，本次没有加入云端模型或上传账单。当前已有通知字段解析、商家分类规则和关键词分类。

构建命令：`gradlew.bat :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。

下载：[GitHub Pages](https://fyy-cn.github.io/qingji-releases/)；安装包、源码ZIP与SHA-256校验值：[v1.3.0发布记录](https://github.com/fyy-cn/qingji-releases/releases/tag/v1.3.0)。公开后需匿名重新下载校验，与本地包逐字节比对哈希。

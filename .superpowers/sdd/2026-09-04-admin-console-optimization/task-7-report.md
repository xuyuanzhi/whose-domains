# Task 7 实现报告

## 修改摘要

- 保持 `/blog/list`、`/blog/detail`、`/blog/save`、`/blog/preview`、`/blog/publish`、`/blog/unpublish` 接口和编辑/发布语义不变。
- 博客列表与编辑器继续使用中文 UTF-8 文案、动态文本转义、安全 `iframe sandbox` 预览和 `noopener,noreferrer` 公开预览；异步按钮新增 `aria-busy`，与禁用态同步。
- 删除未被菜单、视图或配置引用的 Layui 演示控制台、演示用户/设置模块及其模拟 JSON。它们包含 Baidu/OSChina 外链、空接口和模拟接口，属于隐藏的非业务功能。
- `AdminLoginTemplateTest`、`AdminNavigationTemplateTest`、`AdminUserTemplateTest` 的 Node 运行时探针增加 JUnit assumption；Domain/Contact/Dashboard 原有探针已具备同样保护。无 Node 时仅跳过 JS 运行时探针，静态安全/路由契约仍执行。
- README 明确将 `doc/insert.sql` 用于新库初始化；`doc/create.sql` 已有 `DOT_NAME`，且 `DomainController` 保存约定是 `NAME=com.cn`、`DOT_NAME=.com.cn`。因此将全部 1135 条 `WEB_DOMAIN_TLD_EXT` 种子修正为该约定，并新增种子数据契约测试。

## TDD 证据

- 首轮 RED：`BlogAdminTemplateTest,DomainSeedDataContractTest` 共 6 项，3 项按预期失败：列表/编辑器缺少 `aria-busy`，种子缺少 `DOT_NAME` 且 `NAME` 带前导点。
- 首轮 GREEN：同一命令 6/6 通过。
- 演示资源 RED：新增资源缺失契约后，先分别看到 `console.js`、`set.js` 存在而失败。
- 演示资源 GREEN：删除确认未引用的模块和模拟 JSON 后，`AdminNavigationTemplateTest` 6/6 通过。

## 回归与扫描

- 计划内聚焦回归（含新增种子契约）：98 项，0 failures，0 errors，0 skipped。
- 最终全量：`mvn -pl wesite-admin -am test` BUILD SUCCESS；`wesite-core` 56 项、`wesite-admin` 113 项，共 169 项，0 failures，0 errors，0 skipped。
- 无 Node 模拟：从 PATH 移除 Node 后运行六组模板测试，38 项中 22 项静态契约通过、16 项 JS 探针由 assumption 跳过，BUILD SUCCESS。
- `git diff --check` 无空白错误；仅有 Git 对工作区 LF/CRLF 的提示。
- `www.baidu.com`、`/website/`、`/listing/`、`Welcome`、`url: ... xxx`、博客乱码标记扫描无结果。
- `password/secureKey` 仅命中登录表单及 `MainController` 的合法认证计算；后台 DTO 和业务页面无敏感字段输出。

## 浏览器验收环境缺口

- 本隔离工作树按裁决没有带入主工作区未提交的 `application-dev.properties`；现有 dev 文件只有 springdoc 开关，没有数据源、Redis 或管理员夹具配置。
- 本机 `127.0.0.1:3306` 可连接，但没有本工作树可用的数据源凭据；`127.0.0.1:6379` 不可连接，也没有可用管理员测试凭据。
- 因此无法可靠启动 dev profile 并完成登录、CRUD、发布/撤回和窄屏交互的真实浏览器验收；没有把静态/JS 探针冒充浏览器验收。

## 未决风险

- 在核对种子时额外发现 9 个 `WEB_DOMAIN_TLD_EXT.NAME` 超过当前 `varchar(10)`/管理员 10 字符校验，另有 6 组完全重复的 NAME。它们是本次前导点修复之前就存在的数据质量问题；直接删掉会损失有效公共后缀，扩大字段与放宽 Task 4 已审定规则又超出本任务，因此本提交不擅自处理。建议单独决定“扩展字段及校验”还是“剔除不支持种子”，并在真实空库执行完整 SQL 初始化测试。

## Commit

- 本报告与实现位于同一个 Task 7 原子提交；最终哈希由 `git log -1` 获取（提交内容无法自引用自身哈希）。

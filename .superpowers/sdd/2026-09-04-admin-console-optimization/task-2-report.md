# Task 2：后台骨架、真实菜单与退出流程

## 实现

- 将 LayuiAdmin 菜单收敛为六个真实目标：`/`、`person/list`、`domain/tld`、`domain/sld`、`blog/list`、`contact/list`；所有管理员使用同一菜单。
- 更新后台标题、前台入口和管理员名称展示。管理员信息只读取 `GET /userInfo` 的安全名称字段。
- 移除搜索、便签、主题、关于、更多和演示系统入口；保留菜单折叠、前台入口、刷新和页签控制。
- 退出操作请求 `GET /logout`；无论请求成功或失败，`complete` 回调都会调用 `view.clearSession()` 并跳转到 `/user/login`。`logoutInProgress` 防止重复请求。
- 在源码与发布版 CSS 中加入 Whose.Domains 品牌色、键盘焦点样式和窄屏头部布局。

## 文件

- 新增 `wesite-admin/src/test/java/info/wesite/admin/view/AdminNavigationTemplateTest.java`
- 修改 `wesite-admin/src/main/resources/views/index.html`
- 修改 `wesite-admin/src/main/resources/static/layuiadmin/views/layout.html`
- 修改 `wesite-admin/src/main/resources/static/layuiadmin/json/menu.js`
- 修改 `wesite-admin/src/main/resources/static/layuiadmin/adminui/src/css/admin.css`
- 修改 `wesite-admin/src/main/resources/static/layuiadmin/adminui/dist/css/admin.css`

## TDD 记录

先新增菜单与退出风险契约测试。该测试要捕获的生产缺陷为：菜单出现非真实路由或演示入口；退出未先请求服务端、网络失败后未清理本地会话，或一次点击发起重复请求。

首次直接执行任务命令时，受沙箱默认 Maven 仓库 `C:\.m2\repository` 权限限制而未进入测试。随后使用已有缓存并保留同一 Surefire 参数：

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'; mvn -pl wesite-admin -am '-Dtest=AdminNavigationTemplateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

RED：失败，2 项断言失败。实际菜单含 `template/*`、`app/workorder/list`、`system/about` 等演示目标；布局仍含演示工具栏。

完成最小实现后，以相同命令运行 GREEN：`AdminNavigationTemplateTest` 2 项通过，反应堆构建成功。

## 全量测试

```powershell
$env:MAVEN_OPTS='-Dmaven.repo.local=C:\Users\Yuz\.m2\repository'; mvn -pl wesite-admin -am test
```

通过：`wesite-core` 56 项、`wesite-admin` 40 项；零失败、零错误、零跳过，Maven 退出码为 0。

## 自审与风险

- 已检查菜单跳转集合严格等于六项，且菜单不含 `senior`、`template`、`app`、`component` 或 Baidu 链接。
- 已检查退出请求文本顺序、完成回调清理与登录跳转，以及重复点击保护；测试锁定这些风险契约。
- 已检查差异范围仅为任务指定的六个实现文件、一个测试和本报告；`git diff --check` 无空白错误。
- 已知依赖：任务简报要求菜单目标为 `contact/list`，其页面由 Task 5 创建（Task 2 → 5 接口）。本任务严格按给定菜单契约实现，未扩展或修改 `wesite-web`。

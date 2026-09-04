# Task 4 实施报告：顶级域名与二级保留域名页面修复

## 状态

已完成。变更基于 `60ae16272548515b924dfd8ecf3f6807b6c392b8`，范围仅包含 `wesite-admin` 及本报告；未修改 `wesite-web` 的公开 `/domain/*` 页面或 SEO。

## 实现摘要

- `DomainController` 改为构造器注入，并保留原有六个管理端 API 路径。
- TLD/SLD 的 ID、SLD 名称在校验前去除首尾空白；SLD 名称统一使用 `Locale.ROOT` 转为小写。
- 保留 SLD 重名检查和所属 TLD 存在性检查；新增记录强制默认为启用，编辑记录只接受启用/禁用状态。
- 所有控制器失败响应改为一致的中文提示，没有新增删除接口。
- 重做 TLD/SLD 管理模板：统一使用 `/domain/tld/*`、`/domain/sld/*`，修复 toolbar/table filter 与刷新目标。
- 表格单元格和服务端反馈动态文本均转义；详情值通过 `form.val` 写入表单，移除模板值插值。
- 提交按钮使用 busy 状态防止重复请求；编辑详情使用请求序列隔离旧响应；弹窗保存绑定发起弹窗的索引，避免并发弹窗错关。
- 移除 `/listing/*`、`LAY-user-manage`、`url: 'xxx'`、假上传端点和上传脚本；弹窗尺寸改为百分比响应式尺寸。

## TDD 证据

### 控制器 RED → GREEN

- RED：`DomainControllerTest` 10 个测试全部按预期失败，失败原因包括缺少构造器、英文错误信息、ID/名称未 trim、SLD 未规范化及非法编辑状态未拒绝。
- GREEN：同一命令运行 10 个测试，0 failure / 0 error。

命令：

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 模板 RED → GREEN

- RED：`AdminDomainTemplateTest` 8 个测试全部按预期失败，暴露错误端点、事件 ID、无动态转义/无 busy，以及弹窗索引和详情竞态问题。
- GREEN：同一命令运行 8 个测试，0 failure / 0 error。
- 动态反馈文本转义另做一次小循环：新增断言后 1 个预期失败，修复后 8 个全部通过。

命令：

```powershell
mvn -pl wesite-admin -am -Dtest=AdminDomainTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
```

### 联合与完整验证

- 联合测试：18 tests，0 failure / 0 error。
- 完整 reactor：`wesite-core` 56 tests、`wesite-admin` 75 tests，共 131 tests，0 failure / 0 error；BUILD SUCCESS。

```powershell
mvn -pl wesite-admin -am -Dtest=DomainControllerTest,AdminDomainTemplateTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl wesite-admin -am test
```

## 自审

- API：仅保留简报指定的 list/detail/save 路径，未添加删除端点。
- 范围：`git status` 未显示任何 `wesite-web` 变更。
- 数据规则：SLD 标准化、重名、所属 TLD、新增默认状态、编辑状态白名单均有控制器测试。
- 前端安全与并发：API/事件 ID、转义、busy、弹窗索引和过期详情响应均有模板契约测试。
- 已知错误端点：模板契约确认 `/listing/*`、`LAY-user-manage`、`url: 'xxx'`、`res/json/upload` 和 U+FFFD 字符均不存在。

## Concerns

无功能性遗留 concern。完整测试输出仍包含仓库已有的 Bean Validation provider 提示及故意触发的错误日志，但所有测试均通过，且与本任务变更无关。

# 域名监控登录续接设计

## 背景

域名详情页的“Monitor this domain”按钮当前打开一个仅支持 Email Link 的专用弹窗。弹窗会把待监控域名写入 `localStorage.pendingDomainWatch`，但项目中已没有消费该值的逻辑，因此用户完成登录后仍无法自动加入 Watchlist。

本次改造保留专用弹窗，在其中加入 Google 登录，并让 Google 与 Email Link 两种登录方式都能在登录成功后自动完成监控，无需用户再次点击按钮。

## 目标

- 保留域名监控专用弹窗及当前域名说明。
- 弹窗同时提供 Google 登录和 Email Link 登录。
- 两种登录方式的提示和错误相互独立。
- 登录成功返回域名详情页后自动调用 Watchlist 接口。
- 自动监控成功后将按钮更新为“Monitoring”。
- 不再使用 `pendingDomainWatch` 本地存储状态。

## 非目标

- 不替换站点全局标准登录框。
- 不修改 Watchlist 的容量、通知类型或服务端业务规则。
- 不增加服务端待执行任务表或一次性监控令牌。
- 不改变已登录用户点击监控按钮时的直接添加流程。

## 交互设计

专用弹窗保持现有标题和域名说明，登录区域按以下顺序排列：

1. 带 Google 四色图标的“Continue with Google”按钮。
2. Google 登录专属状态提示区。
3. “or”分隔线。
4. Email 地址输入框。
5. “Email me a sign-in link”按钮。
6. Email Link 专属状态提示区。

Google 登录不可用时，不渲染 Google 区域和分隔线，Email Link 仍可独立使用。关闭按钮和点击遮罩关闭的现有行为保留。

## 登录续接流程

页面根据当前域名构造受控回跳地址：

```text
/domain/{当前域名}?monitor=pending
```

- Google 按钮跳转到 `/login/google?returnTo={编码后的回跳地址}`。
- Email Link 请求继续调用 `/user/email-login`，请求体增加相同的 `returnTo`。
- 回跳地址仍由现有 `ReturnTargetService` 校验，拒绝外部地址、认证路径和非法路径。
- Email Link 即使在另一浏览器或设备打开，也能通过链接中的回跳地址继续监控，不依赖原浏览器的本地存储。

## 页面返回后的自动监控

域名详情页加载时，如查询参数 `monitor=pending` 存在：

1. 检查当前会话。
2. 会话有效时调用现有 `/api/domain-watch/watch`，使用当前页面提供的域名和现有 `notifyType: 3`。
3. 添加成功或服务端表明域名已在 Watchlist 时，将按钮更新为“Monitoring”。
4. 会话仍无效时重新打开监控专用弹窗。
5. 其他业务错误或网络错误在弹窗内展示，允许用户重试。
6. 处理完成后使用 `history.replaceState` 移除 `monitor` 参数，保留地址中的其他查询参数和片段，避免刷新时重复提交。

所有待监控域名均以当前页面服务端渲染的 `data-domain` 为准，不信任查询参数或本地存储中的域名值。

## 状态与错误处理

- Google 登录结果只写入 Google 提示区，不占用 Email Link 提示区。
- Email 校验、发送成功、限流和网络错误只写入 Email 提示区。
- 自动添加遇到认证失效时打开弹窗，不显示误导性的添加失败提示。
- 自动添加遇到普通业务错误时打开弹窗并显示服务端消息；无消息时使用通用失败文案。
- 自动添加请求必须防止重复触发，URL 标记也必须及时清理。

## 可访问性

- Google 登录使用真实链接，保留可识别的按钮文字；四色 SVG 标记为装饰性图标。
- 两个状态区使用 `role="status"`、`aria-live="polite"` 和 `aria-atomic="true"`。
- 弹窗打开后优先聚焦 Google 按钮；Google 不可用时聚焦 Email 输入框。
- 保留关闭按钮的可访问名称。

## 测试策略

- 模板测试验证 Google 区域、分隔线、独立状态区和 Email 区域结构。
- 模板测试验证旧 `pendingDomainWatch` 与旧单一消息区被删除。
- 运行时测试直接执行生产脚本，验证：
  - Google 链接包含编码后的当前域名回跳地址；
  - Email 请求携带相同 `returnTo`；
  - `monitor=pending` 且会话有效时自动调用添加接口；
  - 添加成功后按钮进入“Monitoring”；
  - 会话无效时重新打开弹窗；
  - 普通失败显示在正确提示区；
  - 处理后只移除 `monitor` 参数并防止重复提交。
- 运行相关 Node 测试、聚焦 Java 模板测试及完整 Maven reactor。


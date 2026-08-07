# 认证操作与 Watchlist 未登录状态设计

## 目标

通过增加易识别的 Google 图标、将退出确认纳入站点现有视觉体系，以及用聚焦的登录卡片替换杂乱的 Watchlist 未登录提示区，让认证相关操作更清晰、更统一。

## 范围

本次改动涉及 `template.html` 中的共享认证界面、对应的公共样式，以及 `user/domain-watch.html` 的未登录状态。认证接口、登录结果码、会话行为和登录后的 Watchlist 功能保持不变。

## Google 登录按钮

现有 Google 按钮保留原有文案和跳转地址。在文字左侧增加官方四色 Google“G”图标。

图标使用内联 SVG，随页面一同加载，不产生额外网络请求，也不新增资源文件或依赖。按钮仍有完整文字标签，因此图标仅用于装饰，并设置 `aria-hidden="true"`。

当 Google 账号绑定流程把按钮文案改成 `Finish with Google` 时，只替换独立的文字元素，图标继续显示。

## 退出确认框

用户点击 `Sign Out` 后不再立即请求退出接口，而是打开自定义确认框。确认框沿用现有认证弹窗的深蓝背景、青色点缀、玻璃质感边框和圆角卡片风格。

确认框包含：

- 标题：`Sign out?`
- 说明：`You’ll need to sign in again to access your Watchlist and account settings.`
- 次要操作：`Cancel`
- 危险操作：`Sign Out`
- 用于显示请求错误的礼貌播报状态区

确认框使用 `role="dialog"`、`aria-modal="true"`，并通过标题建立可访问名称。打开时保存触发退出操作的元素，并把焦点放到更安全的 `Cancel` 按钮上。Tab 和 Shift+Tab 的焦点保持在确认框内。

按 Escape、点击遮罩、点击 `Cancel` 都会关闭确认框，并把焦点恢复到原来的 Sign Out 链接。

确认退出后：

- 两个操作按钮都进入禁用状态；
- 危险操作文案变为 `Signing out…`；
- 成功退出后刷新当前页面；
- HTTP 响应失败或网络异常时保持确认框打开，恢复按钮，并显示 `Could not sign out. Please try again.`；
- 失败时不得刷新页面。

同一时间最多只能存在一个退出请求。

## Watchlist 未登录状态

未登录提示区采用用户选定的“A：单焦点登录卡”布局：

- 青色浅底方块中的锁或账户图标；
- 标题：`Sign in to view your Watchlist`；
- 说明：`Your monitored domains and alert settings are saved securely to your account.`；
- 两个简短价值点：`Expiry alerts` 和 `Up to 50 domains`；
- 一个用于打开现有认证弹窗的主操作 `Sign In`；
- 辅助说明：`New domains can be added after signing in.`。

移除现有的快捷域名输入框、快捷邮箱输入框、`Email me a link`、重复的 `Create Account`、分隔文字、快捷监控消息区，以及相关的 JavaScript 和本地存储流程。这样可避免在已受保护的页面中同时呈现两套竞争性的登录路径。

当 Watchlist 接口返回未认证状态时：

- 隐藏加载状态；
- 隐藏已登录 Watchlist 界面；
- 显示新的未登录登录卡片。

即使用户在页面打开期间会话失效，也不会残留旧的已登录内容。

## 视觉方向

本次调整延续现有网站视觉，不引入新的主题：

- Google 按钮继续使用浅色背景，保持登录服务商的辨识度；
- 退出确认框和 Watchlist 登录卡使用现有 CSS 变量与克制的青色点缀；
- Watchlist 登录卡居中展示，限制阅读宽度，并围绕唯一主操作保留充足留白。

两个弹窗都必须适配小屏设备。仅当可用宽度不足以舒适容纳退出操作时，确认框按钮才改为纵向排列。

## 错误处理

- Google 登录行为及现有回调消息路由保持不变；
- 取消退出不会发送任何网络请求；
- 只有退出接口成功后才刷新页面；
- 退出失败时在确认框内播报错误，不关闭确认框，也不丢失当前页面状态；
- Watchlist 登录检查失败时显示未登录卡，并隐藏已认证内容。

## 测试

模板测试和可执行 DOM 测试需要验证：

- Google 图标存在、属于装饰元素，并在按钮文案变为 `Finish with Google` 后继续保留；
- 点击 Sign Out 只打开自定义确认框，不发送退出请求；
- Cancel、Escape 和点击遮罩能够关闭确认框并恢复焦点；
- Tab 和 Shift+Tab 的焦点保持在退出确认框内；
- 确认退出只发送一次 POST 请求，并正确进入加载状态；
- 退出成功会刷新页面，退出失败不会刷新，并显示可访问的错误信息；
- Watchlist 未登录卡包含已确认的内容和唯一登录操作；
- 旧的快捷域名、邮箱表单及相关 JavaScript 已移除；
- 切换到未认证状态时，先隐藏加载状态和已登录内容，再显示登录卡。

完成前运行聚焦模板/DOM 测试以及完整的 `wesite-web` reactor 测试。

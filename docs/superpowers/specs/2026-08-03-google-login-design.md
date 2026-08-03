# Google 登录集成设计

## 目标

在保留现有邮箱魔法链接登录的前提下，为 Whose.Domains 增加 Google 登录。Google OAuth2 仅负责验证 Google 身份；登录成功后继续使用项目现有的 JWT HttpOnly Cookie、`WebInterceptor` 和 `UserHolder` 会话体系。

成功标准：

- Google 用户可以首次注册并在后续直接登录。
- Google 返回的已验证邮箱与现有用户相同时，不创建重复用户。
- 非 Google 托管邮箱无论是否已有账户，都必须验证当前邮箱所有权后才能完成注册或绑定。
- 现有邮箱登录、退出、会话检查和受保护页面行为保持不变。
- Client Secret、授权码、ID Token 和访问令牌不进入仓库或日志。

## 范围

本期包含：

- Google OAuth2 授权码登录。
- Google 身份与 `SYS_USER` 的首次绑定和后续识别。
- Gmail、Google Workspace 与第三方邮箱的差异化绑定策略。
- 登录弹窗中的 Google 入口及错误提示。
- 配置、数据库迁移和自动化测试。

本期不包含：

- Google 身份解绑或更换。
- GitHub、Microsoft 等其他身份提供商。
- 调用 Google 业务 API 或持久化 Google Access/Refresh Token。
- 头像同步、账户设置页和管理员绑定工具。

## 架构

### OAuth2 边界

`wesite-web` 引入 `spring-boot-starter-oauth2-client`。Spring Security 使用两条有序安全链：

- OAuth 链仅在 Google 登录启用时创建，优先匹配 `/oauth2/**` 和 `/login/oauth2/**`，负责 Google 授权跳转、OAuth `state`、回调和 ID Token 协议校验。
- 回退链始终存在，匹配其余请求并明确 `permitAll`，关闭 CSRF、表单登录、HTTP Basic、Security Request Cache 和服务端安全上下文持久化，使现有页面、API、邮箱登录与退出继续完全由 `WebInterceptor + JWT Cookie` 管理。

始终存在回退链是必要条件：只要 Spring Security 位于 classpath，而应用没有自定义 `SecurityFilterChain`，Spring Boot 就会默认保护整个 Web 应用。Google 登录关闭时也必须验证首页和现有接口不会被默认登录页或 HTTP Basic 拦截。

Google 登录默认关闭。仅当 `WESITE_GOOGLE_LOGIN_ENABLED=true`，且 Client ID 和 Client Secret 已通过环境变量配置时，才启用 OAuth2 组件并在模板中显示 Google 按钮。

Google 配置使用项目自有的条件配置 Bean 构造 `ClientRegistration`，而不是在默认配置中声明一个空凭据的自动注册项。这样功能关闭时，Spring Boot 不会尝试用空 Client ID/Secret 初始化 OAuth 客户端。功能开关为真但 Client ID 或 Client Secret 缺失时，应用启动必须快速失败并给出不包含密钥值的配置错误。

### 用户模型

不增加独立身份表。在 `SYS_USER` 增加：

```sql
`GOOGLE_SUB` varchar(255) NULL
```

为 `GOOGLE_SUB` 创建唯一索引。MySQL/MariaDB 唯一索引允许多个 `NULL`，因此未绑定 Google 的现有用户不受影响。

Google `sub` 是 Google 账户稳定且不会复用的标识。邮箱仅用于首次匹配，绑定完成后的登录必须优先按 `GOOGLE_SUB` 查找。

### 组件职责

- OAuth2 安全配置：限定过滤器路径，配置 Google 登录成功与失败处理器。
- Google 身份解析器：读取并校验 `sub`、`email`、`email_verified` 和 `hd`，完成邮箱标准化及邮箱类型判断。
- Google 登录服务：事务化执行用户查找、创建、首次绑定和冲突处理。
- 当前 JWT 用户解析器：从项目 Cookie 验证 JWT，再从数据库加载完整且启用的用户；不依赖 OAuth 回调路径上不可用的 `UserHolder`。
- 认证 Cookie 服务：从 `UserController` 提取现有 Cookie 创建逻辑，供邮箱登录和 Google 登录共同调用，确保有效期与安全属性保持一致。
- 成功处理器：签发项目现有 JWT Cookie、清理 OAuth 临时状态并执行安全跳转。
- 失败处理器：映射安全错误码并跳转登录弹窗，不向客户端暴露提供商响应细节。

## 身份与账户匹配规则

### 必需声明

Google 回调必须具备：

- 非空 `sub`。
- 非空 `email`。
- `email_verified=true`。

Spring Security OAuth2 Client 负责签名、`iss`、`aud`、有效期和 OAuth `state` 等协议校验。业务代码不得接受前端自行提交的 Google 邮箱或 `sub` 作为登录凭证。

邮箱统一执行去除首尾空格和小写化。现有 `SYS_USER` 使用 `utf8mb4_bin`，其 `EMAIL` 唯一索引区分大小写，因此迁移必须先处理历史邮箱，再将 `EMAIL` 列调整为大小写不敏感的排序规则并重建唯一索引；不能仅依赖应用层标准化。

### 邮箱类型判断

- Gmail：邮箱域名为 `gmail.com`。
- Google Workspace：`hd` 非空，且标准化后的邮箱域名与 `hd` 完全一致。
- 第三方邮箱：不满足以上条件的其他邮箱。

`email_verified=true` 对第三方邮箱不等价于 Google 能持续证明当前邮箱所有权。由于同一 `SYS_USER.EMAIL` 也能通过魔法链接登录，第三方邮箱无论对应已有用户还是新用户，都必须追加邮箱魔法链接确认，避免旧 Google 账户持有人和当前邮箱持有人共享同一站内账户。

### 匹配顺序

1. 按 `GOOGLE_SUB` 查询。
2. 命中时从数据库加载完整用户并检查 `STATUS_ACTIVE`；只有启用用户可以登录。不得因本次返回邮箱变化而切换到其他用户。
3. 未命中时，按标准化邮箱查询。
4. 如果请求已携带有效的项目 JWT Cookie，则由 Google 成功处理器直接读取 Cookie、调用 `TokenUtils.verifyToken()`，再按用户 ID 从数据库加载完整用户。OAuth 回调由 Spring Security Filter 消费，不能依赖 MVC `WebInterceptor` 或 `UserHolder`。只有数据库用户为启用状态，且当前用户就是邮箱查询命中的用户时，才允许直接绑定本次 `sub`。这用于用户已通过邮箱魔法链接证明所有权后再次发起 Google 授权的场景。
5. Gmail 或 Google Workspace 邮箱：
   - 邮箱用户存在且 `GOOGLE_SUB` 为空：原子绑定本次 `sub`。
   - 邮箱用户存在且绑定同一 `sub`：视为成功。
   - 邮箱用户存在且绑定不同 `sub`：返回账户冲突，不覆盖。
   - 邮箱用户不存在：创建用户并同时保存 `GOOGLE_SUB`。
6. 第三方邮箱：
   - 邮箱用户存在：进入邮箱确认流程。
   - 邮箱用户不存在：暂不创建用户，进入邮箱确认流程；确认成功后再创建用户并同时保存 `GOOGLE_SUB`。

所有匹配路径，包括按 `GOOGLE_SUB`、按邮箱、当前 JWT 用户和邮箱确认完成后的用户，都必须拒绝 `STATUS_INACTIVE` 用户。MyBatis-Plus 逻辑删除规则继续负责排除已删除用户。

## 登录数据流

### 常规 Google 登录

1. 用户在登录弹窗点击 “Continue with Google”。
2. 浏览器进入 `/oauth2/authorization/google` 并跳转 Google。
3. Google 回调 `/login/oauth2/code/google`。
4. Spring Security 完成协议校验，Google 登录服务执行身份匹配。
5. 登录成功后签发与邮箱登录一致的 30 天 JWT Cookie：HttpOnly、Secure、SameSite=Lax、Path=/。
6. 显式移除 `OAuth2AuthorizedClient`，清理 Spring `SecurityContext` 和 OAuth 临时会话，确保 Google ID Token、Access Token 及授权响应不继续保存在服务端 Session 中。
7. 跳转 `/user/watchlist?login=success`。

### 第三方邮箱确认

1. Google 登录服务发现第三方邮箱；无论邮箱用户是否存在，均进入确认流程。新用户在确认完成前不写入 `SYS_USER`。
2. 将待绑定的用户 ID（新用户时为空）、Google `sub`、标准化邮箱和短期过期时间保存在当前 OAuth HTTP Session 中。
3. 复用现有魔法链接服务，向该邮箱发送单次、短时有效的确认链接，并继续执行现有冷却和每日限额。
4. 邮件发送成功后，立即显式移除 `OAuth2AuthorizedClient` 并清理 Spring `SecurityContext`；Session 只保留用户 ID、`sub`、邮箱和过期时间，不保留 Google Token 或完整用户资料。
5. 用户在同一浏览器点击链接：消费魔法链接后，同时校验 HTTP Session 中待绑定信息的邮箱、用户 ID 和有效期。已有用户执行原子绑定；新用户在事务内创建并绑定。随后签发项目 JWT Cookie 并清理待绑定 Session。
6. 用户在另一浏览器点击链接：该浏览器没有待绑定 Session，因此只执行现有邮箱登录，不绑定 Google。结果页提供明确的 “Finish with Google” 重试入口；再次授权时，成功处理器读取有效项目 JWT、加载相同邮箱的启用用户后完成绑定。

待绑定 Google `sub` 不放入 URL，不写入普通重定向参数，也不因邮件点击而信任客户端提供的值。

## 并发与一致性

用户创建与 Google 绑定在事务中完成。事务失败后不得签发项目 JWT Cookie。

- `GOOGLE_SUB` 唯一索引防止同一 Google 账户绑定多个用户。
- `EMAIL` 唯一索引防止并发创建重复用户。
- 首次绑定采用带 `WHERE GOOGLE_SUB IS NULL` 条件的更新。
- 条件更新失败后重新读取记录：已为同一 `sub` 时幂等成功；为不同 `sub` 时返回冲突。
- 并发创建触发邮箱唯一键冲突时，重新读取邮箱用户并重新执行绑定规则。
- 不自动覆盖或解绑已有 `GOOGLE_SUB`。

## 错误处理

面向用户使用稳定、通用的错误码和英文提示：

- 用户取消或 Google 暂时不可用。
- Google 身份资料不完整或邮箱未验证。
- 账户已绑定其他 Google 身份。
- 邮箱确认邮件发送失败或达到限额。
- 登录流程已过期，请重新尝试。

失败后重定向首页，通过 `login` 查询参数自动打开登录弹窗并显示对应通用提示。查询参数只允许预定义错误码，不直接显示 Google 返回内容或异常消息。

服务端日志记录错误类型和内部关联信息，但不得记录授权码、ID Token、Access Token、Client Secret 或完整 OAuth 响应。对邮箱等个人信息按现有日志策略最小化记录。

所有登录成功后的跳转目标使用固定站内路径。若未来支持返回原页面，必须使用站内路径白名单，禁止开放重定向。

## UI 设计

现有登录弹窗保持单一入口：

- 顶部增加全宽 “Continue with Google” 按钮。
- 中间显示 “or” 分隔线。
- 下方原样保留邮箱输入框和 “Email Me a Sign-in Link”。
- Google 登录未启用时，不渲染按钮及分隔线，邮箱登录布局保持正常。
- OAuth 失败或待邮箱确认时，登录弹窗自动打开并在现有消息区域显示结果。
- 跨浏览器完成邮箱确认时，结果页显示 “Finish with Google” 按钮，链接固定为 `/oauth2/authorization/google`；功能关闭时不显示该按钮。

## 配置与密钥

应用配置引用环境变量，不提供真实默认密钥：

- `WESITE_GOOGLE_LOGIN_ENABLED`
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`

生产环境在 Google Cloud Console 配置精确回调地址：

```text
https://whose.domains/login/oauth2/code/google
```

应用侧的 `ClientRegistration.redirectUri` 同样必须由 `wesite.public-base-url` 明确构造为：

```text
{wesite.public-base-url}/login/oauth2/code/{registrationId}
```

生产环境不能只依赖入站请求推导 `{baseUrl}`，否则在 Nginx、CDN 或负载均衡器终止 HTTPS 时，若转发头配置不完整，应用可能生成 `http://` 回调并触发 Google `redirect_uri_mismatch`。启动时校验 `wesite.public-base-url` 为绝对 HTTPS URL；本地开发环境可显式允许 `http://localhost`。

本功能只请求 `openid email profile`，不申请 Google API 敏感权限，也不持久化 Google Access Token 或 Refresh Token。

## 测试设计

### 单元测试

- Gmail、Workspace、第三方邮箱分类。
- `email_verified=false`、缺少 `sub`、缺少邮箱时拒绝。
- 邮箱大小写和首尾空格标准化。
- Workspace `hd` 与邮箱域不一致时按第三方邮箱处理。

### 服务测试

- 已绑定 `sub` 直接登录。
- 已有 Gmail/Workspace 用户自动绑定。
- 第三方邮箱已有用户进入邮件确认。
- 第三方邮箱新用户在确认前不落库，确认后创建用户并保存 `GOOGLE_SUB`。
- 已绑定不同 `sub` 时返回冲突。
- 停用用户在按 `GOOGLE_SUB`、邮箱或 JWT 命中时均被拒绝。
- 并发绑定和并发创建遵守唯一约束并给出确定结果。

### MVC 与模板测试

- OAuth 成功后生成与现有登录一致的安全 Cookie。
- 失败回调只暴露预定义安全错误码。
- 第三方邮箱在同一浏览器确认后完成绑定。
- 第三方邮箱跨浏览器确认时只登录、不绑定。
- 第三方邮箱跨浏览器确认后，可通过 “Finish with Google” 再次授权并绑定当前已登录用户。
- 进入第三方邮箱确认状态后，Session 不包含 `OAuth2AuthorizedClient`、ID Token 或 Access Token。
- 功能关闭时不显示 Google 按钮，应用可在无 Google 密钥时启动。
- 功能关闭时首页、邮箱登录、退出和现有 API 不被 Spring Security 默认登录页、HTTP Basic 或 CSRF 拦截。
- 功能开启时 Google 按钮、分隔线和邮箱入口同时存在。
- 生产配置生成的授权请求使用 `https://whose.domains/login/oauth2/code/google` 作为 `redirect_uri`。
- 现有邮箱登录、退出、会话检查和受保护页面回归测试全部通过。

## 数据库迁移与发布

迁移脚本给 `SYS_USER` 增加可空 `GOOGLE_SUB` 和唯一索引，同时修正邮箱唯一性的大小写语义。迁移分为以下步骤：

1. 预检 `LOWER(TRIM(EMAIL))` 后的重复组；若存在多个用户映射到同一标准化邮箱，迁移必须停止并由人工确认合并策略，不得自动删除或覆盖用户数据。
2. 将非空邮箱统一更新为 `LOWER(TRIM(EMAIL))`。
3. 将 `EMAIL` 列调整为项目数据库兼容的大小写不敏感 `utf8mb4` 排序规则，并重建 `IDX_USER_EMAIL` 唯一索引。
4. 增加可空 `GOOGLE_SUB varchar(255)` 和区分大小写的唯一索引。

应用发布顺序：

1. 执行向后兼容的数据库迁移。
2. 部署默认关闭 Google 登录的新版本。
3. 在 Google Cloud Console 配置正式域名和回调地址。
4. 注入 Client ID、Client Secret 并开启功能开关。
5. 验证首次注册、现有用户绑定、再次登录、取消授权和邮箱确认流程。

关闭功能开关即可停止新 Google 登录；已有用户的 `GOOGLE_SUB` 保留，不影响邮箱登录或其他业务数据。

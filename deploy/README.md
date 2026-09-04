# Whose.Domains 独立 systemd 生产运维手册

本手册用于迁移和运维 `47.76.125.96` 上的生产环境。前台 Web 与后台 Admin
分别使用独立的不可变发布目录、服务、健康检查、回滚和 Jenkins 任务：

```text
/usr/java/apps/web/{current,previous,releases/}
/usr/java/apps/admin/{current,previous,releases/}
```

公开的 Web 服务监听 `127.0.0.1:8080`，Admin 监听 `127.0.0.1:8082`。
Jenkins 只能使用 `wesite-deploy` 账号连接。常规任务只构建并上传一个应用的
JAR，然后调用该应用白名单内的部署和检查命令。基础设施安装、正式配置、数据库
操作、回滚、服务管理和旧版本清理均由运维人员执行，绝不能成为 Jenkins 任务的
附带行为。

服务器有 3.5 GiB 内存。仓库中的 systemd 单元为 Web 最多预留 768 MiB 堆内存，
为 Admin 最多预留 384 MiB，并明确限制元空间和直接内存。2 GiB 交换文件只是
应急缓冲，不是常规工作内存。

## 1. 前置检查 Java、unzip 和 flock

建立维护记录，写明操作人员、UTC 时间、源代码提交和计划的回滚方案。通过获准的
生产运维账号执行以下只读检查：

```bash
java -version
command -v java
test -x /usr/bin/java
command -v unzip
command -v flock
systemctl is-active nginx redis-server
free -h
df -h /
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
crontab -l
sudo crontab -l
```

如果 `/usr/bin/java`、`unzip` 或 `flock` 不可用，请立即停止。现在必须确认旧启动器
和 watchdog 的准确归属，不要使用大范围结束进程的命令。确认 Nginx 和 Redis
运行正常，并确认 8080、8082 端口只由预期的旧 JVM 占用。

## 2. 验证交换空间

```bash
free -h
swapon --show
grep -F '/swapfile none swap sw 0 0' /etc/fstab
df -h /
```

记录检查结果。在这台小内存服务器上，预期的 2 GiB 交换空间必须已经启用且能够
在重启后保留；否则必须先获得运维人员对替代方案的批准，不能进入配置或应用迁移
阶段。如果缺少交换空间，可以在第 4 步安装部署包后，使用经过审查的辅助脚本执行
首次获准的写入操作。`/usr/local/sbin/ensure-wesite-swap` 不会覆盖已经存在但尚未
启用的文件。

## 3. 仅构建并上传基础设施部署包

在可信的构建机或运维机器上，从已经审查的提交执行：

```bash
INFRA_COMMIT="$(git rev-parse --verify HEAD)"
[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
INFRA_OUTPUT="$(mktemp -d)"
scripts/build-wesite-deployment-bundle.sh "$INFRA_COMMIT" "$INFRA_OUTPUT"
ls -l "$INFRA_OUTPUT"
(
  cd "$INFRA_OUTPUT"
  sha256sum --check "wesite-deployment-$INFRA_COMMIT.tar.gz.sha256"
)
```

输出目录必须恰好包含以下三个文件：

```text
wesite-deployment-<commit>.tar.gz
wesite-deployment-<commit>.tar.gz.sha256
install-wesite-deployment-bundle.sh
```

将 `PRODUCTION_OPERATOR` 设置为获准的特权运维账号，它不能是 `wesite-deploy`。
已验证的提交值可以安全地用于临时目录路径：

```bash
REMOTE_INFRA="/tmp/wesite-infrastructure-$INFRA_COMMIT"
ssh "$PRODUCTION_OPERATOR@47.76.125.96" \
  "umask 077 && mkdir -m 0700 -- '$REMOTE_INFRA'"
scp "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz" \
  "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz.sha256" \
  "$INFRA_OUTPUT/install-wesite-deployment-bundle.sh" \
  "$PRODUCTION_OPERATOR@47.76.125.96:$REMOTE_INFRA/"
```

不要随基础设施部署包传输仓库工作区、`.git`、源代码、Maven 文件、测试、应用 JAR
或正式环境机密信息（包括密钥、密码和令牌）。

## 4. 运行独立引导安装程序

在生产服务器上检查上传的三个文件，然后仅以 root 身份运行独立引导安装程序。
安装程序会校验压缩包随附校验文件、拒绝不安全的归档成员、校验内部载荷清单、
保留现有正式配置、安装命令和 systemd 单元，并重新加载 systemd。它不会启用或
启动任何应用及定时器。

```bash
INFRA_COMMIT=0123456789abcdef0123456789abcdef01234567
[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
cd "/tmp/wesite-infrastructure-$INFRA_COMMIT"
sudo ./install-wesite-deployment-bundle.sh \
  "wesite-deployment-$INFRA_COMMIT.tar.gz" \
  "wesite-deployment-$INFRA_COMMIT.tar.gz.sha256"
sudo systemd-analyze verify \
  /etc/systemd/system/wesite-web.service \
  /etc/systemd/system/wesite-admin.service \
  /etc/systemd/system/wesite-health-monitor.service \
  /etc/systemd/system/wesite-health-monitor.timer
sudo stat -c '%U:%G %a %n' /run/lock/wesite
```

部署包中的 `tmpfiles.d` 策略会在每次启动时重建易失的部署锁目录。检查结果必须为
`root:root 755`；如果锁路径不安全或可被替换，部署、回滚和健康恢复都会拒绝执行。

如果第 2 步确认缺少交换空间，且获准方案是使用部署包中的辅助脚本，现在执行它，
然后重新检查交换空间再继续：

```bash
sudo /usr/local/sbin/ensure-wesite-swap
free -h
swapon --show
```

引导安装成功后，只删除这三个明确的临时文件及其已经为空的目录。将压缩包摘要保存
到维护记录中。

## 5. 迁移并保留正式配置

安装程序会创建配置示例，并且只在正式配置文件不存在时创建它，绝不会替换现有
正式配置值。将旧生产配置与以下目标文件逐项比较并谨慎合并：

```text
/etc/wesite/wesite.env
/usr/java/config/web/application-prod.properties
/usr/java/config/admin/application-prod.properties
```

使用 `sudoedit` 编辑配置；绝不能将 `/etc/wesite/wesite.env` 作为 Shell 输入执行，
其中的 JDBC URL 可能包含 Shell 元字符。保留经过测试的远程 MySQL URL，包括明确的
UTC 连接和会话选项，并配置数据库、Redis、JWT、内部博客、邮件及 MaxMind 参数。
首次发布时保持通知投递关闭，并让两个服务继续只监听回环地址。

```bash
sudoedit /etc/wesite/wesite.env
sudoedit /usr/java/config/web/application-prod.properties
sudoedit /usr/java/config/admin/application-prod.properties

sudo chown root:wesite /etc/wesite/wesite.env \
  /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties
sudo chmod 0600 /etc/wesite/wesite.env
sudo chmod 0640 /usr/java/config/web/application-prod.properties \
  /usr/java/config/admin/application-prod.properties

if sudo grep -R -n 'CHANGE_ME\|your-db-\|/path/to/' \
    /etc/wesite/wesite.env \
    /usr/java/config/web/application-prod.properties \
    /usr/java/config/admin/application-prod.properties; then
  printf 'Stop: production placeholders remain.\n' >&2
  exit 1
fi
```

将这三个审核后的正式配置文件备份到发布目录之外。正式环境机密信息（包括密钥、
密码和令牌）绝不能作为 Jenkins 参数，也不能出现在工作区、归档或构建产物中。

## 6. 安装 Jenkins SSH 公钥

在 Jenkins 密钥存储中创建 SSH 密钥凭据，只将经过审查的公钥传输到运维人员所有的
临时路径。安装前根据维护记录核对指纹。引导安装程序会创建专用的
`wesite-deploy` 账号，但有意不修改 SSH 守护进程配置，也不会生成凭据。

```bash
ssh-keygen -lf /tmp/whose-domains-prod-ssh.pub
sudo install -d -o wesite-deploy -g wesite-deploy -m 0700 \
  /var/lib/wesite-deploy/.ssh
sudo install -o wesite-deploy -g wesite-deploy -m 0600 \
  /tmp/whose-domains-prod-ssh.pub \
  /var/lib/wesite-deploy/.ssh/authorized_keys
sudo stat -c '%U:%G %a %n' \
  /var/lib/wesite-deploy/.ssh \
  /var/lib/wesite-deploy/.ssh/authorized_keys
```

在服务器现有 SSH 策略下测试 `wesite-deploy` 的纯密钥登录。不要授予 root 登录、
root Shell 或运维人员密钥的访问权限。

## 7. 验证并显式启用 sudoers

引导安装程序只会安装一个尚未启用的示例文件。先验证这个准确的文件，再显式启用
并验证完整的 sudoers 策略：

```bash
sudo visudo -cf /etc/wesite/wesite-deploy.sudoers.example
sudo install -o root -g root -m 0440 \
  /etc/wesite/wesite-deploy.sudoers.example \
  /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers
sudo -l -U wesite-deploy
```

免密命令必须只包含单个 Web/Admin 应用的部署和检查。该账号不能获得基础设施安装、
配置修改、服务管理、回滚、清理旧版本、任意 root 命令或 Shell 权限。

## 8. 准备相互独立的旧 JAR 基线版本

停止旧进程管理器之前，分别找到准确且已知正常的 JAR，记录其来源和 SHA-256，
然后不做修改地复制到各自的、由 root 所有且权限为 `0700` 的接收目录。不要猜测
路径，也不要用一个应用的构建产物代替另一个应用。

```bash
find /usr/java -maxdepth 4 -type f -name 'wesite-*.jar' -print

BASELINE_ID="$(date -u +%Y%m%dT%H%M%SZ)"
[[ "$BASELINE_ID" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]
sudo mkdir -m 0700 \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID"
sudo install -o root -g root -m 0400 /absolute/reviewed/old-wesite-web.jar \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar"
sudo install -o root -g root -m 0400 /absolute/reviewed/old-wesite-admin.jar \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
sudo sha256sum \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
```

完成第 9 步、确保旧 watchdog 和启动器不再与 systemd 竞争之后，这两个暂存候选文件
会在第 10 步分别成为两个应用的基线版本。如果无法验证某个旧构建产物，应在记录中
注明该应用没有初始二进制回滚目标，并改为备份配置和文件系统。

## 9. 停用旧 watchdog 和启动器

进入维护窗口。根据第 1 步确认的准确来源，停用并停止旧 watchdog（cron 条目、
定时器或服务），然后停止准确的旧 Java 启动器。不要结束无关的 Java 进程，也不要
让两个进程管理器同时拥有同一个端口。

启用基线版本之前验证结果：

```bash
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
grep -R "watchdog" /etc/cron.d /etc/cron.daily /etc/systemd/system 2>/dev/null
ss -lntp | grep -E '127\.0\.0\.1:(8080|8082)' || true
```

记录被停用的准确单元、定时器、cron 文件或启动器。移除旧机制是一次性的运维操作，
绝不能成为 Jenkins 步骤。

## 10. 启用基线并执行首次独立发布

分别启用和检查每个经过验证的旧二进制文件。旧版 HTTP 200 健康检查覆盖参数必须由
root 直接调用，并且只能用于尚未提供就绪端点的旧二进制文件：

```bash
sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  WESITE_APP_HEALTH_URL=http://127.0.0.1:8080/ \
  /usr/local/sbin/deploy-wesite-app web \
  "baseline-web-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-web-$BASELINE_ID/wesite-web.jar"
sudo /usr/local/sbin/check-wesite-app web

sudo -i env -u SUDO_USER -u SUDO_UID -u SUDO_GID \
  WESITE_HEALTH_RESPONSE_MODE=legacy-http-200 \
  WESITE_APP_HEALTH_URL=http://127.0.0.1:8082/ \
  /usr/local/sbin/deploy-wesite-app admin \
  "baseline-admin-$BASELINE_ID" \
  "/var/lib/wesite-deploy/incoming/baseline-admin-$BASELINE_ID/wesite-admin.jar"
sudo /usr/local/sbin/check-wesite-app admin
```

每条命令返回后，只删除对应的基线输入文件及其已经为空的暂存目录。绝不能删除其
不可变发布目录。

在 CI 或其他构建机上构建新的 Web 和 Admin 候选版本。分别运行并记录两个模块的
构建；生产服务器只接收 JAR，不接收源代码：

```bash
mvn -B -pl wesite-web -am clean verify
mvn -B -pl wesite-admin -am clean verify
```

先使用基于提交号的版本标识暂存和部署 Web，并只检查 Web；然后独立暂存和部署
Admin，并只检查 Admin。新版本使用默认的严格就绪检查约定，不能使用旧版健康检查
覆盖参数。只将两个构建完成的 JAR 分别传输到运维人员所有的临时路径。在生产环境
验证记录的提交号，创建两个私有接收目录，再安装各自准确的构建产物：

```bash
NEW_COMMIT=0123456789abcdef0123456789abcdef01234567
[[ "$NEW_COMMIT" =~ ^[0-9a-f]{7,64}$ ]]
sudo mkdir -m 0700 \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT"
sudo install -o root -g root -m 0400 \
  "/tmp/wesite-web-$NEW_COMMIT.jar" \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT/wesite-web-1.0.0.jar"
sudo install -o root -g root -m 0400 \
  "/tmp/wesite-admin-$NEW_COMMIT.jar" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT/wesite-admin-1.0.0.jar"
```

逐个调用并检查发布：

```bash

sudo /usr/local/sbin/deploy-wesite-app web \
  "$NEW_COMMIT-first-web" \
  "/var/lib/wesite-deploy/incoming/first-web-$NEW_COMMIT/wesite-web-1.0.0.jar"
sudo /usr/local/sbin/check-wesite-app web

sudo /usr/local/sbin/deploy-wesite-app admin \
  "$NEW_COMMIT-first-admin" \
  "/var/lib/wesite-deploy/incoming/first-admin-$NEW_COMMIT/wesite-admin-1.0.0.jar"
sudo /usr/local/sbin/check-wesite-app admin
```

某个应用部署失败时，只回滚该应用。发生失败后应停止并调查；不要将另一个应用加入
它的健康门禁或回滚过程。

## 11. 启用服务和健康检查定时器

两个首次发布检查均通过后执行：

```bash
sudo systemctl enable --now \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-enabled \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-active \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl list-timers wesite-health-monitor.timer --no-pager
```

定时器会独立评估每个已经部署的应用。有意停止应用进行维护之前，应先停止定时器和
正在执行的监控任务，避免它将维护操作判定为故障。

## 12. 生产运维

### 单应用检查和全局检查

```bash
sudo /usr/local/sbin/check-wesite-app web
sudo /usr/local/sbin/check-wesite-app admin
sudo /usr/local/sbin/check-wesite-services
curl --fail --silent --show-error http://127.0.0.1:8080/api/healthz
curl --fail --silent --show-error http://127.0.0.1:8080/api/readyz
curl --fail --silent --show-error http://127.0.0.1:8082/api/readyz
nginx -t
```

在运维机器上执行：

```powershell
pwsh -NoProfile -File scripts/check-production-smoke.ps1 -BaseUrl https://whose.domains
pwsh -NoProfile -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

### Jenkins 凭据和两个独立任务

在 Jenkins Credentials 中创建：

- 名为 `whose-domains-prod-ssh` 的 SSH Username with private key 凭据，用户名为
  `wesite-deploy`；
- 名为 `whose-domains-prod-known-hosts` 的 Secret file 凭据。

通过可信控制台或云服务商渠道获取生产服务器公钥。先通过带外方式比对指纹，再将
准确的 `47.76.125.96` known-hosts 行写入 Secret file。仅使用 `ssh-keyscan` 输出
无法验证主机密钥的真实性。

创建两个 Pipeline-from-SCM 任务：

- Web 使用 `deploy/jenkins/wesite-web.Jenkinsfile`；
- Admin 使用 `deploy/jenkins/wesite-admin.Jenkinsfile`。

每个任务都会禁止同一任务并发执行，将 `GIT_COMMIT` 验证为小写十六进制，将
`BUILD_NUMBER` 验证为十进制数字，固定主机密钥，创建权限为 `0700` 的
应用/提交/构建暂存目录，只上传自己的 JAR，并且只调用自己的非交互式部署和检查
命令。同一远程 Shell 中的清理步骤只会删除已上传文件和空任务目录，最后返回保存的
部署/检查状态。

常规任务不能上传基础设施或源代码、安装 systemd 单元、修改配置、直接启动 Java、
直接管理服务、调用 watchdog、执行回滚、清理旧版本或执行数据库维护。独立发布期间
可能暂时运行不同提交的 Web 和 Admin，因此数据库结构、JWT 声明、Redis 值、内部
API 及其他共享约定的变更，必须对相邻版本保持向后和向前兼容。不兼容变更必须安排
协调维护窗口。

### 仅限 root 的手动回滚

Jenkins 没有回滚权限。运维人员只回滚一个应用并检查同一个应用，然后再决定是否
需要单独回滚另一个应用：

```bash
sudo /usr/local/sbin/rollback-wesite-app web
sudo /usr/local/sbin/check-wesite-app web

sudo /usr/local/sbin/rollback-wesite-app admin
sudo /usr/local/sbin/check-wesite-app admin
```

回滚会交换该应用成功版本的 `current` 和 `previous` 目标。如果目标版本未通过其
记录的健康检查约定，命令会恢复原始链接和原始版本。

### 查看 journal 日志

```bash
journalctl -u wesite-web.service --since '-15 min' --no-pager
journalctl -u wesite-admin.service --since '-15 min' --no-pager
journalctl -u wesite-health-monitor.service --since '-15 min' --no-pager
journalctl -u wesite-web.service -f
journalctl -u wesite-admin.service -f
```

### 仅限 root 的旧版本清理

清理旧版本必须作为一项单独且经过审查的运维变更执行。每次只处理一个应用：解析并
记录它的 `current`、`previous` 目标，列出终态发布元数据，并在删除前打印准确的
候选目录：

```bash
sudo realpath -e /usr/java/apps/web/current
sudo realpath -e /usr/java/apps/web/previous
sudo find /usr/java/apps/web/releases -mindepth 1 -maxdepth 1 -type d -print

sudo realpath -e /usr/java/apps/admin/current
sudo realpath -e /usr/java/apps/admin/previous
sudo find /usr/java/apps/admin/releases -mindepth 1 -maxdepth 1 -type d -print
```

保留两个链接目标，以及除此之外最近的至少三个成功版本。故障版本应保留到诊断完成。
只有 root 才能在检查版本的 `APP`、`VERSION`、`STATUS` 以及确认它不被链接引用后，删除
一个完全解析且路径明确的发布目录。绝不能删除未经检查的变量、符号链接目标、应用
根目录、共享父目录，也不能使用通配符删除所有版本。记录上述检查后，只删除已经审查
的字面量路径，例如
`sudo rm -rf -- /usr/java/apps/web/releases/0123456789abcdef-123`；替换并重新检查
版本身份之前，绝不能直接粘贴执行这个示例。

## 协调执行博客数据库迁移和内容净化

这是两个常规 Jenkins 任务之外的数据库和应用维护操作，不能成为任一发布流程的
隐式副作用。安排协调维护窗口，从备份开始到应用变更完成期间保持常规写入程序停止，
并将所有输出保存在变更记录中。

有计划地停止监控任务和两个服务：

```bash
sudo systemctl stop \
  wesite-health-monitor.timer wesite-health-monitor.service \
  wesite-web.service wesite-admin.service
```

在包含已审查 SQL 文件的可信运维机器上，使用具有结构变更权限的账号直接连接远程
数据库，备份并迁移 `WEB_BLOG_POST`。不要将仓库复制到生产服务器：

```bash
mysqldump --single-transaction -h MYSQL_HOST -u DB_USER -p wesitedb \
  WEB_BLOG_POST > web-blog-post-before-editorial-$(date -u +%Y%m%dT%H%M%SZ).sql
sha256sum web-blog-post-before-editorial-*.sql

mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  < doc/alter_blog_editorial_workflow.sql
mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  -e "SHOW COLUMNS FROM WEB_BLOG_POST LIKE 'CONTENT_UPDATED_AT';"
```

将经过审查的 Admin 维护 JAR 复制为 root 所有、运行账号可读的文件。通过 root 创建的
临时 systemd 单元运行它，让 systemd 而不是 Shell 解析环境文件：

```bash
sudo install -o root -g wesite -m 0440 \
  /tmp/wesite-admin-1.0.0.jar /usr/java/wesite-admin-maintenance.jar

set -o pipefail
sudo systemd-run --quiet --wait --collect --pipe \
  --unit="wesite-blog-sanitize-dry-$(date +%s)" \
  --property=User=wesite --property=Group=wesite \
  --property=EnvironmentFile=/etc/wesite/wesite.env \
  --property=WorkingDirectory=/usr/java \
  /usr/bin/java -Xms128m -Xmx384m \
  -jar /usr/java/wesite-admin-maintenance.jar \
  --spring.profiles.active=prod,blog-sanitize \
  --spring.main.web-application-type=none \
  --spring.config.additional-location=file:/usr/java/config/admin/ \
  --wesite.blog.sanitization.mode=dry-run \
  --wesite.blog.sanitization.batch-size=100 \
  2>&1 | sudo tee /usr/java/logs/blog-sanitize-dry-run.log
```

检查每一项拟议变更；如果变更数量很大，则检查明确记录的样本。使用新的单元名称、
`mode=apply`，并将输出写入单独的 apply 日志后重复执行。非零退出状态或缺少完成报告
都必须中止变更。
再次运行 dry-run，结果必须报告零项变更。

通过两个独立的应用部署命令发布经过测试且兼容性匹配的 Web 和 Admin 二进制文件，
并分别检查每个应用。二进制回滚期间保留新增的数据库列。内容回滚只根据记录的备份
恢复受影响 ID 的原始 `CONTENT` 和 `CONTENT_UPDATED_AT` 值，绝不能覆盖其他无关的
文章编辑。只有应用、编辑流程、公开页面、P0 冒烟测试和 SEO 检查全部通过后，才能
恢复服务和定时器。

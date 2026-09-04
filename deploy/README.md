# Whose.Domains 生产部署与运维手册

本文档适用于以下部署架构：

```text
GitHub
  ├─ Web Pipeline  ─┐
  └─ Admin Pipeline ─┴─> 本地 Docker Desktop 中的 Jenkins
                              │
                              │ Tailscale SSH/SCP
                              ▼
                     hk.tail5ed8be.ts.net
                              │
                    ┌─────────┴─────────┐
                    │                   │
             wesite-web.service  wesite-admin.service
               127.0.0.1:8080      127.0.0.1:8082
```

生产服务器不保存 Git 仓库或项目源代码。首次初始化时只上传基础设施部署包；日常发布
时，Web 和 Admin 两个 Jenkins 任务分别只上传自己的 JAR。

## 1. 先了解整体流程

### 1.1 三个执行环境

| 标记 | 执行环境 | 用途 |
| --- | --- | --- |
| **本地电脑** | 已安装 Tailscale 和 Docker Desktop | 管理 Jenkins、访问 GitHub、准备首次部署包 |
| **Jenkins 容器** | Docker Desktop 中的 Jenkins | 从 GitHub 检出代码，分别构建并部署 Web 或 Admin |
| **生产服务器** | `hk.tail5ed8be.ts.net` | 运行 systemd 服务，只接收部署包和 JAR |

除非命令块上方另有说明，变量只在当前 Shell 会话内有效。建议先在本地可信 Shell 中
定义：

```bash
PROD_HOST=hk.tail5ed8be.ts.net
PRODUCTION_OPERATOR=your-operator-user
```

`PRODUCTION_OPERATOR` 不能是 Jenkins 使用的 `wesite-deploy`。生产服务器上的管理命令
应由 root 或具有 sudo 权限的运维账号执行。

### 1.2 应用和发布目录

Web 与 Admin 使用完全独立的不可变发布目录：

```text
/usr/java/apps/web/{current,previous,releases/}
/usr/java/apps/admin/{current,previous,releases/}
```

一个应用部署失败时，只回滚该应用。Web 故障不能触发 Admin 回滚，反之亦然。

### 1.3 不可违反的部署边界

- 所有 SSH 和 SCP 都通过 `hk.tail5ed8be.ts.net`，不使用公网 IP。
- Jenkins 只能以 `wesite-deploy` 登录生产服务器。
- Jenkins 只能执行单应用部署和单应用检查，不能安装基础设施、修改配置、管理
  systemd、执行数据库迁移、回滚或清理旧版本。
- 生产服务器不能接收仓库工作区、`.git`、源代码、Maven 文件或测试。
- 正式环境机密信息只能保存在服务器外部配置或 Jenkins Credentials 中，不能写入
  Git、构建参数、工作区、部署包或构建产物。
- Web 和 Admin 日常发布相互独立，不要求同时部署。

## 2. 首次迁移概览

首次迁移按以下顺序进行：

1. 检查服务器、旧 Java 进程、Redis、Nginx 和交换空间。
2. 在本地从已审查的 Git 提交生成基础设施部署包。
3. 通过 Tailscale 上传三个部署文件并安装 systemd 基础设施。
4. 合并并检查正式环境配置。
5. 配置 Jenkins SSH 凭据、known-hosts 和受限 sudoers。
6. 将当前 Web/Admin JAR 分别保存为可回滚的 baseline。
7. 在维护窗口停用旧 watchdog 和旧启动器。
8. 分别启用两个 baseline，再分别执行首次新版本发布。
9. 检查服务后启用健康检查定时器。

在前一步没有验证通过时，不要继续下一步。

## 3. 首次迁移：服务器前置检查

### 3.1 检查运行环境

**执行位置：生产服务器**

建立维护记录，写明操作人员、UTC 时间、源代码提交以及计划的回滚方案，然后执行：

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
ss -lntp | grep -E '127\.0\.0\.1:(8080|8082)' || true
```

必须确认：

- `/usr/bin/java`、`unzip` 和 `flock` 均可用；
- Nginx 和 Redis 正常；
- 8080、8082 只由预期的旧 JVM 占用；
- 旧启动器和旧 watchdog 的准确归属已经记录。

不要使用大范围结束进程的命令，也不要误停服务器上的其他 Java 应用。

### 3.2 检查交换空间

**执行位置：生产服务器**

```bash
free -h
swapon --show
grep -F '/swapfile none swap sw 0 0' /etc/fstab
df -h /
```

这台服务器有 3.5 GiB 内存，预期应有 2 GiB 持久交换空间。systemd 单元为 Web 最多
预留 768 MiB 堆内存，为 Admin 最多预留 384 MiB，并限制元空间和直接内存。交换
空间只是应急缓冲，不是常规工作内存。

如果交换空间尚未建立，可以在第 4 节安装基础设施后执行：

```bash
sudo /usr/local/sbin/ensure-wesite-swap
free -h
swapon --show
```

`ensure-wesite-swap` 不会覆盖已经存在但未启用的文件。出现这种情况时应停止并人工
检查。如果交换空间的文件、容量、权限、启用状态或 `/etc/fstab` 与预期不一致，必须
先记录原因并取得运维批准；在重新验证 `free -h`、`swapon --show`、`/etc/fstab` 和磁盘
余量之前，不得继续配置或应用迁移。

## 4. 首次迁移：安装基础设施

### 4.1 从 GitHub 代码生成部署包

**执行位置：本地电脑上的可信 Git Bash、WSL 或 Linux Shell**

以下命令会拒绝包含已修改、已暂存或未跟踪文件的工作区，并要求本地 `HEAD` 与最新的
`origin/main` 完全一致。部署包从该提交的独立临时 worktree 生成，避免把当前工作目录
中的其他内容误标记成正式提交：

```bash
(
set -euo pipefail
git fetch --prune origin main

if [[ -n "$(git status --porcelain)" ]]; then
  printf 'Stop: source worktree is not clean.\n' >&2
  exit 1
fi

INFRA_COMMIT="$(git rev-parse --verify 'origin/main^{commit}')"
[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{40,64}$ ]]
[[ "$(git rev-parse --verify 'HEAD^{commit}')" == "$INFRA_COMMIT" ]] || {
  printf 'Stop: HEAD does not match origin/main.\n' >&2
  exit 1
}

INFRA_SOURCE="$(mktemp -d)"
INFRA_OUTPUT="$(mktemp -d)"
cleanup_infra_source() {
  git worktree remove --force "$INFRA_SOURCE" >/dev/null 2>&1 || true
}
trap cleanup_infra_source EXIT

git worktree add --detach "$INFRA_SOURCE" "$INFRA_COMMIT"
"$INFRA_SOURCE/scripts/build-wesite-deployment-bundle.sh" \
  "$INFRA_COMMIT" "$INFRA_OUTPUT"
ls -l "$INFRA_OUTPUT"
(
  cd "$INFRA_OUTPUT"
  sha256sum --check "wesite-deployment-$INFRA_COMMIT.tar.gz.sha256"
)

cleanup_infra_source
trap - EXIT
printf 'Record INFRA_COMMIT=%s\nRecord INFRA_OUTPUT=%s\n' \
  "$INFRA_COMMIT" "$INFRA_OUTPUT"
)
```

输出目录必须恰好包含：

```text
wesite-deployment-<commit>.tar.gz
wesite-deployment-<commit>.tar.gz.sha256
install-wesite-deployment-bundle.sh
```

其中不包含源代码、应用 JAR 或正式环境机密信息。

### 4.2 通过 Tailscale 上传部署包

**执行位置：本地电脑**

从上一节输出和维护记录中重新填写三个值；不要依赖旧 Shell 会话中的变量：

```bash
(
set -euo pipefail
INFRA_COMMIT=0123456789abcdef0123456789abcdef01234567
INFRA_OUTPUT=/absolute/path/to/generated-bundle-directory
PRODUCTION_OPERATOR=your-operator-user
PROD_HOST=hk.tail5ed8be.ts.net

[[ "$INFRA_COMMIT" =~ ^[0-9a-f]{40,64}$ ]]
[[ "$INFRA_OUTPUT" = /* && -d "$INFRA_OUTPUT" ]]
[[ "$PRODUCTION_OPERATOR" != your-operator-user ]]
REMOTE_INFRA="/tmp/wesite-infrastructure-$INFRA_COMMIT"

ssh "$PRODUCTION_OPERATOR@$PROD_HOST" \
  "umask 077 && mkdir -m 0700 -- '$REMOTE_INFRA'"
scp "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz" \
  "$INFRA_OUTPUT/wesite-deployment-$INFRA_COMMIT.tar.gz.sha256" \
  "$INFRA_OUTPUT/install-wesite-deployment-bundle.sh" \
  "$PRODUCTION_OPERATOR@$PROD_HOST:$REMOTE_INFRA/"
)
```

### 4.3 安装并验证 systemd 文件

**执行位置：生产服务器**

将示例提交号替换为刚才记录的 `INFRA_COMMIT`：

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

`/run/lock/wesite` 必须显示为 `root:root 755`。`tmpfiles.d` 会在服务器每次启动时重建
这个目录；如果路径不安全或可被替换，部署、回滚和健康恢复都会拒绝执行。

安装程序只安装命令和单元并重新加载 systemd，不会启动应用或定时器。安装成功后，
只删除这三个明确的临时文件及其空目录，并将压缩包摘要写入维护记录。

## 5. 首次迁移：正式环境配置

**执行位置：生产服务器**

安装程序只在目标文件不存在时创建配置，不会覆盖已有正式配置：

```text
/etc/wesite/wesite.env
/usr/java/config/web/application-prod.properties
/usr/java/config/admin/application-prod.properties
```

逐项对比旧配置并使用 `sudoedit` 合并：

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

注意事项：

- 不能将 `/etc/wesite/wesite.env` 当作 Shell 脚本执行，其中的 JDBC URL 可能包含
  Shell 元字符；
- 保留经过验证的远程 MySQL URL，包括明确的 UTC 连接和会话选项；
- 配置 Redis、JWT、内部博客、邮件和 MaxMind 参数；
- 首次发布时保持通知投递关闭；
- Web 和 Admin 继续只监听回环地址；
- 将三个正式配置文件备份到发布目录之外。

## 6. 配置本地 Docker Jenkins

### 6.1 验证 Jenkins 容器环境

**执行位置：本地电脑的 PowerShell**

先找到 Jenkins 容器名称：

```powershell
docker ps --format "table {{.Names}}\t{{.Image}}\t{{.Status}}"
$JENKINS_CONTAINER = "<Jenkins 容器名称>"
```

验证容器具备构建工具并能解析生产主机：

```powershell
docker exec $JENKINS_CONTAINER sh -lc `
  'java -version && mvn -version && git --version && ssh -V && command -v scp'
docker exec $JENKINS_CONTAINER getent hosts hk.tail5ed8be.ts.net
```

当前架构不要求在 Jenkins 容器中再次安装 Tailscale；容器通过本地电脑的 Tailscale
网络访问 `hk.tail5ed8be.ts.net`。Docker Desktop、Tailscale 或 Jenkins 容器重启后，
应重新执行本节的解析检查和第 6.4 节的 SSH 实测。

### 6.2 创建 Jenkins SSH 凭据

如果尚未创建专用密钥，在仓库目录之外的本地受限目录生成一对新的 Ed25519 密钥。
以下命令在本地电脑 PowerShell 执行，且会拒绝覆盖已有密钥：

```powershell
& {
$ErrorActionPreference = "Stop"
function Assert-NativeSuccess([string]$Step) {
  if ($LASTEXITCODE -ne 0) {
    throw "$Step 失败，退出码：$LASTEXITCODE"
  }
}

$ProductionOperator = "your-operator-user"
if ($ProductionOperator -eq "your-operator-user") { throw "请填写生产运维账号" }

$KeyDirectory = Join-Path $env:USERPROFILE ".ssh\whose-domains-jenkins"
$PrivateKey = Join-Path $KeyDirectory "whose-domains-prod-ssh"
if (Test-Path $KeyDirectory) {
  throw "密钥目录已存在，请先人工检查，拒绝复用：$KeyDirectory"
}
$CurrentIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
New-Item -ItemType Directory -Path $KeyDirectory | Out-Null
icacls $KeyDirectory /inheritance:r /grant:r "$($CurrentIdentity):(OI)(CI)F"
Assert-NativeSuccess "设置密钥目录 ACL"

if ($PSVersionTable.PSVersion -lt [version]"7.3") {
  # Windows PowerShell 5.1 和 PowerShell 7.0-7.2 会丢弃真正的空字符串参数。
  ssh-keygen -t ed25519 -N '""' -f $PrivateKey -C "jenkins@whose-domains"
} else {
  $PSNativeCommandArgumentPassing = "Standard"
  ssh-keygen -t ed25519 -N "" -f $PrivateKey -C "jenkins@whose-domains"
}
Assert-NativeSuccess "ssh-keygen"
icacls $PrivateKey /inheritance:r /grant:r "$($CurrentIdentity):F"
Assert-NativeSuccess "设置私钥 ACL"
ssh-keygen -lf "$PrivateKey.pub"
Assert-NativeSuccess "读取公钥指纹"
scp "$PrivateKey.pub" `
  "${ProductionOperator}@hk.tail5ed8be.ts.net:/tmp/whose-domains-prod-ssh.pub"
Assert-NativeSuccess "scp"
}
```

这是仅供 Jenkins 使用、没有口令的专用密钥，不能复用为人工登录密钥。无口令只用于
支持非交互式 Pipeline；保护边界由 Jenkins Credentials 加密存储、受限账号、固定
known-hosts 和最小 sudoers 共同提供。

**执行位置：生产服务器**

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

在 Jenkins Credentials 中创建：

1. `SSH Username with private key`
   - ID：`whose-domains-prod-ssh`
   - Username：`wesite-deploy`
   - Private Key：刚生成的专用私钥
2. `Secret file`
   - ID：`whose-domains-prod-known-hosts`
   - 内容：经过核对的 `hk.tail5ed8be.ts.net` SSH 主机密钥行

`ssh-keyscan` 只能帮助获取候选主机密钥，不能单独证明其真实性。必须通过服务器控制台
或云服务商渠道核对生产服务器主机密钥指纹，再保存 known-hosts 文件。

确认 Jenkins Credentials 已进入加密备份后，只能将原私钥保存在受控的加密凭据库中，
或者从本地受限目录中安全移除；不得将它移动到仓库、普通下载目录或 Jenkins 工作区。
公钥不是机密，但也不应作为临时文件长期散落。

不要授予 `wesite-deploy` root 登录、交互式 root Shell 或运维人员密钥的访问权限。

### 6.3 启用受限 sudoers

**执行位置：生产服务器**

安装程序只放置未启用的 sudoers 示例。验证后再显式启用：

```bash
sudo visudo -cf /etc/wesite/wesite-deploy.sudoers.example
sudo install -o root -g root -m 0440 \
  /etc/wesite/wesite-deploy.sudoers.example \
  /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers.d/wesite-deploy
sudo visudo -cf /etc/sudoers
sudo -l -U wesite-deploy
```

免密权限只能包括：

- 部署 Web；
- 检查 Web；
- 部署 Admin；
- 检查 Admin。

不能包含基础设施安装、配置修改、systemd 管理、回滚、旧版本清理、任意 root 命令
或交互式 Shell。

### 6.4 从 Jenkins 容器实测 SSH 边界

**执行位置：本地电脑的 Jenkins UI 发起；实际命令在 Jenkins 容器执行**

凭据、known-hosts 和 sudoers 全部配置后，创建一次性 Pipeline 运行以下脚本。它会验证
TCP/22、严格主机密钥、纯密钥非交互登录、远端身份和 sudoers 边界；`getent hosts` 的
DNS 结果不能代替这项测试：

```groovy
pipeline {
  agent any
  stages {
    stage('Verify production SSH boundary') {
      steps {
        withCredentials([
          sshUserPrivateKey(
            credentialsId: 'whose-domains-prod-ssh',
            keyFileVariable: 'SSH_KEY',
            usernameVariable: 'SSH_USER'
          ),
          file(
            credentialsId: 'whose-domains-prod-known-hosts',
            variable: 'KNOWN_HOSTS'
          )
        ]) {
          sh '''#!/usr/bin/env bash
            set -euo pipefail
            test "$SSH_USER" = wesite-deploy
            test -s "$KNOWN_HOSTS"
            ssh -i "$SSH_KEY" \
              -o BatchMode=yes \
              -o ConnectTimeout=10 \
              -o GlobalKnownHostsFile=/dev/null \
              -o IdentitiesOnly=yes \
              -o StrictHostKeyChecking=yes \
              -o UserKnownHostsFile="$KNOWN_HOSTS" \
              "$SSH_USER@hk.tail5ed8be.ts.net" '
                set -euo pipefail
                test "$(id -un)" = wesite-deploy
                test -d /var/lib/wesite-deploy/incoming
                test -w /var/lib/wesite-deploy/incoming
                sudo -n -l
                if sudo -n /usr/bin/true; then
                  printf "Stop: unrestricted sudo is available.\n" >&2
                  exit 1
                fi
              '
          '''
        }
      }
    }
  }
}
```

人工核对 `sudo -n -l` 的输出只能包含 Web/Admin 的部署和检查四类命令。测试通过后删除
这个一次性任务；正式 Jenkinsfile 仍会在每次发布时执行目标应用的实际只读检查。

### 6.5 创建两个 GitHub Pipeline 任务

在 Jenkins 中分别创建两个 `Pipeline script from SCM` 任务。两个任务可以使用同一个
GitHub 仓库和分支，但 Script Path 必须不同：

| 任务 | Script Path | 产物 | 生产服务 |
| --- | --- | --- | --- |
| Web | `deploy/jenkins/wesite-web.Jenkinsfile` | `wesite-web-1.0.0.jar` | `wesite-web.service` |
| Admin | `deploy/jenkins/wesite-admin.Jenkinsfile` | `wesite-admin-1.0.0.jar` | `wesite-admin.service` |

SCM 建议配置：

- Repository URL：Whose.Domains 的 GitHub 仓库地址；
- Credentials：仓库为私有时使用只读 GitHub 凭据；
- Branch Specifier：生产分支，例如 `*/main`；
- Lightweight checkout：只有在插件和 Jenkinsfile 加载验证正常时启用。

两个 Jenkinsfile 都会：

- 禁止同一任务并发执行；
- 分别运行对应模块的 Maven 构建和测试；
- 校验 `GIT_COMMIT` 和 `BUILD_NUMBER`；
- 固定 known-hosts；
- 使用 `wesite-deploy@hk.tail5ed8be.ts.net`；
- 创建权限为 `0700` 的独立暂存目录；
- 只上传自己的 JAR；
- 只部署并检查自己的应用；
- 删除上传的 JAR 和已经为空的暂存目录，并返回真实部署状态。

## 7. 首次迁移：建立 baseline 并切换 systemd

### 7.1 暂存当前正常运行的旧 JAR

**执行位置：生产服务器**

在停止旧 watchdog 和启动器之前，找到当前准确且已知正常的 Web/Admin JAR，记录来源
和 SHA-256。不要猜测路径，也不要混用两个应用的构建产物。

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

如果某个旧 JAR 无法验证，应在维护记录中注明该应用没有初始二进制回滚目标，并改为
备份配置和相关文件系统。

### 7.2 停用旧 watchdog 和启动器

**执行位置：生产服务器维护窗口**

根据第 3 节记录的准确来源，停用并停止旧 watchdog（cron、timer 或 service），然后
停止准确的旧 Java 启动器。不要使用模糊的进程匹配结束其他应用。

```bash
pgrep -af 'java|watchdog|wesite'
systemctl list-units --type=service | grep -Ei 'wesite|watchdog'
systemctl list-timers --all | grep -Ei 'wesite|watchdog'
grep -R "watchdog" /etc/cron.d /etc/cron.daily /etc/systemd/system 2>/dev/null
ss -lntp | grep -E '127\.0\.0\.1:(8080|8082)' || true
```

记录实际停用的 cron 文件、单元、定时器或启动器。移除旧机制只能是一次性人工操作，
不能加入 Jenkins 任务。

### 7.3 分别启用两个 baseline

**执行位置：生产服务器**

以下旧版 HTTP 200 健康检查覆盖参数，只能用于尚未提供 `/api/readyz` 的旧 JAR，且
必须由 root 直接调用：

从第 7.1 节的维护记录重新填写实际 `BASELINE_ID`；不要直接使用示例时间：

```bash
(
set -euo pipefail
BASELINE_ID=20260904T000000Z
[[ "$BASELINE_ID" =~ ^[0-9]{8}T[0-9]{6}Z$ ]]

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
)
```

每条部署命令返回后，只删除对应的 baseline 输入文件和空暂存目录，不能删除不可变的
release 目录。

### 7.4 执行首次新版本发布

**执行位置：本地电脑的 Jenkins UI 发起；实际步骤在 Jenkins 容器执行**

1. 先运行 Web Pipeline，确认构建、上传、部署和 Web 健康检查全部通过。
2. 再运行 Admin Pipeline，确认构建、上传、部署和 Admin 健康检查全部通过。
3. 任一任务失败时立即停止，先调查该应用，不要让另一个应用参与它的回滚。

新版本默认使用严格的 `/api/readyz` 就绪检查，不能继续使用 baseline 的
`legacy-http-200` 覆盖参数。

### 7.5 启用服务和健康检查定时器

**执行位置：生产服务器**

两个首次发布均检查通过后执行：

```bash
sudo systemctl enable --now \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-enabled \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl is-active \
  wesite-web.service wesite-admin.service wesite-health-monitor.timer
systemctl list-timers wesite-health-monitor.timer --no-pager
```

## 8. 日常独立发布

日常发布不再执行基础设施安装、baseline 或旧 watchdog 迁移。

### 8.1 只发布 Web

1. 将需要发布的提交合并并推送到 GitHub 生产分支。
2. 运行 Web Pipeline。
3. Jenkins 只构建 `wesite-web` 及其依赖，只上传 Web JAR。
4. Jenkins 只调用 Web 部署和 Web 检查。
5. 确认 `wesite-web.service` 和公开页面正常。

Admin 不会因此重启或回滚。

### 8.2 只发布 Admin

1. 将需要发布的提交合并并推送到 GitHub 生产分支。
2. 运行 Admin Pipeline。
3. Jenkins 只构建 `wesite-admin` 及其依赖，只上传 Admin JAR。
4. Jenkins 只调用 Admin 部署和 Admin 检查。
5. 确认 `wesite-admin.service` 和后台功能正常。

Web 不会因此重启或回滚。

### 8.3 共享约定变更

Web 与 Admin 可能短时间运行不同提交。因此数据库结构、JWT 声明、Redis 值、内部
API 及其他共享约定的变更，必须对相邻版本同时保持向前和向后兼容。

不兼容变更不能通过两个普通 Jenkins 任务连续发布，必须安排协调维护窗口。

## 9. 发布后检查

### 9.1 服务器内部检查

**执行位置：生产服务器**

```bash
sudo /usr/local/sbin/check-wesite-app web
sudo /usr/local/sbin/check-wesite-app admin
sudo /usr/local/sbin/check-wesite-services
curl --fail --silent --show-error http://127.0.0.1:8080/api/healthz
curl --fail --silent --show-error http://127.0.0.1:8080/api/readyz
curl --fail --silent --show-error http://127.0.0.1:8082/api/readyz
nginx -t
```

### 9.2 外部冒烟和 SEO 检查

**执行位置：本地电脑 PowerShell**

```powershell
pwsh -NoProfile -File scripts/check-production-smoke.ps1 -BaseUrl https://whose.domains
pwsh -NoProfile -File scripts/check-seo.ps1 -BaseUrl https://whose.domains
```

### 9.3 维护期间暂停自动恢复

**执行位置：生产服务器**

有意停止任一应用之前，先停止健康检查定时器和正在执行的监控任务，避免维护操作被
判定为故障：

```bash
sudo systemctl stop wesite-health-monitor.timer wesite-health-monitor.service
```

维护完成并确认应用正常后再恢复定时器：

```bash
sudo systemctl start wesite-health-monitor.timer
```

## 10. 故障处理

### 10.1 手动回滚 Web

**执行位置：生产服务器**

Jenkins 没有回滚权限。Web 回滚必须由运维人员以 root 权限执行：

```bash
sudo /usr/local/sbin/rollback-wesite-app web
sudo /usr/local/sbin/check-wesite-app web
```

### 10.2 手动回滚 Admin

**执行位置：生产服务器**

Admin 回滚必须作为独立决定执行：

```bash
sudo /usr/local/sbin/rollback-wesite-app admin
sudo /usr/local/sbin/check-wesite-app admin
```

回滚会交换指定应用成功版本的 `current` 和 `previous`。如果目标版本未通过其记录的
健康检查约定，脚本会恢复原始链接和版本。

不要因为 Web 回滚而自动回滚 Admin，也不要因为 Admin 回滚而自动回滚 Web。

### 10.3 查看日志

**执行位置：生产服务器**

```bash
journalctl -u wesite-web.service --since '-15 min' --no-pager
journalctl -u wesite-admin.service --since '-15 min' --no-pager
journalctl -u wesite-health-monitor.service --since '-15 min' --no-pager
journalctl -u wesite-web.service -f
journalctl -u wesite-admin.service -f
```

## 11. 清理旧版本

**执行位置：生产服务器**

旧版本清理是单独的人工运维操作，不能加入 Jenkins。

每次只检查一个应用，先解析并记录 `current` 和 `previous`，再列出候选目录：

```bash
sudo realpath -e /usr/java/apps/web/current
sudo realpath -e /usr/java/apps/web/previous
sudo find /usr/java/apps/web/releases -mindepth 1 -maxdepth 1 -type d -print

sudo realpath -e /usr/java/apps/admin/current
sudo realpath -e /usr/java/apps/admin/previous
sudo find /usr/java/apps/admin/releases -mindepth 1 -maxdepth 1 -type d -print
```

清理规则：

- 保留 `current` 和 `previous` 指向的版本；
- 除上述两个版本外，再保留最近至少三个成功版本；
- 故障版本保留到诊断完成；
- 删除前检查候选版本的 `APP`、`VERSION` 和 `STATUS`；
- 确认候选目录没有被任何链接引用；
- 只删除已经完全解析并人工审查过的字面量目录。

示例：

```bash
sudo rm -rf -- /usr/java/apps/web/releases/0123456789abcdef-123
```

必须替换并重新验证示例中的版本身份后才能执行。绝不能删除未经检查的变量、符号链接
目标、应用根目录、共享父目录，也不能使用通配符删除所有版本。

## 12. 博客数据库迁移和内容净化

该流程属于协调维护，不得作为 Web 或 Admin Jenkins Pipeline 的附带操作。

### 12.1 停止应用和监控

**执行位置：生产服务器**

```bash
sudo systemctl stop \
  wesite-health-monitor.timer wesite-health-monitor.service \
  wesite-web.service wesite-admin.service
```

从备份开始到变更应用完成，必须保持常规写入程序停止，并将全部输出保存在变更记录
中。

### 12.2 备份并迁移数据库

**执行位置：包含已审查 SQL 文件的本地可信机器**

使用具有结构变更权限的账号直接连接远程 MySQL。不要将仓库复制到生产服务器：

```bash
mysqldump --single-transaction -h MYSQL_HOST -u DB_USER -p wesitedb \
  WEB_BLOG_POST > web-blog-post-before-editorial-$(date -u +%Y%m%dT%H%M%SZ).sql
sha256sum web-blog-post-before-editorial-*.sql

mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  < doc/alter_blog_editorial_workflow.sql
mysql -h MYSQL_HOST -u DB_USER -p wesitedb \
  -e "SHOW COLUMNS FROM WEB_BLOG_POST LIKE 'CONTENT_UPDATED_AT';"
```

### 12.3 执行内容净化

**执行位置：生产服务器**

将经过审查的 Admin 维护 JAR 复制为 root 所有、运行账号可读的文件。通过临时
systemd 单元运行，确保由 systemd 而不是 Shell 解析环境文件：

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

检查每项拟议变更；数量很大时，检查明确记录的样本。然后使用新的单元名称、
`mode=apply`，并将输出写入单独的 apply 日志后重复执行。以下任一情况都必须停止：

- 进程返回非零状态；
- 没有完成报告；
- dry-run 出现不应删除的合法内容；
- apply 后再次运行 dry-run 仍报告非零变更。

### 12.4 恢复和回滚原则

- 分别发布兼容性匹配的 Web 和 Admin，并分别检查；
- 二进制回滚时保留新增的数据库列；
- 内容回滚只恢复受影响 ID 的原始 `CONTENT` 和 `CONTENT_UPDATED_AT`；
- 不能覆盖其他无关的文章编辑；
- 应用、编辑流程、公开页面、P0 冒烟和 SEO 检查全部通过后，才能恢复服务和定时器。

## 13. 首次迁移检查表

- [ ] 本地电脑、Jenkins 容器和生产服务器的职责已经确认。
- [ ] Jenkins 容器能够解析 `hk.tail5ed8be.ts.net`，并通过严格主机密钥和专用密钥完成 SSH 实测。
- [ ] Java、unzip、flock、Nginx、Redis 和磁盘检查通过。
- [ ] 2 GiB 持久交换空间已启用。
- [ ] 基础设施部署包只有三个预期文件，摘要验证通过。
- [ ] systemd 单元验证通过，`/run/lock/wesite` 为 `root:root 755`。
- [ ] Web/Admin 外部配置已合并，权限正确，没有占位符或 Git 中的机密信息。
- [ ] Jenkins SSH 私钥位于仓库外，公钥和 known-hosts 指纹已经核对，原私钥已受控保管或移除。
- [ ] `wesite-deploy` 的 sudoers 只有四类允许操作。
- [ ] 当前 Web/Admin JAR 已分别建立 baseline。
- [ ] 旧 watchdog 和旧启动器已准确停用。
- [ ] Web baseline 和 Admin baseline 已分别检查通过。
- [ ] Web Pipeline 和 Admin Pipeline 已分别完成首次发布。
- [ ] 服务和健康检查定时器已启用。
- [ ] 服务器内部检查、外部冒烟和 SEO 检查通过。

## 14. 日常发布检查表

- [ ] 目标提交已合并并推送到 GitHub 生产分支。
- [ ] 已确认本次只发布 Web、只发布 Admin，或需要协调维护。
- [ ] 对共享约定的变更兼容相邻版本。
- [ ] 只运行目标应用对应的 Jenkins Pipeline。
- [ ] Jenkins 通过 `hk.tail5ed8be.ts.net` 上传且只上传目标 JAR。
- [ ] 目标应用的部署和健康检查通过。
- [ ] 未发布的另一个应用没有被重启或回滚。
- [ ] 必要时已完成外部冒烟和 SEO 检查。

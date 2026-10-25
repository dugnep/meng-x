# 本地开发直连服务器数据库指南

> 场景：本地 IDEA 跑后端时，连的是**服务器生产库**（唯一数据源）——本地写入的功能卡、聊天记录、法条真题更新，线上立即可见，反之亦然。本地不再跑任何 PostgreSQL。

## 1. 链路架构

```
IDEA 跑的后端进程
  → jdbc:postgresql://127.0.0.1:5432/mengx
    → SSH 隧道（复用 22 端口，走 ~/.ssh/config 的 mengx-tunnel）
      → 服务器 106.53.214.166 内部 PG（127.0.0.1:5432）
```

为什么用隧道而不是公网直开 5432：
- 家宽公网 IP 不固定，端口白名单要频繁改；隧道走 SSH 22 端口，IP 怎么变都无感
- 数据库端口零暴露，不被公网扫描爆破
- 认证走 SSH 密钥，比"挂公网 + 密码硬扛"安全一档

## 2. 首次配置（一次性，换电脑时按此复现）

### 2.1 SSH 免密密钥

先检查本机是否已有密钥对：

```powershell
Test-Path $env:USERPROFILE\.ssh\id_ed25519
```

**没有时先生成**（一次性，git 推 GitHub 也会用到它）：

```powershell
ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\id_ed25519 -N '""'
```

有（或刚生成）之后，传公钥到服务器（输一次密码）：

```powershell
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh ubuntu@106.53.214.166 "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys"
```

验证（不问密码直接回显即成功）：

```powershell
ssh ubuntu@106.53.214.166 "echo ok"
```

### 2.2 隧道配置（~/.ssh/config 追加）

```
Host mengx-tunnel
  HostName 106.53.214.166
  User ubuntu
  LocalForward 5432 127.0.0.1:5432
  ServerAliveInterval 60
  ExitOnForwardFailure yes
```

### 2.3 本地数据库配置（server/src/main/resources/application-local.yml，已 gitignore）

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/mengx
    username: mengx
    password: <服务器 /opt/meng-x/meng-x.env 里的 MENGX_DB_PASSWORD>
```

查密码：

```powershell
ssh ubuntu@106.53.214.166 "sudo grep MENGX_DB_PASSWORD /opt/meng-x/meng-x.env"
```

## 3. 日常开发流程

**第一步：开隧道**（两种方式任选）：

前台窗口（直观，断线可见）——单独开一个 PowerShell 窗口执行，保持开着：

```powershell
ssh -N mengx-tunnel
```

> 执行后**没有输出、光标挂着不动是正常的**——隧道在工作。窗口关闭 = 隧道断开。

后台运行（不占窗口；Windows 的 ssh 不支持 `-f`，用 Start-Process）：

```powershell
Start-Process ssh -ArgumentList '-N','mengx-tunnel' -WindowStyle Hidden
```

> 坑：后台模式断线**不会重连、没有任何提示**。后端报连不上库时先验证隧道，断了重跑一次 Start-Process 即可。

可选：开机自动后台起隧道（计划任务，登录即生效）：

```powershell
schtasks /Create /TN "MengXTunnel" /TR "ssh -N mengx-tunnel" /SC ONLOGON /RL LIMITED /F
```

**第二步：验证隧道**：

```powershell
Test-NetConnection 127.0.0.1 -Port 5432   # TcpTestSucceeded : True 即通
```

**停止后台隧道**（精确匹配 mengx-tunnel 进程，不误杀其他 ssh 连接）：

```powershell
Get-CimInstance Win32_Process -Filter "Name='ssh.exe'" | Where-Object CommandLine -match mengx-tunnel | ForEach-Object { Stop-Process -Id $_.ProcessId }
```

**第三步：IDEA 启动后端**——正常连接生产库。

## 4. IDEA Database 面板连库（浏览数据用，无需手动隧道）

DataSource 配置两个标签页：

- **General**：Host `127.0.0.1`、Port `5432`、Database `mengx`、User `mengx`、Password 同 application-local.yml
- **SSH/SSL**：勾选 Use SSH Tunnel → Proxy Host `106.53.214.166`、User `ubuntu`、Auth type `Key pair`、Private key 选 `~/.ssh/id_ed25519`

IDEA 自行管理隧道连接，浏览数据不需要第一步的手动隧道窗口（但**跑后端代码仍需要**——JDBC 不走 IDEA 面板的 SSH 配置）。

## 5. 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| 启动报 `Unable to determine Dialect without JDBC metadata` | 后端连不上库。九成是隧道窗口没开/已断——重开 `ssh -N mengx-tunnel` 再启动 |
| 隧道窗口一执行就退出（bind: Address already in use） | 本地 5432 被占。查占用：`netstat -ano \| findstr :5432` → `tasklist /fi "PID eq <PID>"`；若是旧 Docker 容器 isor-pg 没停：`docker stop isor-pg` |
| `Test-NetConnection` 通但启动仍报错 | 端口被本地其他进程（而非隧道）监听——看 netstat 输出 PID 归属 |
| IP 变了连不上服务器 | 隧道方案下不应发生；若发生多为网络抖动，重开隧道 |
| 想看线上数据/表结构 | 走第 4 节 IDEA 面板，或 `ssh ubuntu@106.53.214.166 "sudo -u postgres psql mengx"` |

## 6. 信息速查

| 项 | 值/位置 |
|----|---------|
| 服务器 | 腾讯云轻量 4核4G，`106.53.214.166`，Ubuntu 24.04 |
| SSH 登录 | `ssh ubuntu@106.53.214.166`（密钥免密） |
| 数据库密码 | 服务器 `/opt/meng-x/meng-x.env` 的 `MENGX_DB_PASSWORD` |
| 后端服务 | `systemctl status meng-x`（服务器上，jar 在 /opt/meng-x/） |
| 后端日志 | `journalctl -u mengx -f` |
| 本地退役的旧库 | Docker 容器 `isor-pg`（已停用，数据已迁移至服务器，可 docker rm 彻底删除） |

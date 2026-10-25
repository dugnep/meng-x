# 点子铺 · 部署手册

> 适用范围：Web 前端（web/）+ Java 后端（server/）部署到同一台云服务器。
> 小程序（miniprogram/）不参与本流程，其上传见根目录 `npm run upload`。

## 1. 架构总览

```
git push 到 prd/prd 分支（main 为开发分支，推送不触发部署）
      ↓ 自动触发
GitHub Actions（跑在服务器本机的 self-hosted runner 上）
  ├─ mvn package  → meng-x-server.jar
  └─ vite build   → web/dist/
      ↓ 同机拷贝 + systemctl 重启（无跨境传输）
云服务器（4核4G Ubuntu 24.04，构建+运行一体）
  ├─ systemd 守护 java -jar（服务名 meng-x，端口 8080，堆 1G）
  ├─ PostgreSQL 16 + pgvector + pg_trgm（库名 mengx，唯一数据源——本地开发也直连此库）
  └─ Nginx：托管 /var/www/meng-x + /api/ 反代 127.0.0.1:8080
```

密钥与密码管理：
- 数据库密码、JWT 密钥由 `server-setup.sh` 随机生成，写入服务器 `/opt/meng-x/meng-x.env`（权限 600），**不进 git**
- CI 无需任何 SSH 密钥/Secrets：runner 与服务同机，拷贝即部署（旧的 SERVER_HOST/SERVER_USER/SERVER_SSH_KEY Secrets 可删除）

## 2. 本地工具（一次性）

```powershell
winget install Git.Git
winget install Microsoft.OpenJDK.21        # 后端本地开发；用 IntelliJ IDEA 可跳过
winget install PostgreSQL.PostgreSQL.16    # 本地数据库
```

本地建库（与 application.yml 默认值一致）：

```powershell
psql -U postgres -c "CREATE USER mengx WITH PASSWORD 'mengx_local_pwd';"
psql -U postgres -c "CREATE DATABASE mengx OWNER mengx;"
```

## 3. 服务器采购

- 腾讯云轻量应用服务器/CVM，**4核4G + Ubuntu 24.04 LTS**（必须 24.04：pgvector 在源内），带宽 3-5M
- 安全组/防火墙放行：**22（SSH）、80、443**（本地开发经 SSH 隧道连库，5432 不对公网开放，见 3.5 节）
- 域名与 ICP 备案可后置，前期用公网 IP 访问

## 3.5 数据迁移与本地直连（唯一数据源）

本地已有的数据（业务表 + RAG 已向量化的法条/真题）整库迁过去，服务器无需再跑导入管道：

```powershell
# 本地导出（mengx 库，含向量数据；必须用 -f 写文件，PowerShell 的 > 重定向会损坏二进制）
$env:PGPASSWORD='本机postgres密码'; pg_dump -U postgres -Fc -f mengx.dump mengx
scp mengx.dump ubuntu@服务器IP:/tmp/mengx.dump
```

服务器上导入（**必须先跑完 server-setup.sh，且在后端首次启动前**——后端启动会 ddl-auto 建表，先建了表再导入会冲突）：

```bash
sudo -u postgres pg_restore -d mengx --no-owner --role=mengx /tmp/mengx.dump
```

若后端已启动过一次（表已建），改用 `--clean --if-exists` 覆盖导入。

本地开发直连服务器库采用 **SSH 隧道方案**（家宽 IP 不固定、数据库端口不对公网暴露），完整配置与排障见 [LOCAL-DEV.md](./LOCAL-DEV.md)。

## 4. 服务器初始化（一次性，root 执行）

```powershell
scp -r deploy root@服务器IP:/root/deploy
ssh root@服务器IP "bash /root/deploy/server-setup.sh"
```

脚本完成：安装 JDK 17 / Nginx / PostgreSQL → 创建 `mengx` 用户 →
创建数据库与 `/opt/meng-x/meng-x.env`（随机 DB 密码 + JWT 密钥）→
安装 systemd 服务与 Nginx 站点 → 配置部署用户 sudo 免密（仅重启服务）。

## 5. 安装 self-hosted runner 与构建工具链（一次性）

> 背景：GitHub 托管 runner 在境外机房，向国内服务器 scp 上传 jar 需 6 分钟+；
> 改为服务器本机跑 runner，构建+拷贝同机完成，部署约 2-3 分钟。
> 仓库必须为 **private**（public 仓库的 PR 可触发任意 workflow 跑在服务器上）。

### 5.1 安装 runner（ubuntu 用户）

GitHub 仓库 → Settings → Actions → Runners → **New self-hosted runner** → Linux / x64，
按页面给出的命令在服务器执行（版本号与 token 以页面为准，token 一次性有效）：

```bash
mkdir ~/actions-runner && cd ~/actions-runner
curl -o actions-runner-linux-x64-*.tar.gz -L https://github.com/actions/runner/releases/download/v*/actions-runner-linux-x64-*.tar.gz
tar xzf actions-runner-linux-x64-*.tar.gz
./config.sh --url https://github.com/dugnep/meng-x --token <页面上的TOKEN>
# 注册为 systemd 服务：开机自启、崩溃自动拉起
sudo ./svc.sh install ubuntu
sudo ./svc.sh start
```

runner 只需**出站**连接 GitHub（轮询任务队列），服务器无需开放任何入站端口。
`./svc.sh status` 查看状态，`sudo ./svc.sh stop/uninstall` 停用/卸载。

### 5.2 预置构建工具链（ubuntu 用户）

JDK 17 已由 server-setup.sh 安装，补装 git / maven / node 20 并配境内镜像
（否则 Maven/npm 从境外源下载依赖极慢）：

```bash
sudo apt-get update && sudo apt-get install -y git maven
# Node 20（Ubuntu 24.04 自带 apt 版为 18，需 NodeSource 源）
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# Maven 阿里云镜像
mkdir -p ~/.m2
printf '<settings><mirrors><mirror><id>aliyunmaven</id><mirrorOf>*</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > ~/.m2/settings.xml
# npm 淘宝镜像
npm config set registry https://registry.npmmirror.com

java -version && mvn -version && node -v   # 三项均正常即就绪
```

依赖缓存在 runner 本机 `~/.m2` 与 `~/.npm`，天然持久化；
**首次部署**需全量下载依赖（约 5-8 分钟），之后 2-3 分钟。

## 6. 推送代码

```powershell
git init -b main
git add -A
git commit -m "chore: 三端骨架与自动部署链路初始化"
git remote add origin git@github.com:你的用户名/meng-x.git
git push -u origin main
```

此后每次 push 到 **prd/prd 分支**且改动涉及 `server/` 或 `web/`，Actions 在服务器本机自动构建部署（依赖缓存热后约 2-3 分钟生效）；**push 到 main 不部署**（开发分支，随你怎么推）。日常发版流程：main 开发测试 → `git checkout prd/prd && git merge main && git push origin prd/prd`。

## 7. 本地开发

```powershell
# 后端（连服务器库，见 3.5 节 application-local.yml 配置）
cd server; mvn spring-boot:run          # http://localhost:8080

# 前端（/api 自动代理到 8080）
cd web; npm run dev                     # http://localhost:5173
```

本地与服务端共用同一套数据：本地跑导入/更新（法条、真题）直接写入服务器库，两边即刻一致。

## 8. 服务器常用运维命令

```bash
journalctl -u meng-x -f          # 后端实时日志
sudo systemctl restart meng-x    # 重启后端
sudo nginx -t && sudo systemctl reload nginx
sudo -u postgres psql mengx      # 进入数据库
```

## 9. 故障排查

| 现象 | 排查 |
|------|------|
| Actions 任务一直排队（Queued） | runner 离线：服务器 `sudo ./svc.sh status`（在 ~/actions-runner 下）；网页 Settings → Actions → Runners 看 Idle 状态 |
| 构建步骤卡在下载依赖 | 检查 `~/.m2/settings.xml` 阿里云镜像与 `npm config get registry` 是否生效 |
| 网站 502 | `journalctl -u meng-x -n 50` 看后端是否启动；多半是数据库连接失败，检查 `/opt/meng-x/meng-x.env` |
| 接口 401 | 前端 localStorage 的 token 过期（默认 7 天），重新登录 |
| 静态资源 404 | `/var/www/meng-x` 属主是否为 mengx；Nginx 站点配置是否软链到 sites-enabled |

## 10. 域名与 HTTPS（备案后）

1. `deploy/nginx.conf` 的 `server_name` 改为实际域名，同步更新服务器
2. `sudo apt install certbot python3-certbot-nginx && sudo certbot --nginx` 签发免费证书
3. 小程序上线时把域名加入 MP 平台 request 合法域名

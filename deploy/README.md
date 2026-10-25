# 点子铺 · 部署手册

> 适用范围：Web 前端（web/）+ Java 后端（server/）部署到同一台云服务器。
> 小程序（miniprogram/）不参与本流程，其上传见根目录 `npm run upload`。

## 1. 架构总览

```
git push 到 prd/prd 分支（main 为开发分支，推送不触发部署）
      ↓ 自动触发
GitHub Actions（构建，服务器不参与构建）
  ├─ mvn package  → meng-x-server.jar
  └─ vite build   → web/dist/
      ↓ scp 上传 + ssh 重启
云服务器（4核4G Ubuntu 24.04，只运行产物）
  ├─ systemd 守护 java -jar（服务名 meng-x，端口 8080，堆 1G）
  ├─ PostgreSQL 16 + pgvector + pg_trgm（库名 mengx，唯一数据源——本地开发也直连此库）
  └─ Nginx：托管 /var/www/meng-x + /api/ 反代 127.0.0.1:8080
```

密钥与密码管理：
- 数据库密码、JWT 密钥由 `server-setup.sh` 随机生成，写入服务器 `/opt/meng-x/meng-x.env`（权限 600），**不进 git**
- CI 部署用的 SSH 私钥存放在 GitHub Secrets，**不进 git**

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

## 5. 配置 CI 部署密钥（一次性）

本地生成密钥对：

```powershell
ssh-keygen -t ed25519 -f $env:USERPROFILE\.ssh\meng-x-deploy -N '""'
```

把 `meng-x-deploy.pub` 内容追加到服务器 `/home/mengx/.ssh/authorized_keys`
（注意属主 `mengx:mengx`，目录 700、文件 600）。

GitHub 仓库 → Settings → Secrets and variables → Actions，添加：

| Secret | 值 |
|--------|-----|
| `SERVER_HOST` | 服务器公网 IP |
| `SERVER_USER` | `mengx` |
| `SERVER_SSH_KEY` | `meng-x-deploy` **私钥**全文 |

## 6. 推送代码

```powershell
git init -b main
git add -A
git commit -m "chore: 三端骨架与自动部署链路初始化"
git remote add origin git@github.com:你的用户名/meng-x.git
git push -u origin main
```

此后每次 push 到 **prd/prd 分支**且改动涉及 `server/` 或 `web/`，Actions 自动构建部署，约 3-5 分钟生效；**push 到 main 不部署**（开发分支，随你怎么推）。日常发版流程：main 开发测试 → `git checkout prd/prd && git merge main && git push origin prd/prd`。

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
| Actions 部署失败，SSH 超时 | 安全组是否放行 22；Secrets 三个值是否正确（私钥要完整含头尾行） |
| 网站 502 | `journalctl -u meng-x -n 50` 看后端是否启动；多半是数据库连接失败，检查 `/opt/meng-x/meng-x.env` |
| 接口 401 | 前端 localStorage 的 token 过期（默认 7 天），重新登录 |
| 静态资源 404 | `/var/www/meng-x` 属主是否为 mengx；Nginx 站点配置是否软链到 sites-enabled |

## 10. 域名与 HTTPS（备案后）

1. `deploy/nginx.conf` 的 `server_name` 改为实际域名，同步更新服务器
2. `sudo apt install certbot python3-certbot-nginx && sudo certbot --nginx` 签发免费证书
3. 小程序上线时把域名加入 MP 平台 request 合法域名

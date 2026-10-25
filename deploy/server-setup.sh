#!/usr/bin/env bash
# 点子铺云服务器初始化脚本（Ubuntu 22.04 / 24.04）
# 用法: 以 root 身份在服务器上执行一次: bash server-setup.sh
# 完成后服务器即具备运行条件，之后所有部署由 GitHub Actions 自动完成。
set -euo pipefail

DEPLOY_USER=mengx
REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE=/opt/meng-x/meng-x.env

echo "==> [1/8] 安装 JDK 17 / Nginx / PostgreSQL（pgvector 向量扩展 + pg_trgm 为 RAG 检索必需，需 Ubuntu 24.04）"
apt-get update
apt-get install -y openjdk-17-jre-headless nginx postgresql postgresql-16-pgvector postgresql-contrib

echo "==> [2/8] 创建部署/运行用户: $DEPLOY_USER"
if ! id "$DEPLOY_USER" &>/dev/null; then
  useradd -m -s /bin/bash "$DEPLOY_USER"
fi

echo "==> [3/8] 创建应用目录"
mkdir -p /opt/meng-x /var/www/meng-x
chown -R "$DEPLOY_USER:$DEPLOY_USER" /opt/meng-x /var/www/meng-x

echo "==> [4/8] 初始化数据库与密钥配置 $ENV_FILE"
if [ -f "$ENV_FILE" ]; then
  echo "    已存在，跳过（重复执行不会覆盖现有密钥）"
else
  DB_PASSWORD="$(openssl rand -hex 16)"
  JWT_SECRET="$(openssl rand -hex 32)"
  sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
CREATE USER mengx WITH PASSWORD '$DB_PASSWORD';
CREATE DATABASE mengx OWNER mengx;
SQL
  # RAG 向量列与 trgm 检索依赖的扩展（幂等，重跑安全）
  sudo -u postgres psql -v ON_ERROR_STOP=1 -d mengx <<SQL
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL
  cat > "$ENV_FILE" <<ENVEOF
MENGX_DB_URL=jdbc:postgresql://127.0.0.1:5432/mengx
MENGX_DB_USER=mengx
MENGX_DB_PASSWORD=$DB_PASSWORD
MENGX_JWT_SECRET=$JWT_SECRET
# ===== 以下键手动填入后 systemctl restart meng-x 生效 =====
# 智谱 GLM Key:
# MENGX_LLM_API_KEY=replace-me
# 千问 embedding Key（RAG 向量化）:
# MENGX_EMBEDDING_API_KEY=replace-me
# 法条数据目录（LawRefBook/Laws 结构，上传后填实际路径）:
# MENGX_LAW_DATA_DIR=/opt/meng-x/data/laws
# 历年真题目录（上传后填实际路径）:
# MENGX_LAW_QUESTION_DIR=/opt/meng-x/data/questions
ENVEOF
  chown "$DEPLOY_USER:$DEPLOY_USER" "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  echo "    数据库 mengx 已创建，密钥已随机生成并写入 $ENV_FILE"
fi

echo "==> [5/8] 安装 systemd 服务"
cp "$REPO_DIR/meng-x.service" /etc/systemd/system/meng-x.service
systemctl daemon-reload
systemctl enable meng-x
# 注意: jar 还没上传，首次部署后服务才会启动成功

echo "==> [6/8] 安装 Nginx 站点配置"
cp "$REPO_DIR/nginx.conf" /etc/nginx/sites-available/meng-x
ln -sf /etc/nginx/sites-available/meng-x /etc/nginx/sites-enabled/meng-x
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx

echo "==> [7/8] 配置部署用户 sudo 免密（仅允许重启 meng-x 服务）"
echo "$DEPLOY_USER ALL=(ALL) NOPASSWD: /usr/bin/systemctl restart meng-x, /usr/bin/systemctl status meng-x" \
  > /etc/sudoers.d/meng-x
chmod 440 /etc/sudoers.d/meng-x

echo "==> [8/8] 防火墙放行 80/443（云控制台安全组也需同步放行）"
ufw allow 'Nginx Full' 2>/dev/null || true

cat <<EOF

初始化完成。还差两步服务器就绪：

1. 写入 CI 部署公钥（在本地生成密钥对后，把 .pub 内容追加到）:
     /home/$DEPLOY_USER/.ssh/authorized_keys
   注意属主与权限:
     chown -R $DEPLOY_USER:$DEPLOY_USER /home/$DEPLOY_USER/.ssh
     chmod 700 /home/$DEPLOY_USER/.ssh && chmod 600 /home/$DEPLOY_USER/.ssh/authorized_keys

2. 首次 GitHub Actions 部署成功后:
     systemctl status meng-x   # 确认后端已运行
     journalctl -u meng-x -f   # 查看实时日志
EOF

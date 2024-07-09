#!/bin/bash

# スクリプトがエラーで中断するようにする
set -e

# 必要なパッケージをインストール
echo "Updating the system and installing required packages..."
sudo dnf -y update
sudo dnf -y install gcc-c++ make git

# Node.jsのインストール（最新のバージョン）
echo "Installing the latest Node.js using NodeSource..."
curl -fsSL https://rpm.nodesource.com/setup_21.x | sudo bash -
sudo dnf -y install nodejs

# Node.jsとnpmのバージョン確認
echo "Node.js version:"
node -v
echo "npm version:"
npm -v

# スクリプトが存在するディレクトリを取得
APP_DIR=$(dirname "$(readlink -f "$0")")

# 環境変数ファイルを生成
echo "Creating .env file..."
cat << EOF > "$APP_DIR/.env"
ZOOM_WEBHOOK_SECRET_TOKEN=8D2w4n0BQQm_7h3doctzrg
EOF

# npm依存関係をインストール
echo "Installing npm dependencies..."
cd "$APP_DIR"
npm install

# ログディレクトリの作成
echo "Setting up log directory..."
sudo mkdir -p /var/log/zoom/logs
sudo chown -R $USER:$USER /var/log/zoom/logs

# systemdサービスユニットファイルの作成または再作成
SERVICE_FILE="/etc/systemd/system/zoom-webhook.service"
if [ -f "$SERVICE_FILE" ]; then
    echo "Removing existing systemd service unit file..."
    sudo rm "$SERVICE_FILE"
fi

echo "Creating systemd service unit file..."
sudo bash -c "cat << EOF > /etc/systemd/system/zoom-webhook.service
[Unit]
Description=Zoom Webhook Node.js Application
After=network.target

[Service]
Environment=NODE_PORT=4000
WorkingDirectory=$APP_DIR
ExecStart=/usr/bin/npm start
Restart=always
RestartSec=10
User=honoka
Group=honoka
Environment=PATH=/usr/bin:/usr/local/bin
Environment=NODE_ENV=production
PermissionsStartOnly=true
ExecStartPre=/bin/mkdir -p /var/log/zoom/logs
ExecStartPre=/bin/chown -R honoka:honoka /var/log/zoom/logs

[Install]
WantedBy=multi-user.target
EOF"

# systemdデーモンをリロードしてサービスを有効化および起動
echo "Reloading systemd daemon and starting the service..."
sudo systemctl daemon-reload
sudo systemctl enable zoom-webhook.service
sudo systemctl start zoom-webhook.service

# サービスの状態を確認
sudo systemctl status zoom-webhook.service

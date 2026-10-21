#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER="${OMS_SERVER:?请显式设置 OMS_SERVER=user@your-server}"
REMOTE_DIR="${OMS_REMOTE_DIR:-/opt/oms}"
REMOTE_PACKAGE="/root/oms-code-update.tar.gz"
LOCAL_PACKAGE="$ROOT_DIR/outputs/deploy/oms-code-update.tar.gz"
PACKAGE_DIR="$ROOT_DIR/outputs/deploy/package"

echo "==> Building backend"
(cd "$ROOT_DIR/backend" && mvn -q -DskipTests package)

echo "==> Building frontend"
(cd "$ROOT_DIR/frontend" && npm run build)

echo "==> Packaging code update"
rm -rf "$PACKAGE_DIR/frontend"
mkdir -p "$PACKAGE_DIR"
cp "$ROOT_DIR/backend/target/internal-oms.jar" "$PACKAGE_DIR/app.jar"
cp -R "$ROOT_DIR/frontend/dist" "$PACKAGE_DIR/frontend"
tar -czf "$LOCAL_PACKAGE" -C "$PACKAGE_DIR" app.jar frontend
ls -lh "$LOCAL_PACKAGE"

echo "==> Uploading to $SERVER"
rsync -avP "$LOCAL_PACKAGE" "$SERVER:$REMOTE_PACKAGE"

echo "==> Applying update on server"
ssh "$SERVER" "REMOTE_DIR='$REMOTE_DIR' REMOTE_PACKAGE='$REMOTE_PACKAGE' bash -s" <<'REMOTE_SCRIPT'
set -euo pipefail

mkdir -p /opt/oms-backups
if [ -d "$REMOTE_DIR" ]; then
  tar -czf "/opt/oms-backups/oms-code-before-update-$(date +%Y%m%d-%H%M%S).tar.gz" -C "$REMOTE_DIR" app.jar frontend 2>/dev/null || true
fi

systemctl stop oms-backend
tar -xzf "$REMOTE_PACKAGE" -C "$REMOTE_DIR"
systemctl start oms-backend
systemctl reload nginx

ready=0
for i in $(seq 1 150); do
  http_code="$(curl --max-time 5 -sS -o /tmp/oms-health.out -w "%{http_code}" http://127.0.0.1:8081/api/auth/me 2>/tmp/oms-health.err || true)"
  if [ "$http_code" = "401" ]; then
    ready=1
    break
  fi
  sleep 1
done
if [ "$ready" -ne 1 ]; then
  echo "Backend did not become ready within the startup window." >&2
  exit 1
fi

systemctl status oms-backend --no-pager
nginx -t
echo "Server update finished."
REMOTE_SCRIPT

echo "==> Done"
echo "Target: $SERVER"

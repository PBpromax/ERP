# 构建与部署

日常开发按 README 本地启动。以下为部署参考，远程操作前需另行获得明确授权。仓库上传不会触发自动部署。

## 构建

```bash
cd backend
mvn -Dspring.profiles.active=test test
mvn -DskipTests package
cd ../frontend
npm ci
npm run build
```

部署后端 Jar 与前端 dist，配置由运行环境独立提供。不要打包 `.env`、数据库备份、uploads、日志或 outputs。

## 数据库与首次启动

推荐 Java 17、PostgreSQL 16、Nginx。数据库账号仅授予目标业务库所需权限。

`prod` 使用 `ddl-auto=validate`，不会创建空库结构。首次安装时，可在受控、空白的 PostgreSQL 库中以 `postgres` profile 启动一次，让 Hibernate 建立当前实体结构并初始化系统账号，再停止该进程；核实结构后切换 `prod`。此操作会写入目标数据库，只应由安装负责人针对新库执行。已有业务库需先备份、核对迁移脚本，禁止直接套用新库步骤。

```bash
# 环境变量由操作系统或服务管理器事先导出
java -jar target/internal-oms.jar --spring.profiles.active=prod
```

必需配置：数据库连接变量、`OMS_TOKEN_SECRET`、可写的 `OMS_UPLOAD_DIR`，以及空库首次创建管理员时的 `OMS_BOOTSTRAP_ADMIN_PASSWORD`。服务管理器可通过权限受限的环境文件注入，详见[配置说明](CONFIGURATION.md)。

备份应同时覆盖 PostgreSQL 与附件目录，并定期验证恢复。源码仓库不承担业务备份功能。Redis 与 MinIO 当前是可选服务，核心流程使用本地附件目录。

## Nginx 与访问路径

主机配置示例为 `docs/nginx-oms.conf`：前端 `/opt/oms/frontend`，后端 `127.0.0.1:8081`，API 保留 `/api` 前缀。部署前调整路径、域名、HTTPS 和上游地址。哈希资源长期缓存，HTML 禁用缓存，上传限制与后端相容。

前端容器使用 `frontend/nginx.conf`，上游为 Docker 网络中的 `backend:8081`。需自行提供名为 `backend` 的服务/网络别名；根目录 Compose 仅包含本地依赖，不是完整应用编排。

## Docker 构建参考

```bash
# 先生成 backend/target/internal-oms.jar
docker build -t internal-oms-backend ./backend
docker build -t internal-oms-frontend ./frontend
```

数据库与附件需要独立持久化，密钥在运行时注入。`VITE_*` 前端配置在构建时固化，用户可读取，必须保持为公开配置。

## 服务器更新脚本

`scripts/deploy-code.sh` 是显式远程操作工具，要求设置 `OMS_SERVER=user@your-server`，无内置服务器地址。它会构建、打包、rsync 上传，通过 SSH 备份旧代码、重启 `oms-backend` 并重载 Nginx。脚本假定 `/opt/oms`、systemd 服务和权限已由管理员配置，不包含数据库迁移或恢复。

仅在获得目标服务器发布授权后执行，本地验证不运行该脚本。上线前还需核对结构兼容、实际打印收款资料、HTTPS、备份恢复与服务账号权限。

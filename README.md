# ERP · 公司内部订单管理系统

前后端分离的企业业务管理系统，覆盖客户与产品、报价、销售订单、发货、库存、生产、采购、财务对账与收款、质检附件、账号权限和操作日志。

此仓库保存脱敏后的源码与配置示例。数据库文件、业务导入表、客户与员工记录、企业产品及原料清单、附件、备份、运行日志、真实密钥和旧 Git 历史均未发布。打印模板保留结构，联系及收款资料使用空白字段或示例占位。测试数据为隔离测试夹具。

## 技术栈与环境

| 层级 | 技术 / 要求 |
| --- | --- |
| 后端 | JDK 17、Maven 3.8+、Spring Boot 3.3.5、Spring Security、JPA |
| 前端 | Node.js 22.6+（建议 22 LTS）、npm、Vue 3、TypeScript、Vite、Element Plus、Pinia、ECharts |
| 数据库 | PostgreSQL 16；临时本地开发与自动化测试支持 H2 |
| 导出 | Apache POI、PDFBox、ZXing |
| 文件 | 本地附件目录；Redis / MinIO 是可选依赖示例，核心流程不依赖它们 |

## 快速开始

### 1. 获取代码并配置环境

```bash
git clone https://github.com/PBpromax/ERP.git
cd ERP
cp .env.example .env
```

编辑 `.env`，自行设置 `OMS_DB_PASSWORD`、`OMS_TOKEN_SECRET`、`OMS_BOOTSTRAP_ADMIN_PASSWORD`。可用 `openssl rand -hex 32` 生成随机值，三项使用不同的值。管理员密码至少 12 个字符且 UTF-8 长度不超过 72 字节。

`.env` 不会被 Java、Maven 或 Vite 自动读取。每个启动后端或前端的终端都在仓库根目录执行：

```bash
set -a
source .env
set +a
```

以上适用于 Bash / Zsh。值包含空格或 shell 特殊字符时必须加引号；只加载自己维护的配置文件。Docker Compose 自动读取根目录 `.env`。

### 2. PostgreSQL 与后端

```bash
docker compose up -d postgres
docker compose ps
cd backend
mvn spring-boot:run
```

也可使用已有的本地 PostgreSQL，调整 `OMS_DB_URL` / `OMS_DB_USERNAME`。默认 `postgres` profile 使用 Hibernate `ddl-auto=update` 创建空库结构。第一次启动只创建系统角色、权限和配置的管理员，不会还原企业业务记录；已有用户时不重置管理员密码。

API：`http://localhost:8081/api`。接口文档：`http://localhost:8081/api/swagger-ui.html`。

### 3. 前端

另开终端，从仓库根目录加载 `.env` 后执行：

```bash
cd frontend
cp .env.example .env.local
npm ci
npm run dev
```

访问 `http://localhost:5173`，使用 `.env` 中配置的管理员账户与密码登录。前端 `/api` 默认代理至 `127.0.0.1:8081`；更换端口时设置 `OMS_DEV_API_ORIGIN`。

### 不安装 PostgreSQL 的本地体验

加载根目录 `.env` 后，将后端启动命令改为：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`dev` profile 在当前目录的 `data/` 生成全新的 H2 文件，仅供本地开发。令牌密钥和首次管理员密码仍需配置。不要把数据目录加入 Git。

## 验证与构建

```bash
# 仓库根目录：检查暂存内容是否包含禁止上传的文件或明显密钥
python3 scripts/check-repository.py

# 后端：测试使用内存 H2 与明确标识的虚构凭据
cd backend
mvn -Dspring.profiles.active=test test
mvn -DskipTests package

# 前端：在另一个终端执行
cd frontend
npm ci
npm run test:money
npm run test:pagination
npm run build
```

后端产物为 `backend/target/internal-oms.jar`，前端产物为 `frontend/dist/`。构建本身不会上传服务器。

## 目录

```text
backend/src/main/java/       业务、接口、权限及持久化代码
backend/src/main/resources/  Spring 配置与脱敏打印模板
backend/src/test/            自动化测试与虚构夹具
frontend/src/               页面、路由、状态管理、产品编码与金额逻辑
frontend/tests/             金额、请求并发、分页测试
sql/                        历史结构与迁移参考，非完整初始化包
docs/                       配置、数据库、接口、部署与权限说明
scripts/                    提交检查与显式服务器部署脚本
SYSTEM_MANUAL.md             系统使用说明
.env.example                后端与本地依赖配置模板
docker-compose.yml          本地 PostgreSQL 及可选依赖
```

## 文档

- [环境变量与配置](docs/CONFIGURATION.md)：profile、数据库、密钥、OCR、附件、端口与初始化。
- [部署说明](docs/DEPLOYMENT.md)：构建产物、空库初始化、Nginx、Docker 与持久化。
- [数据库](docs/DATABASE.md)、[接口](docs/API.md)、[权限规则](docs/USER_PERMISSIONS.md)。
- [功能覆盖](docs/FEATURE_COVERAGE.md)、[业务用例](docs/USE_CASES.md)、[使用手册](SYSTEM_MANUAL.md)。
- [协作与提交](CONTRIBUTING.md)、[敏感信息管理](SECURITY.md)。

`ARK_API_KEY` 与 `ARK_MODEL` 仅用于可选的外部识别功能，必须配置在后端。所有 `VITE_*` 值都会进入浏览器，不能用于存放密钥。首次业务使用前需自行建立客户、产品、原料和员工账号，并在私有环境配置实际打印收款资料。

## 发布与使用权限

推送到 GitHub 只用于保存源码。本仓库未配置自动部署工作流；服务器部署需要单独授权并显式提供目标地址。`scripts/deploy-code.sh` 会上传代码并重启服务，日常本地开发不应执行。

本仓库未授予开源许可证，使用与分发权限由仓库所有者决定。

# 环境变量与配置

后端默认端口 `8081`，上下文 `/api`，时区 `Asia/Shanghai`。根目录 `.env.example` 是模板，真实 `.env` 已被忽略。Spring Boot 使用进程环境变量；Compose 自动读取 `.env`；Vite 读取 `frontend/.env.local`，开发代理读取启动进程的 `OMS_DEV_API_ORIGIN`。

## 常用变量

| 变量 | 用途 | 默认 / 要求 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `postgres` / `dev` / `prod` / `test` | 默认 `postgres` |
| `OMS_DB_URL` | PostgreSQL JDBC 地址 | `jdbc:postgresql://127.0.0.1:5432/internal_oms` |
| `OMS_DB_USERNAME` | 数据库用户 | `oms` |
| `OMS_DB_PASSWORD` | PostgreSQL 密码 | postgres / prod 必填 |
| `OMS_TOKEN_SECRET` | 登录令牌 HMAC 密钥 | 运行环境必填，建议独立随机 32 字节以上 |
| `OMS_BOOTSTRAP_ADMIN_USERNAME` | 空库首次管理员账户 | `admin` |
| `OMS_BOOTSTRAP_ADMIN_NAME` | 管理员显示名 | `系统管理员` |
| `OMS_BOOTSTRAP_ADMIN_PASSWORD` | 首次管理员密码 | 至少 12 字符，UTF-8 不超过 72 字节；已有用户时不重置 |
| `OMS_UPLOAD_DIR` | 附件目录 | 通用 `./uploads`；prod 默认 `/data/oms/uploads` |
| `SERVER_PORT` | Spring 标准端口覆盖 | 默认 `8081` |
| `OMS_DEV_API_ORIGIN` | Vite 开发代理后端 | `http://127.0.0.1:8081` |
| `VITE_API_BASE_URL` | 浏览器 API 根路径 | `/api`，设在前端环境文件中 |
| `ARK_API_KEY` | 外部识别 API Key | 可选，后端私密配置 |
| `ARK_MODEL` | 自己账户可用的模型 / 接入点 ID | 与 API Key 配对配置 |
| `OMS_SLOW_REQUEST_MS` | 慢请求阈值 | `100` ms |
| `MINIO_ROOT_USER` | 可选 MinIO 管理员 | `omsadmin` |
| `MINIO_ROOT_PASSWORD` | 可选 MinIO 密码 | 启动 extras 前填写，至少 8 字符 |

修改容器环境中的密码不会修改已有 PostgreSQL 卷内的数据库用户密码，需要管理员单独管理已有实例。

## Profile 与数据库

| Profile | 数据库 | 表结构策略 | 用途 |
| --- | --- | --- | --- |
| `postgres` | PostgreSQL | `update` | 本地开发与新空库结构准备 |
| `dev` | `./data/oms` H2 文件 | `update` | 本地单机开发 |
| `prod` | PostgreSQL | `validate` | 验证已有结构，缺表时拒绝启动 |
| `test` | 内存 H2 | `create-drop` | 自动化测试，数据不保留 |

H2 兼容模式不能替代 PostgreSQL 验收。`sql/schema-mysql.sql` 是旧版结构参考，不能初始化当前 PostgreSQL。迁移 SQL 是基于已有库状态的历史片段，不应对空库按文件名全量执行。

业务产品及原料目录已清空：`productSeedData.ts`、`ProductSeedRunner` 不含企业产品记录，运行资源 `material-catalog.json` 为 `[]`。测试资源同名文件含虚构原料，用于验证初始化不会覆盖用户修改；不会进入正式 Jar。产品分类及编码规则仍保留。企业旧员工账号初始化工具未发布，员工改为通过用户管理创建。

## OCR 与导出模板

配置 `ARK_API_KEY` 与 `ARK_MODEL` 后，远程识别会把提交的图片/文字发送至代码中指定的服务端点。未配置时可使用本地开票文字解析，需要远程识别时会提示缺少配置。示例不包含可用密钥或企业专属推理端点 ID。

Excel 模板位于 `backend/src/main/resources/templates/`，用于报价、订单、送货、对账导出。真实联系人、客户地址、收款银行与账户已清空/替换；需在企业私有环境配置。保留单元格位置、合并区域和公式，否则可能影响导出代码的坐标写入。

## 历史导入

`OMS_LEGACY_BACKFILLS_ENABLED`、`OMS_MIGRATE_H2` 默认 `false`。`oms.history-import.*`、`oms.customer-ledger-import.*` 默认关闭，依赖私有计划文件，本仓库不提供业务明细。不要在日常启动时启用这些修复或导入功能。

## 常见问题

- 无法解析密钥或数据库密码：确认启动终端已经导出环境变量；仅复制 `.env` 不会让 Java 自动加载。
- 空库提示管理员密码缺失：填写并导出 `OMS_BOOTSTRAP_ADMIN_PASSWORD`。
- 前端 502：核实后端已启动，且 `OMS_DEV_API_ORIGIN` 与实际端口一致。
- 登录 401：核实账户状态与密码；更换令牌密钥后旧令牌失效。
- prod 缺表：先在受控空库准备结构，再用 `validate` 启动，参见部署说明。
- 附件失败：检查运行用户对 `OMS_UPLOAD_DIR` 的访问权限，并持久化该目录。

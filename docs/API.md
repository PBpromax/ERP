# API 说明

统一前缀：`/api`

统一返回：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

## 认证

- `POST /api/auth/login`
- `GET /api/auth/me`
- `GET /api/auth/field-permissions`

## 主数据

- `/api/master/customers`
- `/api/master/products`
- `/api/master/materials`
- `/api/master/suppliers`
- `/api/master/boms`
- `/api/master/bom-items`

## 销售

- `/api/sales/quotations`
- `POST /api/sales/quotations/with-items`
- `POST /api/sales/quotations/{id}/send`
- `POST /api/sales/quotations/{id}/convert`
- `/api/sales/orders`
- `POST /api/sales/orders/with-items`
- `POST /api/sales/orders/{id}/approve`
- `GET /api/sales/orders/{id}/detail`
- `/api/sales/deliveries`
- `POST /api/sales/deliveries/from-order/{orderId}`
- `POST /api/sales/deliveries/{id}/confirm`

## 库存

- `/api/inventory/product-stocks`
- `/api/inventory/material-stocks`
- `/api/inventory/transactions`
- `POST /api/inventory/product-stocks`
- `POST /api/inventory/material-stocks`
- `POST /api/inventory/transactions`
- `POST /api/inventory/product-stocks/{productId}/adjust`
- `POST /api/inventory/material-stocks/{materialId}/adjust`

## 生产采购

- `/api/production/plans`
- `POST /api/production/plans/{id}/finish`
- `/api/purchase/requests`
- `POST /api/purchase/requests/from-warning`

## 财务

- `/api/finance/statements`
- `POST /api/finance/statements/generate?month=2026-04`
- `/api/finance/invoices`
- `/api/finance/receipts`

## 质检附件

- `/api/quality/reports`
- `/api/files/attachments`
- `POST /api/files/upload`

## 消息报表

- `/api/notify`
- `POST /api/notify/{id}/read`
- `/api/reports/dashboard`

## 系统管理

- `/api/system/users`
- `/api/system/roles`
- `/api/system/permissions`
- `/api/system/audit-logs`

## 采购原料

权限：`materials`。接口根路径 `/api/master/materials`，响应沿用 `ApiResponse`。

- `GET /master/materials`：材料列表，附当前数量、今日三类数量、是否待完善及预警状态。
- `GET /master/materials/{id}`：单项资料和最新版本号。
- `POST /master/materials`：新增；必填分类、名称、单位、`initialQty`、`safetyStock`。
- `PUT /master/materials/{id}`：编辑；提交读取时的 `versionNo`、`stockVersion`。尚无库存时填写 `initialQty`；已有库存时不传该字段。单位允许更正，包括已有数量记录的原料；仅更正单位，不自动换算库存、预警值、每日记录及流水数量。
- `DELETE /master/materials/{id}`：仅可删除无数量记录的材料；有记录时通过编辑设置 `enabled=false` 停用。
- `GET /master/materials/{id}/daily-records/{date}`：所选业务日期的记录，无记录返回 `data=null`。
- `PUT /master/materials/{id}/daily-records/{date}`：提交当日总量 `purchaseQty`、`productionQty`、`lossQty`、备注和读取时的 `stockVersion`、每日记录 `versionNo`（新记录为 `-1`）。系统按差额更新库存，同内容重试不重复扣减。禁止未来日期。
- `GET /master/materials/{id}/daily-records`：分页每日记录。
- `GET /master/materials/{id}/ledger`：分页流水，按登记时间、ID倒序；历史补录的业务日期与登记时间分别保留。
- `GET /master/materials/{id}/trend`：上海时区含今天的30个自然日，返回每日生产使用、损耗及总消耗，缺失日期补零。
- `POST /master/materials/{id}/adjustments`：提交 `targetQty`、必填 `reason`、`stockVersion`，写入库存调整流水。
- `GET /reports/material-warnings`：权限 `dashboard`，独立实时读取已完善且启用材料，当前数量小于或等于预警值时提醒采购。

分页参数：`page`（默认1）、`size`（默认25，最多100）、可选业务日期范围 `start`/`end`。返回 `{items,total,page,size,totalPages}`。数量为非负数，最多三位小数；所有变动必须满足当前库存和锁定库存校验。并发版本冲突返回409，提示刷新重试。流水中 `materialMovementType` 区分初始数量、采购、生产使用、损耗、调整和每日登记；`qty` 为有符号库存变化，`MATERIAL_DAILY_CORRECTION` 标记更正。旧流水缺少业务日期时，界面和日期筛选使用登记日期。

采购量指实际入库量，生产使用和损耗由本栏目手工登记；不关联采购付款或自动生产扣料。流水永久保留，图表仅展示最近30日。

## 金额与单价精度

金额和单价按实际十进制精度计算、保存，不在中间步骤按两位或三位小数舍入。展示保留千位分隔符并去掉小数末尾多余的零：`100.000 → 100`、`100.500 → 100.5`、`1.2346 → 1.2346`。数量仍使用各业务原有精度规则。PostgreSQL 使用不限定小数位的 `numeric`，升级执行 `sql/migrations/20261015_exact_money.sql`；迁移不重算历史金额。


## 原料库存扫码出入库

- `POST /master/materials/{id}/scan-code`：返回该原料的固定二维码token，首次自动生成，之后始终复用；不提供更换接口。需要原料库存菜单权限。
- `GET /material-entry/{token}`：需要登录且具有入库或出库登记权限，只返回原料名称和计量单位；停用或资料不完整时拒绝登记。
- `POST /material-entry/{token}/entries`：需要登录且具有入库或出库登记权限，JSON字段为 `requestId`（UUID）、`direction`（IN或OUT）、`qty`（大于0且最多三位小数，可用十进制字符串）。相同原料和requestId重试只登记一次，换数量或类型必须使用新的requestId。
- `GET /material-entry/materials`：需要入库或出库登记权限，返回未删除原料的 id、name、category、unit、ready、enabled，供员工选择，不开放库存数量及其他业务信息。
- `GET /material-entry/materials/{id}` 和 `POST /material-entry/materials/{id}/entries`：网页选料后的查询与登记接口，权限、请求和库存处理与扫码接口相同；两种入口共用幂等校验及当前账号的操作人记录。

查询接口要求 material-entry-in 或 material-entry-out 至少一项。登记接口还会按方向校验：IN 要求 material-entry-in，OUT 要求 material-entry-out，网页与二维码共用该校验；旧 material-entry 合并授权兼容为两项，管理界面保存时转为独立授权。导航用 material-entry 为派生权限，不作为实际登记方向授权。

登记使用上海当日业务日期：IN累加当日purchaseQty，OUT累加当日productionQty；库存、日汇总、数量流水及幂等凭据在同一事务更新。库存不足、锁定库存约束、停用及二维码失效均阻止登记。响应包含登记ID、方向、数量、单位、业务日期，以及操作人姓名和登录账户。操作人由服务端从当前登录身份取得，拒绝由其他员工重试同一登记；流水保存 operator（姓名快照）、operatorUsername（账户快照）、operatorUserId（用户编号），账号以后改名不回写历史。每日汇总的 updatedBy 表示最后操作人。未登录返回401，无对应方向登记权限返回403，不能通过重试已登记请求绕过撤权。

手机页面路径为 `/worker/material/{token}`。二维码使用打开后台时的当前网站域名生成；应在正式网站生成并打印现场标签。

员工可在用户管理的“库存操作 → 原料出入库登记”下单独授权出库登记、入库登记，无需 materials 权限。`/worker/material-entry` 为原料选择页，电脑端每行五个原料气泡，手机自动排布；点击进入 `/worker/material-entry/{materialId}`，复用当前登录会话，无需扫码或再次登录。停用或资料不完整的原料不可选。扫码页登录后保留原二维码入口。`/worker/material-preview` 保留不写入库存的界面演示，也需要员工登录和登记权限。升级扫码身份记录执行 `sql/migrations/20261020_material_scan_accounts.sql`，不追填旧匿名记录的身份；新增网页选择入口无需数据库迁移。

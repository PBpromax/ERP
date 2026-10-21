# 功能覆盖说明

| 设计功能 | 后端位置 | 前端页面 | 说明 |
|---|---|---|---|
| 客户档案 | masterdata/Customer* | 客户档案 | 客户联系人、地址、开票资料、对公账户信息、账期、税率、信用额度 |
| 产品资料 | masterdata/Product* | 产品资料 | 产品规格、单位、默认价、成本价、安全库存、是否生产 |
| 原材料资料 | masterdata/Material* | 原材料资料 | 安全库存、供应商、最近采购价、最低采购量 |
| BOM | masterdata/Bom*、BomItem* | BOM 管理 | 成品与原材料标准耗用关系 |
| 报价单 | sales/Quotation*、SalesService | 报价单 | 状态、明细、发送、转订单、打印入口 |
| 销售订单 | sales/SalesOrder*、SalesService | 销售订单 | 明细、审核、库存校验、缺货转生产 |
| 送货发货 | sales/DeliveryNote*、SalesService | 送货与发货 | 分批发货、确认出库、二维码、含价/无价打印 |
| 成品库存 | inventory/InventoryStock*、InventoryService | 成品库存 | 当前库存、锁定库存、安全库存、流水 |
| 原材料库存 | inventory/MaterialStock*、InventoryService | 原料库存 | 原料入库、领料、预警和缺料分析 |
| 库存流水 | inventory/InventoryTxn* | 库存流水 | 所有库存变化留痕 |
| 生产计划 | production/ProductionPlan*、ProductionService | 生产计划 | 自动生成计划、缺料分析、完工入库 |
| 采购提醒/申请 | purchase/PurchaseRequest* | 采购申请 | 缺料自动生成采购申请 |
| 账单 | finance/Statement*、FinanceService | 账单管理 | 月底生成、对账明细、欠款 |
| 开票 | finance/Invoice* | 开票登记 | 发票号、日期、金额 |
| 收款 | finance/Receipt* | 收款登记 | 回款登记并更新欠款 |
| 质检报告 | quality/QualityReport* | 质检报告 | 批次、结论、附件关联 |
| 附件 | quality/AttachmentController | 质检报告/文件接口 | 文件上传下载，预留 MinIO/OSS |
| 消息提醒 | notify/NotifyRecord* | 消息提醒 | 待办、库存预警、缺料提醒 |
| 统计分析 | report/DashboardController | 首页驾驶舱 | 销售趋势、客户排行、低库存、待办 |
| 用户角色 | system/SysUser*、SysRole* | 用户管理/角色管理 | 账号、角色、启用状态 |
| 字段权限 | system/FieldPermission*、FieldMaskingAdvice | 字段权限 | 金额/成本字段前端隐藏、后端脱敏 |
| 操作日志 | common/AuditLogAction、config/AuditAspect | 操作日志 | 审核、发货、账单、上传等关键动作留痕 |

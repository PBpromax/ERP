# 数据库说明

数据库按业务域拆分为以下表组：

1. 基础档案：customer、product、material、supplier、bom、bom_item
2. 销售单据：quotation、quotation_item、sales_order、sales_order_item、delivery_note、delivery_note_item
3. 财务：statement、statement_item、invoice、receipt、ar_balance_snapshot
4. 库存：inventory_stock、material_stock、inventory_txn、stock_lock
5. 生产采购：production_plan、purchase_request
6. 质检附件：quality_report、attachment
7. 系统：sys_user、sys_role、sys_permission、field_permission、audit_log、notify_record

库存变化必须写入 `inventory_txn`，业务模块不直接修改库存数字。订单、报价、送货、账单、发票、生产计划、采购申请均保留编号和状态字段。

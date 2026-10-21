CREATE DATABASE IF NOT EXISTS internal_oms DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE internal_oms;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(64) NOT NULL UNIQUE,
  `name` VARCHAR(100) NOT NULL,
  `description` VARCHAR(500),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(128) NOT NULL UNIQUE,
  `name` VARCHAR(100) NOT NULL,
  `type` VARCHAR(30) NOT NULL,
  `path` VARCHAR(255),
  `field_code` VARCHAR(128),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL UNIQUE,
  `password_hash` VARCHAR(255) NOT NULL,
  `real_name` VARCHAR(100),
  `phone` VARCHAR(50),
  `email` VARCHAR(120),
  `role_codes` VARCHAR(500),
  `enabled` TINYINT(1),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `field_permission`;
CREATE TABLE `field_permission` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `role_code` VARCHAR(64) NOT NULL,
  `field_code` VARCHAR(128) NOT NULL,
  `readable` TINYINT(1),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `username` VARCHAR(64),
  `module` VARCHAR(100),
  `action` VARCHAR(100),
  `business_key` VARCHAR(128),
  `detail` TEXT,
  `ip` VARCHAR(64),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `customer`;
CREATE TABLE `customer` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(200) NOT NULL,
  `contact_person` VARCHAR(100),
  `phone` VARCHAR(50),
  `address` VARCHAR(500),
  `invoice_title` VARCHAR(200),
  `tax_no` VARCHAR(100),
  `public_bank_account` VARCHAR(100),
  `public_bank_name` VARCHAR(200),
  `public_bank_no` VARCHAR(100),
  `default_payment_term` VARCHAR(30),
  `tax_rate` DECIMAL(18,4),
  `settlement_method` VARCHAR(100),
  `credit_limit` DECIMAL(38,18),
  `enabled` TINYINT(1),
  `last_order_date` DATE,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `product`;
CREATE TABLE `product` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(64) UNIQUE,
  `name` VARCHAR(200) NOT NULL,
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `default_price` DECIMAL(38,18),
  `cost_price` DECIMAL(38,18),
  `safety_stock` DECIMAL(18,3),
  `enabled` TINYINT(1),
  `aliases` TEXT,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `material`;
CREATE TABLE `material` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(64) UNIQUE,
  `name` VARCHAR(200) NOT NULL,
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `safety_stock` DECIMAL(18,3),
  `default_supplier` VARCHAR(200),
  `supplier` VARCHAR(200),
  `supplier_code` VARCHAR(64),
  `last_purchase_price` DECIMAL(38,18),
  `min_purchase_qty` DECIMAL(18,3),
  `enabled` TINYINT(1),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `supplier`;
CREATE TABLE `supplier` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(64) UNIQUE,
  `name` VARCHAR(200) NOT NULL,
  `contact_person` VARCHAR(100),
  `phone` VARCHAR(50),
  `address` VARCHAR(500),
  `enabled` TINYINT(1),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `quotation`;
CREATE TABLE `quotation` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `quote_no` VARCHAR(64) UNIQUE,
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `status` VARCHAR(30),
  `quote_date` DATE,
  `valid_until` DATE,
  `tax_rate` DECIMAL(18,4),
  `total_amount` DECIMAL(38,18),
  `remark` VARCHAR(1000),
  `version_no` INT,
  `converted_order_no` VARCHAR(64),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `quotation_item`;
CREATE TABLE `quotation_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `quotation_id` BIGINT,
  `product_id` BIGINT,
  `product_name` VARCHAR(200),
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `qty` DECIMAL(18,3),
  `unit_price` DECIMAL(38,18),
  `tax_rate` DECIMAL(18,4),
  `amount` DECIMAL(38,18),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `sales_order`;
CREATE TABLE `sales_order` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `order_no` VARCHAR(64) UNIQUE,
  `source_order_no` VARCHAR(64),
  `source_quote_no` VARCHAR(64),
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `status` VARCHAR(30),
  `order_date` DATE,
  `delivery_date` DATE,
  `actual_delivery_date` DATE,
  `total_amount` DECIMAL(38,18),
  `tax_amount` DECIMAL(38,18),
  `paid_amount` DECIMAL(38,18),
  `remark` VARCHAR(1000),
  `auditor` VARCHAR(100),
  `audit_time` DATETIME,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `sales_order_item`;
CREATE TABLE `sales_order_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `order_id` BIGINT,
  `product_id` BIGINT,
  `product_code` VARCHAR(64),
  `product_name` VARCHAR(200),
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `qty` DECIMAL(18,3),
  `shipped_qty` DECIMAL(18,3),
  `unit_price` DECIMAL(18,5),
  `tax_rate` DECIMAL(18,4),
  `amount` DECIMAL(38,18),
  `due_date` DATE,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `delivery_note`;
CREATE TABLE `delivery_note` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `delivery_no` VARCHAR(64) UNIQUE,
  `delivery_seq` INT,
  `order_id` BIGINT,
  `order_no` VARCHAR(64),
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `status` VARCHAR(30),
  `delivery_date` DATE,
  `total_amount` DECIMAL(38,18),
  `print_price` TINYINT(1),
  `qr_code_content` VARCHAR(500),
  `confirmer` VARCHAR(100),
  `confirm_time` DATETIME,
  `remark` VARCHAR(1000),
  `delivery_qty` DECIMAL(18,3),
  `delivery_method` VARCHAR(100),
  `logistics_no` VARCHAR(100),
  `delivery_info` VARCHAR(1000),
  `historical_imported` TINYINT(1),
  `inventory_posted` TINYINT(1),
  `source_comment` TEXT,
  `invoiced` TINYINT(1),
  `invoice_no` VARCHAR(100),
  `invoice_date` DATE,
  `invoice_amount` DECIMAL(38,18),
  `statement_id` BIGINT,
  `statement_no` VARCHAR(64),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `delivery_note_item`;
CREATE TABLE `delivery_note_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `delivery_id` BIGINT,
  `order_item_id` BIGINT,
  `product_id` BIGINT,
  `product_name` VARCHAR(200),
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `qty` DECIMAL(18,3),
  `unit_price` DECIMAL(38,18),
  `amount` DECIMAL(38,18),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `inventory_stock`;
CREATE TABLE `inventory_stock` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `product_id` BIGINT UNIQUE,
  `product_code` VARCHAR(64),
  `product_name` VARCHAR(200),
  `current_qty` DECIMAL(18,3),
  `locked_qty` DECIMAL(18,3),
  `safety_stock` DECIMAL(18,3),
  `stock_amount` DECIMAL(38,18),
  `version_no` BIGINT,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `material_stock`;
CREATE TABLE `material_stock` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `material_id` BIGINT UNIQUE,
  `material_code` VARCHAR(64),
  `material_name` VARCHAR(200),
  `current_qty` DECIMAL(18,3),
  `locked_qty` DECIMAL(18,3),
  `safety_stock` DECIMAL(18,3),
  `stock_amount` DECIMAL(38,18),
  `version_no` BIGINT,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `inventory_txn`;
CREATE TABLE `inventory_txn` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `item_type` VARCHAR(30),
  `item_id` BIGINT,
  `item_code` VARCHAR(64),
  `item_name` VARCHAR(200),
  `txn_type` VARCHAR(50),
  `qty` DECIMAL(18,3),
  `before_qty` DECIMAL(18,3),
  `after_qty` DECIMAL(18,3),
  `source_type` VARCHAR(50),
  `source_no` VARCHAR(64),
  `remark` VARCHAR(1000),
  `operator` VARCHAR(100),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `stock_lock`;
CREATE TABLE `stock_lock` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `item_type` VARCHAR(30),
  `item_id` BIGINT,
  `source_type` VARCHAR(50),
  `source_no` VARCHAR(64),
  `qty` DECIMAL(18,3),
  `status` VARCHAR(30),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `production_plan`;
CREATE TABLE `production_plan` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `plan_no` VARCHAR(64) UNIQUE,
  `source_order_id` BIGINT,
  `source_order_no` VARCHAR(64),
  `product_id` BIGINT,
  `product_name` VARCHAR(200),
  `plan_qty` DECIMAL(18,3),
  `status` VARCHAR(30),
  `progress` INT,
  `expected_finish_date` DATE,
  `start_date` DATE,
  `finish_date` DATE,
  `remark` VARCHAR(1000),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `semi_finished_stock`;
CREATE TABLE `semi_finished_stock` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `code` VARCHAR(64) UNIQUE,
  `labor_price` DECIMAL(38,18),
  `stock_qty` DECIMAL(18,3),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `production_worker`;
CREATE TABLE `production_worker` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `worker_production_record`;
CREATE TABLE `worker_production_record` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `worker_id` BIGINT,
  `worker_name` VARCHAR(100),
  `production_date` DATE,
  `semi_finished_stock_id` BIGINT,
  `semi_finished_code` VARCHAR(64),
  `qty` DECIMAL(18,3),
  `labor_price` DECIMAL(38,18),
  `amount` DECIMAL(38,18),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `purchase_request`;
CREATE TABLE `purchase_request` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `request_no` VARCHAR(64) UNIQUE,
  `material_id` BIGINT,
  `material_name` VARCHAR(200),
  `request_qty` DECIMAL(18,3),
  `default_supplier` VARCHAR(200),
  `expected_arrival_date` DATE,
  `status` VARCHAR(30),
  `source_type` VARCHAR(50),
  `source_no` VARCHAR(64),
  `remark` VARCHAR(1000),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `statement`;
CREATE TABLE `statement` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `statement_no` VARCHAR(64) UNIQUE,
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `statement_month` VARCHAR(7),
  `receivable_amount` DECIMAL(38,18),
  `invoiced_amount` DECIMAL(38,18),
  `received_amount` DECIMAL(38,18),
  `historical_received_amount` DECIMAL(38,18),
  `arrears_amount` DECIMAL(38,18),
  `status` VARCHAR(30),
  `settlement_method` VARCHAR(20),
  `settlement_status` VARCHAR(30),
  `registered_at` DATETIME,
  `paid_at` DATETIME,
  `generated_at` DATETIME,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `statement_item`;
CREATE TABLE `statement_item` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `statement_id` BIGINT,
  `delivery_id` BIGINT,
  `delivery_item_id` BIGINT,
  `delivery_no` VARCHAR(64),
  `order_no` VARCHAR(64),
  `product_name` VARCHAR(200),
  `spec` VARCHAR(200),
  `unit` VARCHAR(30),
  `qty` DECIMAL(18,3),
  `unit_price` DECIMAL(38,18),
  `amount` DECIMAL(38,18),
  `delivery_date` DATE,
  `adjustment_type` VARCHAR(20),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `invoice`;
CREATE TABLE `invoice` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `invoice_no` VARCHAR(64),
  `statement_id` BIGINT,
  `order_id` BIGINT,
  `order_no` VARCHAR(64),
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `invoice_date` DATE,
  `amount` DECIMAL(38,18),
  `tax_rate` DECIMAL(18,4),
  `status` VARCHAR(30),
  `remark` VARCHAR(1000),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `receipt`;
CREATE TABLE `receipt` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `receipt_no` VARCHAR(64) UNIQUE,
  `statement_id` BIGINT,
  `invoice_id` BIGINT,
  `order_id` BIGINT,
  `order_no` VARCHAR(64),
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `receipt_date` DATE,
  `amount` DECIMAL(38,18),
  `method` VARCHAR(50),
  `bank_serial` VARCHAR(100),
  `remark` VARCHAR(1000),
  `payment_batch_no` VARCHAR(80),
  `ledger_account_id` BIGINT,
  `ledger_entry_id` BIGINT,
  `ledger_previous_payment_date` DATE,
  `customer_level_payment` TINYINT(1) DEFAULT 0,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `ar_balance_snapshot`;
CREATE TABLE `ar_balance_snapshot` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `customer_id` BIGINT,
  `customer_name` VARCHAR(200),
  `snapshot_date` DATE,
  `receivable_amount` DECIMAL(38,18),
  `received_amount` DECIMAL(38,18),
  `arrears_amount` DECIMAL(38,18),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `customer_ledger_account`;
CREATE TABLE `customer_ledger_account` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `source_key` VARCHAR(200) NOT NULL UNIQUE,
  `sheet_name` VARCHAR(200) NOT NULL UNIQUE,
  `statement_amount` DECIMAL(38,18),
  `current_debt` DECIMAL(38,18),
  `entry_count` INT,
  `open_entry_count` INT,
  `credit_balance` DECIMAL(38,18),
  `invoice_cutoff` DATE,
  `imported_at` DATETIME,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `customer_ledger_entry`;
CREATE TABLE `customer_ledger_entry` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `source_key` VARCHAR(240) NOT NULL UNIQUE,
  `account_id` BIGINT,
  `source_row` INT,
  `transaction_date` DATE,
  `source_date` VARCHAR(100),
  `summary` VARCHAR(100),
  `amount` DECIMAL(38,18),
  `invoice_no` VARCHAR(200),
  `notes` VARCHAR(2000),
  `paid_amount` DECIMAL(38,18),
  `remaining_amount` DECIMAL(38,18),
  `last_payment_date` DATE,
  `linked_statement_id` BIGINT,
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0,
  KEY `idx_customer_ledger_entry_account` (`account_id`),
  KEY `idx_customer_ledger_entry_statement` (`linked_statement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `quality_report`;
CREATE TABLE `quality_report` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `report_no` VARCHAR(64) UNIQUE,
  `business_type` VARCHAR(50),
  `business_no` VARCHAR(64),
  `order_no` VARCHAR(64),
  `product_id` BIGINT,
  `product_name` VARCHAR(200),
  `batch_no` VARCHAR(100),
  `inspection_date` DATE,
  `inspector` VARCHAR(100),
  `conclusion` VARCHAR(50),
  `remark` VARCHAR(1000),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `attachment`;
CREATE TABLE `attachment` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `business_type` VARCHAR(50),
  `business_id` BIGINT,
  `file_key` VARCHAR(500),
  `original_name` VARCHAR(255),
  `content_type` VARCHAR(100),
  `size_bytes` BIGINT,
  `uploaded_by` VARCHAR(100),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TABLE IF EXISTS `notify_record`;
CREATE TABLE `notify_record` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `title` VARCHAR(200),
  `content` VARCHAR(1000),
  `type` VARCHAR(50),
  `level` VARCHAR(30),
  `status` VARCHAR(30),
  `business_type` VARCHAR(50),
  `business_no` VARCHAR(64),
  `created_at` DATETIME,
  `updated_at` DATETIME,
  `created_by` VARCHAR(100),
  `updated_by` VARCHAR(100),
  `deleted` TINYINT(1) DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- Local first: widen unit prices without changing line amounts or financial balances.
-- Repair only prices proven by an exact source order line AND the existing delivery amount.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE delivery_note_item ALTER COLUMN unit_price TYPE numeric(18,5);
ALTER TABLE quotation_item ALTER COLUMN unit_price TYPE numeric(18,5);

CREATE TEMP TABLE price_repair_delivery ON COMMIT DROP AS
SELECT di.id, di.delivery_id, di.unit_price old_price, oi.unit_price new_price
FROM delivery_note_item di
JOIN delivery_note d ON d.id=di.delivery_id AND NOT d.deleted
JOIN sales_order_item oi ON oi.id=di.order_item_id AND NOT oi.deleted
JOIN sales_order o ON o.id=oi.order_id AND NOT o.deleted
WHERE NOT di.deleted AND di.unit_price<>oi.unit_price
  AND di.unit_price=round(oi.unit_price,2)
  AND di.amount=round(di.qty*oi.unit_price,2);

-- Do not infer a source line from similar product names. Old single-line deliveries
-- have an unambiguous source; multi-line deliveries require an explicit item ID.
CREATE TEMP TABLE price_repair_statement ON COMMIT DROP AS
SELECT si.id, si.statement_id, si.unit_price old_price, fix.new_price
FROM statement_item si
JOIN statement s ON s.id=si.statement_id AND NOT s.deleted
JOIN price_repair_delivery fix ON
  si.delivery_item_id=fix.id OR
  (si.delivery_item_id IS NULL AND si.delivery_id=fix.delivery_id AND
    (SELECT count(*) FROM delivery_note_item di WHERE di.delivery_id=fix.delivery_id AND NOT di.deleted)=1)
WHERE NOT si.deleted AND si.unit_price=fix.old_price
  AND si.amount=round(si.qty*fix.new_price,2);

INSERT INTO audit_log(created_at,updated_at,deleted,username,module,action,business_key,detail,ip)
SELECT now(),now(),false,'admin','财务管理','修复单价精度',
  'UNIT-PRICE-PRECISION-20261003',
  jsonb_build_object('table',changes.table_name,'change',changes.row_data,
    'rule','Exact source order and existing amount match; amounts and receipts unchanged')::text,
  '127.0.0.1'
FROM (
  SELECT 'delivery_note_item' table_name,to_jsonb(x) row_data FROM price_repair_delivery x
  UNION ALL SELECT 'statement_item',to_jsonb(x) FROM price_repair_statement x
) changes;

UPDATE delivery_note_item di SET unit_price=fix.new_price,updated_at=now(),updated_by='precision-repair'
FROM price_repair_delivery fix WHERE di.id=fix.id;
UPDATE statement_item si SET unit_price=fix.new_price,updated_at=now(),updated_by='precision-repair'
FROM price_repair_statement fix WHERE si.id=fix.id;
SELECT (SELECT count(*) FROM price_repair_delivery) delivery_prices_repaired,
       (SELECT count(*) FROM price_repair_statement) statement_prices_repaired;
COMMIT;

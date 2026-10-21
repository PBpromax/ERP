-- Exact monetary values: remove fixed decimal scale without recomputing any existing amount.
BEGIN;
SET LOCAL lock_timeout = '10s';
DO $migration$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('delivery_note','total_amount'),
        ('delivery_note','invoice_amount'),
        ('quotation','total_amount'),
        ('sales_order_item','unit_price'),
        ('sales_order_item','amount'),
        ('sales_order','total_amount'),
        ('sales_order','tax_amount'),
        ('sales_order','paid_amount'),
        ('delivery_note_item','unit_price'),
        ('delivery_note_item','amount'),
        ('sales_return','unit_price'),
        ('sales_return','amount'),
        ('quotation_item','unit_price'),
        ('quotation_item','amount'),
        ('material_stock','stock_amount'),
        ('inventory_stock','stock_amount'),
        ('semi_finished_stock','labor_price'),
        ('worker_production_record','labor_price'),
        ('worker_production_record','amount'),
        ('receipt','amount'),
        ('customer_ledger_account','statement_amount'),
        ('customer_ledger_account','current_debt'),
        ('customer_ledger_account','credit_balance'),
        ('customer_ledger_account','source_net_amount'),
        ('ar_balance_snapshot','receivable_amount'),
        ('ar_balance_snapshot','received_amount'),
        ('ar_balance_snapshot','arrears_amount'),
        ('statement','receivable_amount'),
        ('statement','invoiced_amount'),
        ('statement','received_amount'),
        ('statement','historical_received_amount'),
        ('statement','arrears_amount'),
        ('statement','source_invoiced_amount'),
        ('invoice','amount'),
        ('statement_item','unit_price'),
        ('statement_item','amount'),
        ('customer_ledger_entry','amount'),
        ('customer_ledger_entry','paid_amount'),
        ('customer_ledger_entry','remaining_amount'),
        ('receipt_payment','amount'),
        ('receipt_payment','unallocated_amount'),
        ('material','last_purchase_price'),
        ('product','default_price'),
        ('product','cost_price'),
        ('customer','credit_limit')
    ) AS columns_to_expand(table_name,column_name)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema='public'
                AND c.table_name=item.table_name AND c.column_name=item.column_name) THEN
            RAISE EXCEPTION 'Missing monetary column %.%',item.table_name,item.column_name;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.columns c WHERE c.table_schema='public'
                AND c.table_name=item.table_name AND c.column_name=item.column_name AND c.numeric_scale IS NOT NULL) THEN
            EXECUTE format('ALTER TABLE public.%I ALTER COLUMN %I TYPE numeric', item.table_name,item.column_name);
        END IF;
    END LOOP;
END $migration$;
COMMIT;

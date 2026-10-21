package com.company.oms.finance;

import com.company.oms.common.*;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import com.company.oms.sales.*;
import com.company.oms.quality.Attachment;
import com.company.oms.quality.AttachmentRepository;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FinanceService {
    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_INVOICE = "INVOICE";
    public static final String STATUS_UNREGISTERED = "UNREGISTERED";
    public static final String STATUS_CASH_PENDING = "CASH_PENDING";
    public static final String STATUS_INVOICED_PENDING = "INVOICED_PENDING";
    public static final String STATUS_PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String STATUS_PAID = "PAID";
    public static final String ADJUSTMENT_RETURN = "RETURN";
    public static final String ADJUSTMENT_AMOUNT = "AMOUNT";
    // These settlement reductions remain in the balances, but are not a payment arriving from the customer.
    private static final Set<String> NON_PAYMENT_METHODS = Set.of(
            "账款调减", "货物抵款", "冲账", "让利", "差额", "平账", "扣款", "退货", "退回", "樱桃汁", "发酒", "酒");
    private static final Pattern INVOICE_NUMBER_PATTERN = Pattern.compile("(?<!\\d)\\d{15,20}(?!\\d)");
    private static final Set<String> NON_INVOICE_LABELS = Set.of(
            "不开票", "未开票", "不开发票", "现汇", "现金", "私卡", "承兑", "银行转账", "客户汇款");
    private static final String COLLECTION_COMPANY_BOARD_SQL = """
            with active_customers as (
                select c.id, c.name, c.contact_person, c.phone
                  from customer c
                 where c.deleted = false or c.deleted is null
            ),
            mapped_accounts as (
                select a.id, a.credit_balance, a.source_net_amount,
                       direct_customer.id as direct_customer_id,
                       coalesce(direct_customer.id, (
                           select min(named_customer.id)
                             from active_customers named_customer
                            where trim(named_customer.name) = trim(a.sheet_name)
                       )) as customer_id,
                       case when direct_customer.id is null then (
                           select count(*)
                             from active_customers named_customer
                            where trim(named_customer.name) = trim(a.sheet_name)
                       ) else 0 end as name_match_count
                  from customer_ledger_account a
                  left join active_customers direct_customer on direct_customer.id = a.customer_id
                 where a.deleted = false
            ),
            account_totals as (
                select a.customer_id,
                       sum(coalesce(a.credit_balance, 0)) as credit_balance,
                       sum(coalesce(a.source_net_amount, 0)) as source_net_amount,
                       max(case when a.source_net_amount is not null then 1 else 0 end) as has_source_net
                  from mapped_accounts a
                 where a.customer_id is not null
                 group by a.customer_id
            ),
            statement_totals as (
                select s.customer_id,
                       count(s.id) as statement_count,
                       sum(coalesce(s.receivable_amount, 0)) as receivable_amount,
                       sum(coalesce(s.invoiced_amount, 0)) as invoiced_amount,
                       sum(case when coalesce(s.invoiced_amount, 0) > coalesce(s.received_amount, 0)
                                then coalesce(s.invoiced_amount, 0) - coalesce(s.received_amount, 0)
                                else 0 end) as invoiced_unpaid_amount,
                       sum(coalesce(s.received_amount, 0)) as received_amount,
                       sum(case when coalesce(s.arrears_amount, 0) > 0
                                then coalesce(s.arrears_amount, 0) else 0 end) as current_debt,
                       sum(case when coalesce(s.arrears_amount, 0) > 0 then 1 else 0 end) as unpaid_count,
                       max(s.generated_at) as latest_statement_at
                  from statement s
                  join active_customers c on c.id = s.customer_id
                 where s.deleted = false
                   and (s.collection_excluded = false or s.collection_excluded is null)
                 group by s.customer_id
            ),
            payment_totals as (
                select p.group_key, sum(coalesce(p.unallocated_amount, 0)) as unallocated_amount
                  from receipt_payment p
                 where p.deleted = false
                 group by p.group_key
            ),
            sales_totals as (
                select o.customer_id, sum(coalesce(o.total_amount, 0)) as total_sales_amount
                  from sales_order o
                 where o.deleted = false
                   and o.customer_id is not null
                   and o.order_date >= :yearStart
                   and o.order_date < :yearEnd
                 group by o.customer_id
            ),
            eligible_ledger as (
                select concat('customer-', cast(a.customer_id as varchar)) as group_key,
                       e.amount, e.invoice_no, e.paid_amount, e.remaining_amount
                  from mapped_accounts a
                  join customer_ledger_entry e on e.account_id = a.id and e.deleted = false
                 where a.customer_id is not null
                   and (e.linked_statement_id is null or not exists (
                       select 1 from statement linked
                        where linked.id = e.linked_statement_id
                          and (linked.deleted = false or linked.deleted is null)
                          and (linked.collection_excluded = false or linked.collection_excluded is null)
                   ))
            )
            select 'BASE' as row_type,
                   concat('customer-', cast(c.id as varchar)) as group_key,
                   c.id as customer_id, c.name as customer_name,
                   c.contact_person, c.phone,
                   coalesce(st.statement_count, 0) as statement_count,
                   coalesce(sa.total_sales_amount, cast(0 as numeric(18,2))) as total_sales_amount,
                   coalesce(st.unpaid_count, 0) as unpaid_statement_count,
                   coalesce(st.receivable_amount, cast(0 as numeric(18,2))) as statement_receivable,
                   coalesce(st.invoiced_amount, cast(0 as numeric(18,2))) as statement_invoiced,
                   coalesce(st.invoiced_unpaid_amount, cast(0 as numeric(18,2))) as statement_invoiced_unpaid,
                   coalesce(st.received_amount, cast(0 as numeric(18,2))) as statement_received,
                   coalesce(st.current_debt, cast(0 as numeric(18,2))) as statement_debt,
                   coalesce(at.credit_balance, cast(0 as numeric(18,2)))
                       + coalesce(pt.unallocated_amount, cast(0 as numeric(18,2))) as credit_balance,
                   at.source_net_amount, coalesce(at.has_source_net, 0) as has_source_net,
                   st.latest_statement_at,
                   cast(null as numeric(18,2)) as ledger_amount,
                   cast(null as varchar) as ledger_invoice_no,
                   cast(null as numeric(18,2)) as ledger_paid_amount,
                   cast(null as numeric(18,2)) as ledger_remaining_amount,
                   0 as fallback_required
              from active_customers c
              left join statement_totals st on st.customer_id = c.id
              left join account_totals at on at.customer_id = c.id
              left join payment_totals pt on pt.group_key = concat('customer-', cast(c.id as varchar))
              left join sales_totals sa on sa.customer_id = c.id
            union all
            select 'LEDGER', e.group_key,
                   cast(null as bigint), cast(null as varchar), cast(null as varchar), cast(null as varchar),
                   cast(0 as bigint), cast(0 as numeric(18,2)), cast(0 as bigint),
                   cast(0 as numeric(18,2)), cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)), cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)), cast(null as numeric(18,2)), 0,
                   cast(null as timestamp),
                   e.amount, e.invoice_no, e.paid_amount, e.remaining_amount, 0
              from eligible_ledger e
            union all
            select 'META', cast(null as varchar),
                   cast(null as bigint), cast(null as varchar), cast(null as varchar), cast(null as varchar),
                   cast(0 as bigint), cast(0 as numeric(18,2)), cast(0 as bigint),
                   cast(0 as numeric(18,2)), cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)), cast(0 as numeric(18,2)),
                   cast(0 as numeric(18,2)), cast(null as numeric(18,2)), 0,
                   cast(null as timestamp),
                   cast(null as numeric(18,2)), cast(null as varchar),
                   cast(null as numeric(18,2)), cast(null as numeric(18,2)),
                   case when exists (
                       select 1 from mapped_accounts a
                        where a.customer_id is null
                           or (a.direct_customer_id is null and a.name_match_count > 1)
                   ) or exists (
                       select 1
                         from statement s
                         left join active_customers c on c.id = s.customer_id
                        where s.deleted = false
                          and (s.collection_excluded = false or s.collection_excluded is null)
                          and c.id is null
                   ) then 1 else 0 end
            """;

    private final StatementRepository statementRepository;
    private final StatementItemRepository statementItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReceiptRepository receiptRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliveryNoteItemRepository deliveryNoteItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final AttachmentRepository attachmentRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final CustomerLedgerAccountRepository ledgerAccountRepository;
    private final CustomerLedgerEntryRepository ledgerEntryRepository;
    private final DocumentNumberService documentNumberService;
    private final SalesReturnRepository salesReturnRepository;
    private final SalesReturnService salesReturnService;
    private final ReceiptPaymentRepository paymentRepository;
    private final CacheManager cacheManager;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    public FinanceService(StatementRepository statementRepository,
                          StatementItemRepository statementItemRepository,
                          InvoiceRepository invoiceRepository,
                          ReceiptRepository receiptRepository,
                          DeliveryNoteRepository deliveryNoteRepository,
                          DeliveryNoteItemRepository deliveryNoteItemRepository,
                          SalesOrderRepository salesOrderRepository,
                          SalesOrderItemRepository salesOrderItemRepository,
                          AttachmentRepository attachmentRepository,
                          CustomerRepository customerRepository,
                          ProductRepository productRepository,
                          CustomerLedgerAccountRepository ledgerAccountRepository,
                          CustomerLedgerEntryRepository ledgerEntryRepository,
                          DocumentNumberService documentNumberService,
                          SalesReturnRepository salesReturnRepository, SalesReturnService salesReturnService,
                          ReceiptPaymentRepository paymentRepository,
                          CacheManager cacheManager,
                          NamedParameterJdbcTemplate jdbcTemplate) {
        this.statementRepository = statementRepository;
        this.statementItemRepository = statementItemRepository;
        this.invoiceRepository = invoiceRepository;
        this.receiptRepository = receiptRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.deliveryNoteItemRepository = deliveryNoteItemRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.attachmentRepository = attachmentRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.documentNumberService = documentNumberService;
        this.salesReturnRepository = salesReturnRepository;
        this.salesReturnService = salesReturnService;
        this.paymentRepository = paymentRepository;
        this.cacheManager = cacheManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public List<Statement> generateStatements(String month) {
        YearMonth ym = YearMonth.parse(month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        Map<String, List<DeliveryNote>> grouped = pendingDeliveries(deliveryNoteRepository
                .findByStatusAndDeliveryDateBetweenAndDeletedFalse("已发货", start, end)).stream()
                .collect(Collectors.groupingBy(
                        pending -> customerKey(pending.delivery()),
                        LinkedHashMap::new,
                        Collectors.mapping(PendingDelivery::delivery, Collectors.toList())));
        List<Statement> generated = new ArrayList<>();
        List<Map<String, Object>> returns = salesReturnService.candidates().stream()
                .filter(r -> !((LocalDate) r.get("deliveryDate")).isBefore(start)
                        && !((LocalDate) r.get("deliveryDate")).isAfter(end)).toList();
        for (List<DeliveryNote> deliveries : grouped.values()) {
            if (deliveries.isEmpty()) continue;
            List<StatementDeliverySelection> selections = new ArrayList<>(deliveries.stream()
                    .map(d -> new StatementDeliverySelection(d.id, null)).toList());
            for (Map<String, Object> row : returns) {
                if (!Objects.equals(deliveries.get(0).customerId, row.get("customerId"))) continue;
                StatementDeliverySelection selection = new StatementDeliverySelection();
                selection.returnId = (Long) row.get("returnId");
                selections.add(selection);
            }
            generated.addAll(generateStatementsFromSelections(month, selections));
        }
        return generated;
    }

    public List<Map<String, Object>> statementCandidateDeliveries(String month) {
        YearMonth ym = YearMonth.parse(month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<Map<String, Object>> rows = new ArrayList<>(pendingDeliveries(deliveryNoteRepository
                .findByStatusAndDeliveryDateBetweenAndDeletedFalse("已发货", start, end)).stream()
                .sorted(Comparator
                        .comparing((PendingDelivery pending) -> pending.delivery().deliveryDate == null ? LocalDate.MIN : pending.delivery().deliveryDate)
                        .reversed()
                        .thenComparing(pending -> pending.delivery().id == null ? 0L : pending.delivery().id, Comparator.reverseOrder()))
                .map(this::deliveryCandidateRow)
                .toList());
        rows.addAll(salesReturnService.candidates().stream()
                .filter(r -> !((LocalDate) r.get("deliveryDate")).isBefore(start)
                        && !((LocalDate) r.get("deliveryDate")).isAfter(end)).toList());
        return rows;
    }

    /** Same remaining shipments and returns as the statement customer board, across all years. */
    public BigDecimal pendingReconciliationTotal() {
        return statementCustomerBoard().stream()
                .map(row -> MoneyUtil.nvl((BigDecimal) row.get("pendingAmount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Map<String, Object>> statementCustomerBoard() {
        List<Map<String, Object>> pendingReturns = salesReturnService.candidates();
        Map<Long, List<PendingDelivery>> pendingByCustomer = activePendingDeliveries().stream()
                .filter(pending -> pending.delivery().customerId != null)
                .collect(Collectors.groupingBy(pending -> pending.delivery().customerId));
        return customerRepository.findAll().stream()
                .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                .map(customer -> {
                    Map<String, Object> row = customerBoardRow(customer, pendingByCustomer.getOrDefault(customer.id, List.of()));
                    List<Map<String, Object>> returns = pendingReturns.stream()
                            .filter(r -> Objects.equals(customer.id, r.get("customerId"))).toList();
                    row.put("pendingDeliveryCount", ((Number) row.get("pendingDeliveryCount")).intValue() + returns.size());
                    row.put("pendingAmount", ((BigDecimal) row.get("pendingAmount")).add(returns.stream()
                            .map(r -> (BigDecimal) r.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add)));
                    return row;
                })
                .sorted(Comparator
                        .comparingInt((Map<String, Object> row) -> ((Number) row.get("pendingDeliveryCount")).intValue())
                        .reversed()
                        .thenComparing(row -> (BigDecimal) row.get("pendingAmount"), Comparator.reverseOrder())
                        .thenComparing(row -> String.valueOf(row.get("customerName"))))
                .toList();
    }

    public Map<String, Object> customerStatementCandidates(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("客户不存在"));
        List<Map<String, Object>> deliveries = new ArrayList<>(activePendingDeliveries().stream()
                .filter(pending -> Objects.equals(customerId, pending.delivery().customerId))
                .map(this::deliveryCandidateRow)
                .toList());
        deliveries.addAll(salesReturnService.candidates().stream()
                .filter(row -> Objects.equals(customerId, row.get("customerId"))).toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", customer);
        result.put("deliveries", deliveries);
        result.put("statements", sortedStatements(statementRepository.findByCustomerIdAndDeletedFalse(customerId)));
        return result;
    }

    @Transactional
    public List<Statement> generateStatementsFromDeliveries(String month, List<Long> deliveryIds) {
        if (deliveryIds == null || deliveryIds.isEmpty()) {
            throw new BusinessException("请先勾选需要对账的送货记录");
        }
        List<StatementDeliverySelection> selections = deliveryIds.stream()
                .map(deliveryId -> new StatementDeliverySelection(deliveryId, null))
                .toList();
        return generateStatementsFromSelections(month, selections);
    }

    @Transactional
    public List<Statement> generateStatementsFromSelections(String month,
                                                             List<StatementDeliverySelection> selections) {
        List<Statement> statements = generateWithoutPaymentReplay(month, selections);
        // Finalize return deductions before applying any existing advance payment.
        for (Statement statement : statements) {
            statementRepository.saveAndFlush(statement);
            if (statement.customerId != null) {
                String groupKey = "customer-" + statement.customerId;
                if (!paymentRepository.findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(groupKey).isEmpty())
                    replayCustomerPayments(groupKey);
            }
        }
        return statements;
    }

    private List<Statement> generateWithoutPaymentReplay(String month,
                                                         List<StatementDeliverySelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new BusinessException("请先勾选需要对账的送货记录");
        }

        YearMonth.parse(month);
        if (selections.stream().anyMatch(s -> s != null && s.returnId != null)) {
            return generateWithReturns(month, selections);
        }
        if (selections.stream().anyMatch(selection -> selection == null || selection.deliveryId == null)) {
            throw new BusinessException("送货记录不存在");
        }
        List<Long> distinctIds = selections.stream().map(selection -> selection.deliveryId).distinct().sorted().toList();
        Set<Long> wholeDeliveries = new HashSet<>();
        Map<Long, Set<Long>> selectedItemIds = new HashMap<>();
        for (StatementDeliverySelection selection : selections) {
            if (selection.deliveryItemId == null) {
                if (!wholeDeliveries.add(selection.deliveryId) || selectedItemIds.containsKey(selection.deliveryId))
                    throw new BusinessException("不能重复选择同一送货记录或混合选择整单与明细");
            } else if (wholeDeliveries.contains(selection.deliveryId)
                    || !selectedItemIds.computeIfAbsent(selection.deliveryId, ignored -> new HashSet<>()).add(selection.deliveryItemId)) {
                throw new BusinessException("不能重复选择同一送货明细或混合选择整单与明细");
            }
        }
        Map<Long, DeliveryNote> deliveriesById = distinctIds.stream()
                .map(id -> deliveryNoteRepository.findForReconciliation(id)
                        .orElseThrow(() -> new BusinessException("送货记录不存在")))
                .collect(Collectors.toMap(note -> note.id, note -> note));
        if (deliveriesById.size() != distinctIds.size()) throw new BusinessException("送货记录不存在");
        Map<Long, PendingDelivery> pendingById = pendingDeliveries(new ArrayList<>(deliveriesById.values())).stream()
                .collect(Collectors.toMap(pending -> pending.delivery().id, pending -> pending));

        Set<String> customerKeys = new HashSet<>();
        List<DeliveryNote> deliveries = new ArrayList<>();
        Map<Long, BigDecimal> selectedQtyByDelivery = new LinkedHashMap<>();
        Map<Long, Map<Long, BigDecimal>> selectedQtyByItem = new LinkedHashMap<>();
        for (StatementDeliverySelection selection : selections) {
            Long deliveryId = selection.deliveryId;
            DeliveryNote delivery = deliveriesById.get(deliveryId);
            if (!"已发货".equals(delivery.status)) {
                throw new BusinessException("只有已发货的送货记录可以生成对账单：" + delivery.deliveryNo);
            }
            PendingDelivery pending = pendingById.get(delivery.id);
            if (pending == null) {
                throw new BusinessException("送货记录没有剩余待对账数量：" + delivery.deliveryNo);
            }
            BigDecimal maximum = pending.balance().remainingQty();
            if (selection.deliveryItemId != null) {
                maximum = pending.balance().remainingByItem().get(selection.deliveryItemId);
                if (maximum == null) throw new BusinessException("所选型号不属于该送货单：" + delivery.deliveryNo);
            }
            BigDecimal selectedQty = selection.qty == null ? maximum : selection.qty;
            validateStatementSelectionQty(delivery, selectedQty, maximum);
            customerKeys.add(customerKey(delivery));
            if (customerKeys.size() > 1) {
                throw new BusinessException("勾选订单未来自同一公司！");
            }
            if (!deliveries.contains(delivery)) deliveries.add(delivery);
            if (selection.deliveryItemId == null) selectedQtyByDelivery.put(delivery.id, selectedQty);
            else selectedQtyByItem.computeIfAbsent(delivery.id, ignored -> new LinkedHashMap<>())
                    .put(selection.deliveryItemId, selectedQty);
        }
        return List.of(createStatement(month, deliveries, selectedQtyByDelivery, selectedQtyByItem));
    }

    private List<Statement> generateWithReturns(String month, List<StatementDeliverySelection> selections) {
        if (selections.stream().anyMatch(s -> s == null || (s.returnId == null) == (s.deliveryId == null)))
            throw new BusinessException("每行必须选择一条发货或退货记录");
        List<StatementDeliverySelection> shipments = selections.stream().filter(s -> s.deliveryId != null).toList();
        if (shipments.isEmpty()) throw new BusinessException("请选择发货记录一起对账，退货冲减后的合计不能小于0");
        Statement statement = generateWithoutPaymentReplay(month, shipments).get(0);
        appendReturnItems(statement, selections.stream().filter(s -> s.returnId != null).toList());
        return List.of(statement);
    }

    @Transactional
    public Statement appendSelectedReturns(Long statementId, List<StatementDeliverySelection> selections) {
        if (selections == null || selections.isEmpty() || selections.stream()
                .anyMatch(s -> s == null || s.returnId == null || s.deliveryId != null))
            throw new BusinessException("请选择需要加入账单的退货记录");
        Statement statement = statementRepository.findForReturn(statementId)
                .orElseThrow(() -> new BusinessException("对账单不存在"));
        appendReturnItems(statement, selections);
        return statement;
    }

    private void appendReturnItems(Statement statement, List<StatementDeliverySelection> selections) {
        Set<Long> seen = new HashSet<>();
        for (StatementDeliverySelection selection : selections.stream().filter(s -> s.returnId != null)
                .sorted(Comparator.comparing(s -> s.returnId)).toList()) {
            if (!seen.add(selection.returnId)) throw new BusinessException("不能重复选择同一退货记录");
            SalesReturn r = salesReturnRepository.findForReconciliation(selection.returnId)
                    .orElseThrow(() -> new BusinessException("退货记录不存在"));
            if (!Objects.equals(r.customerId, statement.customerId)) throw new BusinessException("发货和退货必须来自同一客户");
            BigDecimal remaining = r.qty.subtract(salesReturnService.reconciledQty(r.id));
            BigDecimal qty = selection.qty == null ? remaining.negate() : selection.qty;
            if (qty.signum() >= 0 || qty.stripTrailingZeros().scale() > 0 || qty.abs().compareTo(remaining) > 0)
                throw new BusinessException("退货对账数量必须为负整数，且不能超过剩余退货数量 " + remaining);
            StatementItem item = new StatementItem();
            item.statementId = statement.id; item.returnId = r.id; item.adjustmentType = ADJUSTMENT_RETURN;
            item.deliveryNo = "退货记录"; item.deliveryDate = r.returnDate; item.orderNo = r.orderNo;
            item.productName = r.productName; item.spec = r.spec; item.unit = r.unit;
            item.qty = qty; item.unitPrice = r.unitPrice; item.amount = MoneyUtil.money(qty.multiply(r.unitPrice));
            statementItemRepository.save(item);
        }
        validateAdjustedStatementTotal(statement, null, null);
        recalcStatementAmount(statement);
    }

    private void validateStatementSelectionQty(DeliveryNote delivery, BigDecimal selectedQty,
                                               BigDecimal remainingQty) {
        if (selectedQty == null || selectedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("对账数量必须大于0：" + delivery.deliveryNo);
        }
        if (selectedQty.stripTrailingZeros().scale() > 0) {
            throw new BusinessException("对账数量必须为整数：" + delivery.deliveryNo);
        }
        if (selectedQty.compareTo(remainingQty) > 0) {
            throw new BusinessException("对账数量不能超过剩余可对账数量 " + remainingQty + "：" + delivery.deliveryNo);
        }
    }

    @Transactional
    public Invoice registerInvoice(Invoice invoice) {
        if (invoice.statementId == null) {
            throw new BusinessException("请选择需要开票的对账单");
        }
        Statement statement = statementRepository.findById(invoice.statementId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("对账单不存在"));
        if (invoice.invoiceNo == null || invoice.invoiceNo.isBlank()) {
            throw new BusinessException("请输入发票号");
        }
        invoice.invoiceNo = invoice.invoiceNo.trim();
        if (NON_INVOICE_LABELS.contains(invoice.invoiceNo)) {
            throw new BusinessException("这是结算备注，不是发票号，请保留为未开票账款");
        }
        ensureSettlementMethod(statement, METHOD_INVOICE);
        invoice.amount = MoneyUtil.money(MoneyUtil.nvl(invoice.amount));
        if (invoice.amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("开票金额必须大于0");
        BigDecimal otherInvoices = invoiceRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> invoice.id == null || !Objects.equals(item.id, invoice.id))
                .map(item -> MoneyUtil.nvl(item.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (otherInvoices.add(invoice.amount).compareTo(MoneyUtil.nvl(statement.receivableAmount)) > 0) {
            throw new BusinessException("累计开票金额不能超过对账单金额");
        }
        invoice.statementNo = statement.statementNo;
        invoice.customerId = statement.customerId;
        invoice.customerName = statement.customerName;
        if (invoice.invoiceDate == null) invoice.invoiceDate = LocalDate.now();
        invoice.status = invoice.status == null ? "已开票" : invoice.status;
        if (statement.registeredAt == null) statement.registeredAt = LocalDateTime.now();
        statementRepository.save(statement);
        Invoice saved = invoiceRepository.save(invoice);
        saved.statementNo = statement.statementNo;
        synchronizeHistoricalLedgerLinks(statement.id);
        recalcStatement(invoice.statementId);
        syncStatementInvoicesToDeliveries(invoice.statementId);
        return saved;
    }

    @Transactional
    public Invoice updateInvoice(Long id, Invoice invoice) {
        Invoice existing = invoiceRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("发票不存在"));
        Long oldStatementId = existing.statementId;
        invoice.id = id;
        invoice.createdAt = existing.createdAt;
        invoice.deleted = existing.deleted;
        Invoice saved = registerInvoice(invoice);
        if (oldStatementId != null && !Objects.equals(oldStatementId, saved.statementId)) {
            recalcStatement(oldStatementId);
            syncStatementInvoicesToDeliveries(oldStatementId);
        }
        return saved;
    }

    @Transactional
    public void deleteInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("发票不存在"));
        Long statementId = invoice.statementId;
        attachmentRepository.deleteAll(attachmentRepository.findByBusinessTypeAndBusinessIdAndDeletedFalse("INVOICE_ATTACHMENT", invoice.id));
        invoiceRepository.delete(invoice);
        if (statementId != null) {
            synchronizeHistoricalLedgerLinks(statementId);
            recalcStatement(statementId);
            syncStatementInvoicesToDeliveries(statementId);
        }
    }

    @Transactional
    public Receipt registerReceipt(Receipt receipt) {
        if (receipt.statementId == null) throw new BusinessException("请选择需要收款的对账单");
        if (receipt.id == null && receipt.paymentId == null && !Boolean.TRUE.equals(receipt.customerLevelPayment)) {
            FinanceGroup group = financeGroups().stream().filter(g -> g.statements.stream()
                    .anyMatch(s -> Objects.equals(s.id, receipt.statementId))).findFirst().orElseThrow();
            CustomerPaymentRequest request = new CustomerPaymentRequest();
            request.statementId = receipt.statementId;
            request.amount = receipt.amount;
            request.paymentDate = receipt.receiptDate;
            request.method = receipt.method;
            request.remark = receipt.remark;
            Map<String, Object> saved = registerCustomerPayment(group.groupKey, request);
            List<Receipt> allocations = receiptRepository.findByPaymentIdAndDeletedFalse((Long) saved.get("id"));
            if (!allocations.isEmpty()) return allocations.get(0);
            // A pure advance has a payment header but no fabricated allocation row.
            receipt.statementId = null;
            receipt.paymentId = (Long) saved.get("id");
            receipt.receiptNo = (String) saved.get("recordNo");
            return receipt;
        }
        Statement statement = statementRepository.findById(receipt.statementId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("对账单不存在"));
        if (statement.settlementMethod == null || statement.settlementMethod.isBlank()) {
            ensureSettlementMethod(statement, METHOD_CASH);
        }
        receipt.amount = MoneyUtil.money(MoneyUtil.nvl(receipt.amount));
        if (receipt.amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("到账金额必须大于0");
        BigDecimal otherReceipts = receiptRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> receipt.id == null || !Objects.equals(item.id, receipt.id))
                .map(item -> MoneyUtil.nvl(item.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal historicalBaseline = MoneyUtil.money(statement.historicalReceivedAmount);
        if (otherReceipts.add(receipt.amount).add(historicalBaseline)
                .compareTo(MoneyUtil.nvl(statement.receivableAmount)) > 0) {
            throw new BusinessException("到账金额不能超过剩余欠款");
        }
        if (receipt.receiptDate == null && receipt.paymentId == null) receipt.receiptDate = LocalDate.now();
        if (receipt.receiptNo == null || receipt.receiptNo.isBlank()) {
            receipt.receiptNo = documentNumberService.nextReceipt(receipt.receiptDate, "收款单号生成失败，请稍后重试");
        }
        receipt.customerId = statement.customerId;
        receipt.customerName = statement.customerName;
        if (receipt.method == null || receipt.method.isBlank()) {
            receipt.method = METHOD_INVOICE.equals(statement.settlementMethod) ? "开票回款" : "现汇";
        }
        if (statement.registeredAt == null) statement.registeredAt = LocalDateTime.now();
        statementRepository.save(statement);
        Receipt saved = receiptRepository.save(receipt);
        recalcStatement(receipt.statementId);
        return saved;
    }

    @Transactional
    public Receipt updateReceipt(Long id, Receipt receipt) {
        Receipt existing = receiptRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("收款记录不存在"));
        if (existing.paymentId != null) {
            ReceiptPayment payment = paymentRepository.findById(existing.paymentId).orElseThrow();
            CustomerPaymentRequest request = new CustomerPaymentRequest();
            request.statementId = payment.statementId;
            request.amount = receipt.amount;
            request.paymentDate = receipt.receiptDate;
            request.method = receipt.method;
            request.remark = receipt.remark;
            updateCustomerPayment(payment.groupKey, payment.paymentNo, request);
            return receiptRepository.findByPaymentIdAndDeletedFalse(payment.id).stream().findFirst().orElse(existing);
        }
        if (Boolean.TRUE.equals(existing.customerLevelPayment)) {
            throw new BusinessException("公司级到账记录请在公司收款流水中修改");
        }
        Receipt updated = new Receipt();
        updated.id = existing.id;
        updated.createdAt = existing.createdAt;
        updated.createdBy = existing.createdBy;
        updated.updatedBy = existing.updatedBy;
        updated.deleted = existing.deleted;
        updated.receiptNo = existing.receiptNo;
        updated.statementId = existing.statementId;
        updated.invoiceId = existing.invoiceId;
        updated.orderId = existing.orderId;
        updated.orderNo = existing.orderNo;
        updated.customerId = existing.customerId;
        updated.customerName = existing.customerName;
        updated.paymentBatchNo = existing.paymentBatchNo;
        updated.ledgerAccountId = existing.ledgerAccountId;
        updated.ledgerEntryId = existing.ledgerEntryId;
        updated.ledgerPreviousPaymentDate = existing.ledgerPreviousPaymentDate;
        updated.customerLevelPayment = existing.customerLevelPayment;
        updated.receiptDate = receipt.receiptDate;
        updated.amount = receipt.amount;
        updated.method = receipt.method;
        updated.bankSerial = receipt.bankSerial;
        updated.remark = receipt.remark;
        return registerReceipt(updated);
    }

    @Transactional
    public void deleteReceipt(Long id) {
        Receipt receipt = receiptRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.deleted) || (item.paymentId != null
                        && paymentRepository.findById(item.paymentId).filter(p -> !Boolean.TRUE.equals(p.deleted)).isPresent()))
                .orElseThrow(() -> new BusinessException("收款记录不存在"));
        if (receipt.paymentId != null) {
            ReceiptPayment payment = paymentRepository.findById(receipt.paymentId).orElseThrow();
            deleteCustomerPayment(payment.groupKey, payment.paymentNo);
            return;
        }
        if (Boolean.TRUE.equals(receipt.customerLevelPayment)) {
            throw new BusinessException("公司级到账记录请在公司收款流水中删除");
        }
        Long statementId = receipt.statementId;
        receiptRepository.delete(receipt);
        if (statementId != null) recalcStatement(statementId);
    }

    public Map<String, Object> statementDetail(Long id) {
        Statement statement = statementRepository.findById(id).orElseThrow(() -> new BusinessException("账单不存在"));
        List<Invoice> invoices = invoiceRepository.findByStatementIdAndDeletedFalse(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("statement", statement);
        detail.put("items", statementItemRepository.findByStatementIdAndDeletedFalse(id).stream()
                .sorted(Comparator.comparing((StatementItem item) -> ADJUSTMENT_RETURN.equals(item.adjustmentType))
                        .thenComparing(item -> item.deliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.id)).toList());
        detail.put("invoices", invoices);
        detail.put("receipts", receiptRepository.findByStatementIdAndDeletedFalse(id).stream().peek(receipt -> {
            if (receipt.paymentId != null) receipt.paymentNo = paymentRepository.findById(receipt.paymentId)
                    .map(p -> p.paymentNo).orElse(receipt.receiptNo);
        }).toList());
        detail.put("invoiceAttachments", invoiceAttachmentMap(invoices));
        return detail;
    }

    @Transactional
    public Statement updateStatementAmount(Long statementId, StatementAmountRequest request) {
        Statement statement = activeStatement(statementId);
        FinanceGroup group = financeGroups().stream()
                .filter(candidate -> candidate.statements.stream()
                        .anyMatch(item -> Objects.equals(item.id, statementId)))
                .findFirst()
                .orElseThrow(() -> new BusinessException("对账单未关联客户财务记录"));
        lockPaymentGroup(group);
        statement = activeStatement(statementId);
        recalcStatement(statement);

        BigDecimal requestedAmount = request == null ? BigDecimal.ZERO
                : MoneyUtil.money(MoneyUtil.nvl(request.receivableAmount));
        if (requestedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("对账单金额必须大于0");
        }
        BigDecimal financialFloor = MoneyUtil.money(MoneyUtil.nvl(statement.invoicedAmount)
                .max(MoneyUtil.nvl(statement.receivedAmount)));
        if (requestedAmount.compareTo(financialFloor) < 0) {
            throw new BusinessException("对账单金额不能低于已开票或已到账金额 " + financialFloor);
        }

        if (Boolean.TRUE.equals(statement.sourceAmountOnly)) {
            statement.receivableAmount = requestedAmount;
            statementRepository.save(statement);
        } else {
            List<StatementItem> items = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id);
            List<StatementItem> amountAdjustments = items.stream()
                    .filter(item -> ADJUSTMENT_AMOUNT.equals(item.adjustmentType)).toList();
            BigDecimal baseAmount = MoneyUtil.money(items.stream()
                    .filter(item -> !ADJUSTMENT_AMOUNT.equals(item.adjustmentType))
                    .map(item -> MoneyUtil.nvl(item.amount)).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal difference = MoneyUtil.money(requestedAmount.subtract(baseAmount));

            if (difference.compareTo(BigDecimal.ZERO) == 0) {
                statementItemRepository.deleteAll(amountAdjustments);
            } else {
                StatementItem adjustment = amountAdjustments.isEmpty() ? new StatementItem() : amountAdjustments.get(0);
                adjustment.statementId = statement.id;
                adjustment.deliveryId = null;
                adjustment.deliveryItemId = null;
                adjustment.returnId = null;
                adjustment.deliveryNo = "金额调整";
                adjustment.orderNo = null;
                adjustment.productName = "账单金额调整";
                adjustment.spec = null;
                adjustment.unit = null;
                adjustment.qty = null;
                adjustment.unitPrice = null;
                adjustment.amount = difference;
                adjustment.deliveryDate = statement.generatedAt == null ? null : statement.generatedAt.toLocalDate();
                adjustment.adjustmentType = ADJUSTMENT_AMOUNT;
                statementItemRepository.save(adjustment);
                if (amountAdjustments.size() > 1) {
                    statementItemRepository.deleteAll(amountAdjustments.subList(1, amountAdjustments.size()));
                }
            }
            statementItemRepository.flush();
            recalcStatementAmount(statement);
        }
        replayPayments(group);
        return activeStatement(statementId);
    }

    public Map<String, Object> invoiceDetail(Long id) {
        Invoice invoice = invoiceRepository.findById(id).orElseThrow(() -> new BusinessException("发票不存在"));
        fillStatementNo(invoice);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("invoice", invoice);
        detail.put("attachments", attachmentRepository.findByBusinessTypeAndBusinessIdAndDeletedFalse("INVOICE_ATTACHMENT", id));
        return detail;
    }

    public List<Invoice> listInvoices() {
        return invoiceRepository.findAll().stream()
                .filter(invoice -> invoice.deleted == null || !invoice.deleted)
                .peek(this::fillStatementNo)
                .toList();
    }

    public List<Map<String, Object>> collectionBoard() {
        return collectionRows(activeStatements());
    }

    public List<Map<String, Object>> invoicedStatementBoard() {
        return collectionRows(activeStatements().stream()
                .filter(statement -> METHOD_INVOICE.equals(statement.settlementMethod))
                .filter(statement -> Set.of(STATUS_INVOICED_PENDING, STATUS_PARTIALLY_PAID, STATUS_PAID)
                        .contains(statement.settlementStatus))
                .toList());
    }

    public List<Map<String, Object>> invoiceStatementCandidates() {
        return collectionRows(activeStatements().stream()
                .filter(statement -> statement.settlementMethod == null
                        || statement.settlementMethod.isBlank()
                        || METHOD_INVOICE.equals(statement.settlementMethod))
                .filter(statement -> MoneyUtil.nvl(statement.invoicedAmount)
                        .compareTo(MoneyUtil.nvl(statement.receivableAmount)) < 0)
                .toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> collectionCompanyBoard() {
        List<Map<String, Object>> fastRows = fastCollectionCompanyBoard();
        List<Map<String, Object>> rows = fastRows == null ? collectionCompanyBoardFallback() : fastRows;
        addCompanyPaymentActivity(rows);
        return rows;
    }

    private void addCompanyPaymentActivity(List<Map<String, Object>> rows) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        Map<String, LocalDate> latestDates = new HashMap<>();
        Set<String> undatedGroups = new HashSet<>();
        for (var activity : paymentRepository.paymentActivityByGroup(today, NON_PAYMENT_METHODS)) {
            if (activity.getLatestPaymentDate() != null)
                latestDates.put(activity.getGroupKey(), activity.getLatestPaymentDate());
            if (activity.getUndatedPaymentCount() > 0) undatedGroups.add(activity.getGroupKey());
        }

        // Only receipts without a payment header are original payments. Allocations may have later bill dates.
        List<Receipt> legacy = receiptRepository.findByPaymentIdIsNullAndDeletedFalse().stream()
                .filter(r -> MoneyUtil.nvl(r.amount).signum() > 0 && !NON_PAYMENT_METHODS.contains(r.method == null ? "" : r.method))
                .filter(r -> r.receiptDate == null || !r.receiptDate.isAfter(today)).toList();
        if (!legacy.isEmpty()) {
            Map<Long, String> customerGroups = new HashMap<>();
            Map<String, String> namedGroups = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("groupKey");
                if (row.get("customerId") instanceof Long id) customerGroups.put(id, key);
                if (row.get("customerName") instanceof String name) namedGroups.putIfAbsent(name.trim(), key);
            }
            Set<Long> accountIds = legacy.stream().map(r -> r.ledgerAccountId).filter(Objects::nonNull).collect(Collectors.toSet());
            Set<Long> statementIds = legacy.stream().map(r -> r.statementId).filter(Objects::nonNull).collect(Collectors.toSet());
            Map<Long, String> statementGroups = new HashMap<>();
            for (Statement statement : statementRepository.findAllById(statementIds)) {
                String key = customerGroups.get(statement.customerId);
                if (key == null && statement.ledgerAccountId != null) {
                    accountIds.add(statement.ledgerAccountId);
                    key = "ledger-" + statement.ledgerAccountId;
                }
                if (key == null) key = statement.customerId != null ? "statement-" + statement.customerId
                        : "statement-name-" + Integer.toUnsignedString(
                                (statement.customerName == null ? "未命名客户" : statement.customerName.trim()).hashCode());
                statementGroups.put(statement.id, key);
            }
            Map<Long, String> accountGroups = new HashMap<>();
            for (CustomerLedgerAccount account : ledgerAccountRepository.findAllById(accountIds)) {
                String key = customerGroups.get(account.customerId);
                if (key == null && account.sheetName != null) key = namedGroups.get(account.sheetName.trim());
                accountGroups.put(account.id, key == null ? "ledger-" + account.id : key);
            }
            for (Receipt receipt : legacy) {
                Set<String> keys = new HashSet<>();
                keys.add(customerGroups.get(receipt.customerId));
                keys.add(accountGroups.get(receipt.ledgerAccountId));
                keys.add(statementGroups.get(receipt.statementId));
                for (String key : keys) {
                    if (key == null) continue;
                    if (receipt.receiptDate == null || (receipt.remark != null && receipt.remark.contains("日期待核实")))
                        undatedGroups.add(key);
                    else latestDates.merge(key, receipt.receiptDate, (a, b) -> a.isAfter(b) ? a : b);
                }
            }
        }
        YearMonth currentMonth = YearMonth.from(today);
        for (Map<String, Object> row : rows) {
            String key = (String) row.get("groupKey");
            LocalDate latest = latestDates.get(key);
            row.put("latestPaymentDate", latest);
            row.put("paidThisMonth", latest != null && YearMonth.from(latest).equals(currentMonth));
            row.put("hasUndatedPayments", undatedGroups.contains(key));
        }
    }

    private List<Map<String, Object>> fastCollectionCompanyBoard() {
        LocalDate yearStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfYear(1);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("yearStart", yearStart)
                .addValue("yearEnd", yearStart.plusYears(1));
        return jdbcTemplate.query(COLLECTION_COMPANY_BOARD_SQL, parameters, resultSet -> {
            Map<String, CollectionBoardJdbcGroup> groups = new LinkedHashMap<>();
            boolean fallbackRequired = false;
            while (resultSet.next()) {
                String rowType = resultSet.getString("row_type");
                if ("META".equals(rowType)) {
                    fallbackRequired = resultSet.getInt("fallback_required") != 0;
                    continue;
                }
                String groupKey = resultSet.getString("group_key");
                if ("BASE".equals(rowType)) {
                    CollectionBoardJdbcGroup group = groups.computeIfAbsent(
                            groupKey, ignored -> new CollectionBoardJdbcGroup());
                    group.groupKey = groupKey;
                    group.customerId = resultSet.getObject("customer_id", Long.class);
                    group.customerName = resultSet.getString("customer_name");
                    group.contactPerson = resultSet.getString("contact_person");
                    group.phone = resultSet.getString("phone");
                    group.statementCount += Math.toIntExact(resultSet.getLong("statement_count"));
                    group.totalSalesAmount = MoneyUtil.money(resultSet.getBigDecimal("total_sales_amount"));
                    group.unpaidStatementCount += Math.toIntExact(resultSet.getLong("unpaid_statement_count"));
                    group.receivableAmount = group.receivableAmount.add(
                            MoneyUtil.nvl(resultSet.getBigDecimal("statement_receivable")));
                    group.invoicedAmount = group.invoicedAmount.add(
                            MoneyUtil.nvl(resultSet.getBigDecimal("statement_invoiced")));
                    group.invoicedUnpaidAmount = group.invoicedUnpaidAmount.add(
                            MoneyUtil.nvl(resultSet.getBigDecimal("statement_invoiced_unpaid")));
                    group.receivedAmount = group.receivedAmount.add(
                            MoneyUtil.nvl(resultSet.getBigDecimal("statement_received")));
                    group.currentDebt = group.currentDebt.add(
                            MoneyUtil.nvl(resultSet.getBigDecimal("statement_debt")));
                    group.creditBalance = MoneyUtil.money(resultSet.getBigDecimal("credit_balance"));
                    group.sourceNetAmount = resultSet.getInt("has_source_net") == 0
                            ? null : MoneyUtil.money(resultSet.getBigDecimal("source_net_amount"));
                    group.latestStatementAt = resultSet.getObject("latest_statement_at", LocalDateTime.class);
                } else if ("LEDGER".equals(rowType)) {
                    CollectionBoardJdbcGroup group = groups.get(groupKey);
                    if (group == null) {
                        group = new CollectionBoardJdbcGroup();
                        group.groupKey = groupKey;
                        groups.put(groupKey, group);
                    }
                    group.addLedgerEntry(
                            resultSet.getBigDecimal("ledger_amount"),
                            resultSet.getString("ledger_invoice_no"),
                            resultSet.getBigDecimal("ledger_paid_amount"),
                            resultSet.getBigDecimal("ledger_remaining_amount"));
                }
            }
            if (fallbackRequired || groups.values().stream().anyMatch(group -> group.customerId == null)) return null;
            return groups.values().stream()
                    .map(CollectionBoardJdbcGroup::toRow)
                    .sorted(Comparator
                            .comparing((Map<String, Object> row) -> (BigDecimal) row.get("arrearsAmount"), Comparator.reverseOrder())
                            .thenComparing(row -> String.valueOf(row.get("customerName"))))
                    .toList();
        });
    }

    private List<Map<String, Object>> collectionCompanyBoardFallback() {
        LocalDate yearStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfYear(1);
        Map<Long, BigDecimal> salesByCustomer = salesOrderRepository
                .sumActiveOrderAmountsByCustomerAndDateRange(yearStart, yearStart.plusYears(1)).stream()
                .collect(Collectors.toMap(SalesOrderRepository.CustomerSalesTotal::getCustomerId,
                        total -> MoneyUtil.money(total.getTotalAmount())));
        List<Customer> customers = customerRepository.findAll().stream()
                .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                .filter(customer -> customer.id != null)
                .toList();
        Map<Long, Customer> customersById = customers.stream()
                .collect(Collectors.toMap(customer -> customer.id, Function.identity()));
        Map<String, Customer> customersByName = customers.stream()
                .filter(customer -> customer.name != null && !customer.name.isBlank())
                .collect(Collectors.toMap(customer -> customer.name.trim(), Function.identity(), (first, ignored) -> first));
        Map<String, CollectionBoardGroup> groups = new LinkedHashMap<>();
        customers.forEach(customer -> groups.put("customer-" + customer.id,
                new CollectionBoardGroup("customer-" + customer.id, customer, customer.name)));

        List<CustomerLedgerAccount> accounts = ledgerAccountRepository.findByDeletedFalse();
        for (CustomerLedgerAccount account : accounts) {
            Customer customer = account.customerId == null ? null : customersById.get(account.customerId);
            if (customer == null && account.sheetName != null) customer = customersByName.get(account.sheetName.trim());
            Customer selectedCustomer = customer;
            String groupKey = selectedCustomer == null ? "ledger-" + account.id : "customer-" + selectedCustomer.id;
            CollectionBoardGroup group = groups.computeIfAbsent(groupKey,
                    ignored -> new CollectionBoardGroup(groupKey, selectedCustomer,
                            selectedCustomer == null ? account.sheetName : selectedCustomer.name));
            group.accounts.add(account);
        }

        for (StatementRepository.CustomerCollectionTotal total : statementRepository.collectionTotalsByCustomer()) {
            Customer customer = customersById.get(total.getCustomerId());
            String groupKey = customer == null ? "statement-" + total.getCustomerId() : "customer-" + customer.id;
            CollectionBoardGroup group = groups.computeIfAbsent(groupKey,
                    ignored -> new CollectionBoardGroup(groupKey, customer,
                            customer == null ? total.getCustomerName() : customer.name));
            group.add(total);
        }
        for (Statement statement : statementRepository.findUnmappedCollectionStatements()) {
            String groupKey = statement.ledgerAccountId != null
                    ? "ledger-" + statement.ledgerAccountId
                    : "statement-name-" + Integer.toUnsignedString(
                            (statement.customerName == null ? "未命名客户" : statement.customerName.trim()).hashCode());
            CollectionBoardGroup group = groups.computeIfAbsent(groupKey,
                    ignored -> new CollectionBoardGroup(groupKey, null, statement.customerName));
            group.add(statement);
        }

        List<Long> accountIds = accounts.stream().map(account -> account.id)
                .filter(Objects::nonNull).toList();
        List<CustomerLedgerEntry> ledgerEntries = accountIds.isEmpty() ? List.of()
                : ledgerEntryRepository.findByAccountIdInAndDeletedFalse(accountIds);
        Map<Long, String> groupKeysByAccount = groups.values().stream()
                .flatMap(group -> group.accounts.stream().map(account -> Map.entry(account.id, group.groupKey)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Set<Long> linkedStatementIds = ledgerEntries.stream().map(entry -> entry.linkedStatementId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> activeLinkedStatementIds = linkedStatementIds.isEmpty() ? Set.of()
                : statementRepository.findAllById(linkedStatementIds).stream()
                        .filter(statement -> !Boolean.TRUE.equals(statement.deleted))
                        .filter(statement -> !Boolean.TRUE.equals(statement.collectionExcluded))
                        .map(statement -> statement.id).collect(Collectors.toSet());
        ledgerEntries.stream()
                .filter(entry -> entry.linkedStatementId == null
                        || !activeLinkedStatementIds.contains(entry.linkedStatementId))
                .forEach(entry -> {
                    CollectionBoardGroup group = groups.get(groupKeysByAccount.get(entry.accountId));
                    if (group != null) group.ledgerEntries.add(entry);
                });

        Map<String, BigDecimal> unallocatedPaymentsByGroup = paymentRepository.sumUnallocatedAmountByGroup().stream()
                .collect(Collectors.toMap(ReceiptPaymentRepository.GroupCreditTotal::getGroupKey,
                        total -> MoneyUtil.nvl(total.getUnallocatedAmount())));
        return groups.values().stream()
                .filter(group -> group.customer != null || group.statementCount > 0 || !group.ledgerEntries.isEmpty())
                .map(group -> collectionCompanyBoardRow(group, salesByCustomer,
                        unallocatedPaymentsByGroup.getOrDefault(group.groupKey, BigDecimal.ZERO)))
                .sorted(Comparator
                        .comparing((Map<String, Object> row) -> (BigDecimal) row.get("arrearsAmount"), Comparator.reverseOrder())
                        .thenComparing(row -> String.valueOf(row.get("customerName"))))
                .toList();
    }

    private Map<String, Object> collectionCompanyBoardRow(CollectionBoardGroup group,
                                                           Map<Long, BigDecimal> salesByCustomer,
                                                           BigDecimal unallocatedPayments) {
        BigDecimal ledgerReceivable = group.ledgerEntries.stream()
                .map(entry -> MoneyUtil.nvl(entry.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerInvoiced = group.ledgerEntries.stream()
                .filter(entry -> !invoiceNumberTokens(entry.invoiceNo).isEmpty())
                .map(entry -> MoneyUtil.nvl(entry.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerInvoicedUnpaid = group.ledgerEntries.stream()
                .filter(entry -> !invoiceNumberTokens(entry.invoiceNo).isEmpty())
                .map(entry -> MoneyUtil.nvl(entry.remainingAmount))
                .filter(amount -> amount.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerReceived = group.ledgerEntries.stream()
                .map(entry -> MoneyUtil.nvl(entry.paidAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ledgerDebt = group.ledgerEntries.stream()
                .map(entry -> MoneyUtil.nvl(entry.remainingAmount))
                .filter(amount -> amount.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int ledgerUnpaidCount = Math.toIntExact(group.ledgerEntries.stream()
                .filter(entry -> MoneyUtil.nvl(entry.remainingAmount).compareTo(BigDecimal.ZERO) > 0)
                .count());
        BigDecimal creditBalance = group.accounts.stream()
                .map(account -> MoneyUtil.nvl(account.creditBalance))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(MoneyUtil.nvl(unallocatedPayments));
        BigDecimal currentDebt = ledgerDebt.add(group.statementDebt);
        BigDecimal arrearsAmount = currentDebt.subtract(creditBalance);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupKey", group.groupKey);
        row.put("customerId", group.customer == null ? null : group.customer.id);
        row.put("customerName", group.displayName());
        row.put("contactPerson", group.customer == null ? null : group.customer.contactPerson);
        row.put("phone", group.customer == null ? null : group.customer.phone);
        row.put("statementCount", group.statementCount + group.ledgerEntries.size());
        row.put("totalSalesAmount", group.customer == null ? null
                : salesByCustomer.getOrDefault(group.customer.id, MoneyUtil.money(BigDecimal.ZERO)));
        row.put("unpaidStatementCount", group.unpaidStatementCount + ledgerUnpaidCount);
        row.put("receivableAmount", MoneyUtil.money(group.statementReceivable.add(ledgerReceivable)));
        row.put("invoicedAmount", MoneyUtil.money(group.statementInvoiced.add(ledgerInvoiced)));
        row.put("invoicedUnpaidAmount", MoneyUtil.money(
                group.statementInvoicedUnpaid.add(ledgerInvoicedUnpaid)));
        row.put("receivedAmount", MoneyUtil.money(group.statementReceived.add(ledgerReceived).add(creditBalance)));
        row.put("arrearsAmount", MoneyUtil.money(arrearsAmount));
        row.put("creditBalance", MoneyUtil.money(creditBalance));
        if (group.accounts.stream().anyMatch(account -> account.sourceNetAmount != null)) {
            BigDecimal sourceNetAmount = group.accounts.stream()
                    .map(account -> MoneyUtil.nvl(account.sourceNetAmount))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            row.put("sourceNetAmount", MoneyUtil.money(sourceNetAmount));
            row.put("reconciliationDifference", MoneyUtil.money(arrearsAmount.subtract(sourceNetAmount)));
        } else {
            row.put("sourceNetAmount", null);
            row.put("reconciliationDifference", null);
        }
        row.put("latestStatementAt", group.latestStatementAt);
        return row;
    }

    @Transactional(readOnly = true)
    public BigDecimal collectionInvoicedUnpaidTotal() {
        return MoneyUtil.money(collectionCompanyBoard().stream()
                .map(row -> (BigDecimal) row.get("invoicedUnpaidAmount"))
                .map(MoneyUtil::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Same payment records as company collection details, counted once by payment date.
     * Includes non-cash offsets and advances; allocation rows are never added to their headers.
     */
    @Transactional(readOnly = true)
    public BigDecimal collectionYearReceivedTotal(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = start.plusYears(1);
        List<FinanceGroup> groups = financeGroups();
        if (groups.isEmpty()) return MoneyUtil.money(BigDecimal.ZERO);
        BigDecimal total = MoneyUtil.nvl(paymentRepository.sumPaymentsInPeriod(
                groups.stream().map(g -> g.groupKey).toList(), start, end));
        Set<Long> statementIds = groups.stream().flatMap(g -> g.statements.stream())
                .map(s -> s.id).collect(Collectors.toSet());
        Set<Long> accountIds = groups.stream().flatMap(g -> g.accounts.stream())
                .map(a -> a.id).collect(Collectors.toSet());
        Set<Long> customerIds = groups.stream().filter(g -> g.customer != null)
                .map(g -> g.customer.id).collect(Collectors.toSet());
        // Legacy receipts without a payment header remain visible in collection details.
        for (Receipt receipt : receiptRepository
                .findByPaymentIdIsNullAndDeletedFalseAndReceiptDateGreaterThanEqualAndReceiptDateLessThan(start, end)) {
            boolean visible = statementIds.contains(receipt.statementId)
                    || Boolean.TRUE.equals(receipt.customerLevelPayment)
                    && (accountIds.contains(receipt.ledgerAccountId) || customerIds.contains(receipt.customerId));
            if (visible) total = total.add(MoneyUtil.nvl(receipt.amount));
        }
        // Preserve the dated historical-baseline rows exposed by the collection screen.
        for (FinanceGroup group : groups) {
            for (Statement statement : group.statements) {
                BigDecimal baseline = MoneyUtil.nvl(statement.historicalReceivedAmount);
                if (baseline.signum() <= 0) continue;
                List<CustomerLedgerEntry> entries = ledgerEntryRepository.findByLinkedStatementIdAndDeletedFalse(statement.id);
                LocalDate date = entries.stream().map(e -> e.lastPaymentDate).filter(Objects::nonNull)
                        .max(LocalDate::compareTo).orElseGet(() -> entries.stream().map(e -> e.transactionDate)
                                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
                if (date != null && !date.isBefore(start) && date.isBefore(end)) total = total.add(baseline);
            }
        }
        return MoneyUtil.money(total);
    }

    public Map<String, Object> collectionCompanyDetail(String groupKey) {
        return customerSummaryDetail(groupKey);
    }

    public List<Statement> listStatements() {
        return activeStatements();
    }

    public Map<String, Object> collectionDetail(Long statementId) {
        return statementDetail(statementId);
    }

    @Transactional
    public Map<String, Object> registerCollection(Long statementId, CollectionRegisterRequest request) {
        Statement statement = activeStatement(statementId);
        String method = normalizeMethod(request.method);
        ensureSettlementMethod(statement, method);
        if (statement.registeredAt == null) statement.registeredAt = LocalDateTime.now();
        statementRepository.save(statement);

        if (METHOD_INVOICE.equals(method)) {
            Invoice invoice = new Invoice();
            invoice.statementId = statement.id;
            invoice.invoiceNo = request.invoiceNo;
            invoice.invoiceDate = request.transactionDate;
            invoice.amount = request.amount == null ? statement.receivableAmount : request.amount;
            invoice.taxRate = request.taxRate;
            invoice.remark = request.remark;
            registerInvoice(invoice);
        } else if (Boolean.TRUE.equals(request.arrived)) {
            Receipt receipt = new Receipt();
            receipt.statementId = statement.id;
            receipt.receiptDate = request.transactionDate;
            receipt.amount = request.amount == null ? statement.arrearsAmount : request.amount;
            receipt.method = "现汇";
            receipt.bankSerial = request.bankSerial;
            receipt.remark = request.remark;
            registerReceipt(receipt);
        } else {
            recalcStatement(statement);
            statementRepository.save(statement);
        }
        return statementDetail(statement.id);
    }

    @Transactional
    public Receipt registerCollectionReceipt(Long statementId, Receipt receipt) {
        receipt.id = null;
        receipt.paymentId = null;
        receipt.customerLevelPayment = false;
        Statement statement = activeStatement(statementId);
        if (statement.settlementMethod == null || statement.settlementMethod.isBlank()) {
            throw new BusinessException("请先选择现汇或开票并完成登记");
        }
        receipt.statementId = statement.id;
        return registerReceipt(receipt);
    }

    public List<Map<String, Object>> customerSummaries() {
        return financeGroups().stream()
                .map(group -> groupSummaryRow(group, groupLedgerData(group)))
                .sorted(Comparator
                        .comparing((Map<String, Object> row) -> (BigDecimal) row.get("currentDebt"), Comparator.reverseOrder())
                        .thenComparing(row -> String.valueOf(row.get("customerName"))))
                .toList();
    }

    public Map<String, Object> customerSummaryDetail(String groupKey) {
        FinanceGroup group = findFinanceGroup(groupKey);
        GroupLedgerData data = groupLedgerData(group);
        Map<Long, CustomerLedgerAccount> accountsById = group.accounts.stream()
                .collect(Collectors.toMap(account -> account.id, account -> account));
        List<CustomerLedgerEntry> entries = data.entries.stream()
                .sorted(Comparator
                        .comparing((CustomerLedgerEntry entry) -> MoneyUtil.nvl(entry.remainingAmount).compareTo(BigDecimal.ZERO) > 0 ? 0 : 1)
                        .thenComparing(entry -> entry.transactionDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(entry -> entry.sourceRow, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        Map<String, Object> customerInfo = new LinkedHashMap<>();
        customerInfo.put("id", group.customer == null ? null : group.customer.id);
        customerInfo.put("groupKey", group.groupKey);
        customerInfo.put("name", group.displayName());
        customerInfo.put("contactPerson", group.customer == null ? null : group.customer.contactPerson);
        customerInfo.put("phone", group.customer == null ? null : group.customer.phone);
        customerInfo.put("ledgerAccountIds", group.accounts.stream().map(account -> account.id).toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", customerInfo);
        Map<String, Object> summary = groupSummaryRow(group, data);
        LocalDate yearStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).withDayOfYear(1);
        summary.put("totalSalesAmount", group.customer == null ? null
                : MoneyUtil.money(salesOrderRepository.sumActiveOrderAmountByCustomerIdAndDateRange(
                        group.customer.id, yearStart, yearStart.plusYears(1))));
        result.put("summary", summary);
        result.put("statements", collectionRows(sortedStatements(group.statements)));
        result.put("ledgerEntries", entries.stream()
                .map(entry -> ledgerEntryRow(accountsById.get(entry.accountId), entry)).toList());
        result.put("payments", customerPaymentRows(group));
        return result;
    }

    public Map<String, Object> customerSummaryDetail(Long legacyId) {
        return customerSummaryDetail(resolveLegacyGroupKey(legacyId));
    }

    @Transactional
    public Map<String, Object> registerCustomerPayment(String groupKey, CustomerPaymentRequest request) {
        return registerCustomerPayment(groupKey, request, null);
    }

    private Map<String, Object> registerCustomerPayment(String groupKey, CustomerPaymentRequest request,
                                                        String existingBatchNo) {
        FinanceGroup group = findFinanceGroup(groupKey);
        lockPaymentGroup(group);
        BigDecimal amount = MoneyUtil.money(MoneyUtil.nvl(request.amount));
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("收款金额必须大于0");
        ReceiptPayment payment = existingBatchNo == null ? new ReceiptPayment()
                : paymentRepository.findByGroupKeyAndPaymentNoAndDeletedFalse(groupKey, existingBatchNo)
                .orElseThrow(() -> new BusinessException("到账记录不存在"));
        LocalDate paymentDate = request.paymentDate != null ? request.paymentDate
                : payment.id == null ? LocalDate.now() : payment.paymentDate;
        if (request.statementId != null && group.statements.stream().noneMatch(s -> Objects.equals(s.id, request.statementId)))
            throw new BusinessException("对账单不属于当前公司");
        payment.groupKey = groupKey;
        payment.customerId = group.customer == null ? null : group.customer.id;
        // A statement entry point selects the company, never the allocation destination.
        payment.statementId = null;
        payment.paymentDate = paymentDate;
        if (paymentDate != null) payment.sourceSortDate = paymentDate;
        payment.amount = amount;
        payment.method = request.method == null || request.method.isBlank() ? "客户汇款" : request.method.trim();
        payment.remark = request.remark;
        if (payment.paymentNo == null) payment.paymentNo = "PAY-" + paymentDate.toString().replace("-", "")
                + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        paymentRepository.saveAndFlush(payment);
        replayPayments(group);
        Map<String, Object> result = paymentRow(payment);
        result.put("detail", customerSummaryDetail(group.groupKey));
        return result;
    }

    private void lockPaymentGroup(FinanceGroup group) {
        Object owner = group.customer != null ? group.customer
                : !group.accounts.isEmpty() ? group.accounts.get(0)
                : group.statements.stream().min(Comparator.comparing(s -> s.id)).orElseThrow();
        entityManager.lock(owner, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        entityManager.flush();
        group.statements.forEach(entityManager::refresh);
        group.accounts.forEach(entityManager::refresh);
    }

    /** Rebuild the company's FIFO queue atomically, preserving actual payment headers. */
    private void replayPayments(FinanceGroup group) {
        List<ReceiptPayment> payments = paymentRepository.findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(group.groupKey);
        for (ReceiptPayment payment : payments) clearPaymentAllocations(payment);
        receiptRepository.flush();
        group.statements.forEach(this::recalcStatement);
        List<ReceiptPayment> ordered = payments.stream().sorted(Comparator
                .comparing((ReceiptPayment p) -> p.sourceSortDate != null ? p.sourceSortDate : p.paymentDate,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(p -> p.sourceSequence, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(p -> p.id)).toList();
        List<PaymentTarget> targets = groupLedgerData(group).targets;
        Map<PaymentTarget, BigDecimal> available = new LinkedHashMap<>();
        targets.forEach(t -> available.put(t, t.outstanding()));
        for (ReceiptPayment payment : ordered) {
            BigDecimal remaining = payment.amount;
            for (PaymentTarget target : targets) {
                if (remaining.signum() <= 0) break;
                BigDecimal allocated = remaining.min(available.get(target));
                if (allocated.signum() <= 0) continue;
                CustomerPaymentRequest request = new CustomerPaymentRequest();
                request.method = payment.method;
                request.remark = payment.remark;
                Receipt receipt = createCustomerPaymentReceipt(group, target, allocated, payment.paymentDate,
                        payment.paymentNo, request);
                receipt.paymentId = payment.id;
                receipt.allocationDate = payment.paymentDate == null ? null
                        : target.date() != null && target.date().isAfter(payment.paymentDate)
                        ? target.date() : payment.paymentDate;
                receiptRepository.save(receipt);
                remaining = remaining.subtract(allocated);
                available.put(target, available.get(target).subtract(allocated));
            }
            payment.statementId = null;
            payment.unallocatedAmount = MoneyUtil.money(remaining);
            paymentRepository.save(payment);
        }
        group.statements.forEach(s -> { recalcStatement(s); statementRepository.save(s); });
        group.accounts.forEach(this::recalcLedgerAccount);
    }

    private void clearPaymentAllocations(ReceiptPayment payment) {
        for (Receipt receipt : receiptRepository.findByPaymentIdAndDeletedFalse(payment.id)) {
            if (receipt.ledgerEntryId != null) ledgerEntryRepository.findById(receipt.ledgerEntryId).ifPresent(entry -> {
                entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount).subtract(receipt.amount).max(BigDecimal.ZERO));
                entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.amount).subtract(entry.paidAmount));
                entry.lastPaymentDate = receipt.ledgerPreviousPaymentDate;
                ledgerEntryRepository.save(entry);
            });
            receipt.deleted = true;
            receiptRepository.save(receipt);
        }
    }

    private Map<String, Object> paymentRow(ReceiptPayment payment) {
        Map<String, Object> row = new LinkedHashMap<>();
        boolean adjustment = "账款调减".equals(payment.method);
        row.put("recordType", adjustment ? "ADJUSTMENT" : "PAYMENT");
        row.put("readOnly", adjustment);
        row.put("sourceDateEstimated", payment.remark != null && payment.remark.contains("日期待核实"));
        row.put("id", payment.id);
        row.put("recordNo", payment.paymentNo);
        row.put("paymentBatchNo", payment.paymentNo);
        row.put("paymentDate", payment.paymentDate);
        row.put("sourceDateText", payment.sourceDateText);
        row.put("sourceSequence", payment.sourceSequence);
        row.put("amount", payment.amount);
        row.put("unallocatedAmount", payment.unallocatedAmount);
        row.put("method", payment.method);
        row.put("remark", payment.remark);
        row.put("statementId", payment.statementId);
        row.put("statementNo", payment.statementId == null ? "定期还款"
                : statementRepository.findById(payment.statementId).map(s -> s.statementNo).orElse("待核对"));
        List<Map<String, Object>> allocations = receiptRepository.findByPaymentIdAndDeletedFalse(payment.id).stream().map(r -> {
            Map<String, Object> allocation = new LinkedHashMap<>();
            allocation.put("statementId", r.statementId);
            allocation.put("statementNo", r.statementId == null ? "待核对历史账款"
                    : statementRepository.findById(r.statementId).map(s -> s.statementNo).orElse("待核对"));
            allocation.put("amount", r.amount);
            allocation.put("allocationDate", r.allocationDate);
            return allocation;
        }).toList();
        row.put("allocations", allocations);
        row.put("allocationCount", allocations.size());
        return row;
    }

    @Transactional
    public void replayCustomerPayments(String groupKey) {
        FinanceGroup group = findFinanceGroup(groupKey);
        lockPaymentGroup(group);
        replayPayments(group);
    }

    @Transactional
    public Map<String, Object> updateCustomerPayment(String groupKey, String batchNo,
                                                     CustomerPaymentRequest request) {
        if (batchNo == null || batchNo.isBlank()) throw new BusinessException("到账记录不存在");
        if (paymentRepository.findByGroupKeyAndPaymentNoAndDeletedFalse(groupKey, batchNo).isPresent())
            return registerCustomerPayment(groupKey, request, batchNo);
        deleteCustomerPayment(groupKey, batchNo);
        receiptRepository.flush();
        return registerCustomerPayment(groupKey, request, null);
    }

    @Transactional
    public Map<String, Object> registerCustomerPayment(Long legacyId, CustomerPaymentRequest request) {
        return registerCustomerPayment(resolveLegacyGroupKey(legacyId), request);
    }

    @Transactional
    public void deleteCustomerPayment(String groupKey, String batchNo) {
        FinanceGroup group = findFinanceGroup(groupKey);
        lockPaymentGroup(group);
        Optional<ReceiptPayment> payment = paymentRepository.findByGroupKeyAndPaymentNoAndDeletedFalse(groupKey, batchNo);
        if (payment.isPresent()) {
            clearPaymentAllocations(payment.get());
            payment.get().deleted = true;
            paymentRepository.saveAndFlush(payment.get());
            replayPayments(group);
            return;
        }
        Set<Long> accountIds = group.accounts.stream().map(account -> account.id).collect(Collectors.toSet());
        Set<Long> statementIdsInGroup = group.statements.stream().map(statement -> statement.id).collect(Collectors.toSet());
        List<Receipt> receipts = receiptRepository
                .findByPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(batchNo).stream()
                .filter(receipt -> groupContainsReceipt(group, accountIds, statementIdsInGroup, receipt))
                .sorted(Comparator.comparing((Receipt receipt) -> receipt.id).reversed())
                .toList();
        if (receipts.isEmpty()) throw new BusinessException("客户收款记录不存在");
        Set<Long> statementIds = new LinkedHashSet<>();
        Set<Long> affectedAccountIds = new LinkedHashSet<>();
        for (Receipt receipt : receipts) {
            if (receipt.ledgerEntryId != null) {
                CustomerLedgerEntry entry = ledgerEntryRepository.findById(receipt.ledgerEntryId).orElseThrow();
                entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount)
                        .subtract(MoneyUtil.nvl(receipt.amount)).max(BigDecimal.ZERO));
                entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.remainingAmount).add(MoneyUtil.nvl(receipt.amount)));
                entry.lastPaymentDate = receipt.ledgerPreviousPaymentDate;
                ledgerEntryRepository.save(entry);
                affectedAccountIds.add(entry.accountId);
            }
            if (receipt.ledgerAccountId != null) affectedAccountIds.add(receipt.ledgerAccountId);
            if (receipt.statementId != null) statementIds.add(receipt.statementId);
            receiptRepository.delete(receipt);
        }
        statementIds.forEach(this::recalcStatement);
        group.accounts.stream().filter(account -> affectedAccountIds.contains(account.id)).forEach(this::recalcLedgerAccount);
    }

    @Transactional
    public void deleteCustomerPayment(Long legacyId, String batchNo) {
        deleteCustomerPayment(resolveLegacyGroupKey(legacyId), batchNo);
    }

    @Transactional
    public void voidLedgerEntry(String groupKey, Long entryId) {
        FinanceGroup group = findFinanceGroup(groupKey);
        Set<Long> accountIds = group.accounts.stream().map(account -> account.id).collect(Collectors.toSet());
        CustomerLedgerEntry entry = ledgerEntryRepository.findById(entryId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> accountIds.contains(item.accountId))
                .orElseThrow(() -> new BusinessException("历史账页记录不存在"));
        if (MoneyUtil.nvl(entry.paidAmount).compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("该历史账页记录已有到账金额，不能作废");
        }
        if (entry.linkedStatementId != null && statementRepository.findById(entry.linkedStatementId)
                .filter(statement -> !Boolean.TRUE.equals(statement.deleted)).isPresent()) {
            throw new BusinessException("该历史账页记录已关联有效对账单，不能单独作废");
        }
        entry.deleted = true;
        ledgerEntryRepository.save(entry);
        group.accounts.stream().filter(account -> Objects.equals(account.id, entry.accountId))
                .forEach(this::recalcLedgerAccount);
    }

    private List<FinanceGroup> financeGroups() {
        Map<Long, Customer> customers = customerRepository.findAll().stream()
                .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                .filter(customer -> customer.id != null)
                .collect(Collectors.toMap(customer -> customer.id, customer -> customer));
        Map<String, Customer> customersByName = customers.values().stream()
                .filter(customer -> customer.name != null && !customer.name.isBlank())
                .collect(Collectors.toMap(customer -> customer.name.trim(), customer -> customer, (first, ignored) -> first));
        Map<String, FinanceGroup> groups = new LinkedHashMap<>();

        // Customer identity exists before orders, statements or payments do.
        customers.values().forEach(customer -> {
            String key = "customer-" + customer.id;
            groups.put(key, new FinanceGroup(key, customer, customer.name));
        });

        for (CustomerLedgerAccount account : ledgerAccountRepository.findByDeletedFalse()) {
            Customer customer = account.customerId == null ? null : customers.get(account.customerId);
            if (customer == null && account.sheetName != null) customer = customersByName.get(account.sheetName.trim());
            Customer selectedCustomer = customer;
            String key = selectedCustomer == null ? "ledger-" + account.id : "customer-" + selectedCustomer.id;
            FinanceGroup group = groups.computeIfAbsent(key,
                    ignored -> new FinanceGroup(key, selectedCustomer,
                            selectedCustomer == null ? account.sheetName : selectedCustomer.name));
            group.accounts.add(account);
        }

        for (Statement statement : activeStatements()) {
            if (Boolean.TRUE.equals(statement.collectionExcluded)) continue;
            Customer customer = statement.customerId == null ? null : customers.get(statement.customerId);
            String key;
            if (customer == null && statement.ledgerAccountId != null) {
                key = "ledger-" + statement.ledgerAccountId;
            } else if (customer != null) {
                key = "customer-" + customer.id;
            } else if (statement.customerId != null) {
                key = "statement-" + statement.customerId;
            } else {
                String name = statement.customerName == null ? "未命名客户" : statement.customerName.trim();
                key = "statement-name-" + Integer.toUnsignedString(name.hashCode());
            }
            Customer selectedCustomer = customer;
            FinanceGroup group = groups.computeIfAbsent(key,
                    ignored -> new FinanceGroup(key, selectedCustomer,
                            selectedCustomer == null ? statement.customerName : selectedCustomer.name));
            group.statements.add(statement);
        }
        return new ArrayList<>(groups.values());
    }

    private FinanceGroup findFinanceGroup(String groupKey) {
        return financeGroups().stream()
                .filter(group -> group.groupKey.equals(groupKey))
                .findFirst()
                .orElseThrow(() -> new BusinessException("客户财务记录不存在"));
    }

    private String resolveLegacyGroupKey(Long legacyId) {
        List<FinanceGroup> groups = financeGroups();
        String customerKey = "customer-" + legacyId;
        if (groups.stream().anyMatch(group -> group.groupKey.equals(customerKey))) return customerKey;
        String ledgerKey = "ledger-" + legacyId;
        if (groups.stream().anyMatch(group -> group.groupKey.equals(ledgerKey))) return ledgerKey;
        String mappedLedgerGroup = groups.stream()
                .filter(group -> group.accounts.stream().anyMatch(account -> Objects.equals(account.id, legacyId)))
                .map(group -> group.groupKey)
                .findFirst()
                .orElse(null);
        if (mappedLedgerGroup != null) return mappedLedgerGroup;
        String statementKey = "statement-" + legacyId;
        if (groups.stream().anyMatch(group -> group.groupKey.equals(statementKey))) return statementKey;
        throw new BusinessException("客户财务记录不存在");
    }

    private GroupLedgerData groupLedgerData(FinanceGroup group) {
        synchronizeHistoricalLedgerLinks(group);
        List<Long> accountIds = group.accounts.stream().map(account -> account.id).filter(Objects::nonNull).toList();
        List<CustomerLedgerEntry> allEntries = accountIds.isEmpty()
                ? List.of()
                : ledgerEntryRepository.findByAccountIdInAndDeletedFalse(accountIds);
        Set<Long> activeStatementIds = group.statements.stream().map(statement -> statement.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<CustomerLedgerEntry> entries = allEntries.stream()
                .filter(entry -> entry.linkedStatementId == null || !activeStatementIds.contains(entry.linkedStatementId))
                .toList();
        List<PaymentTarget> targets = customerPaymentTargets(entries, group.statements);
        return new GroupLedgerData(entries, targets, group.statements);
    }

    private void synchronizeHistoricalLedgerLinks(FinanceGroup group) {
        if (group.customer == null || group.accounts.isEmpty() || group.statements.isEmpty()) return;
        List<Long> statementIds = group.statements.stream().map(statement -> statement.id)
                .filter(Objects::nonNull).toList();
        Map<String, Set<Long>> statementIdsByInvoiceNo = new LinkedHashMap<>();
        invoiceRepository.findByStatementIdInAndDeletedFalse(statementIds).forEach(invoice ->
                invoiceNumberTokens(invoice.invoiceNo).forEach(token ->
                        statementIdsByInvoiceNo.computeIfAbsent(token, ignored -> new LinkedHashSet<>())
                                .add(invoice.statementId)));

        List<Long> accountIds = group.accounts.stream().map(account -> account.id)
                .filter(Objects::nonNull).toList();
        List<CustomerLedgerEntry> entries = ledgerEntryRepository.findByAccountIdInAndDeletedFalse(accountIds);
        Set<Long> affectedStatementIds = new LinkedHashSet<>();
        for (CustomerLedgerEntry entry : entries) {
            Set<Long> candidates = invoiceNumberTokens(entry.invoiceNo).stream()
                    .map(statementIdsByInvoiceNo::get)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Long linkedStatementId = candidates.size() == 1 ? candidates.iterator().next() : null;
            if (Objects.equals(entry.linkedStatementId, linkedStatementId)) continue;
            if (entry.linkedStatementId != null) affectedStatementIds.add(entry.linkedStatementId);
            entry.linkedStatementId = linkedStatementId;
            ledgerEntryRepository.save(entry);
            if (linkedStatementId != null) affectedStatementIds.add(linkedStatementId);
        }
        affectedStatementIds.stream()
                .map(statementId -> statementRepository.findById(statementId)
                        .filter(statement -> !Boolean.TRUE.equals(statement.deleted)).orElse(null))
                .filter(Objects::nonNull)
                .forEach(statement -> {
                    refreshHistoricalReceivedBaseline(statement);
                    recalcStatementIfChanged(statement);
                });
    }

    private void synchronizeHistoricalLedgerLinks(Long statementId) {
        if (statementId == null) return;
        financeGroups().stream()
                .filter(group -> group.statements.stream()
                        .anyMatch(statement -> Objects.equals(statement.id, statementId)))
                .findFirst()
                .ifPresent(this::synchronizeHistoricalLedgerLinks);
    }

    private void recalcStatementIfChanged(Statement statement) {
        BigDecimal beforeInvoiced = MoneyUtil.money(statement.invoicedAmount);
        BigDecimal beforeSystemAndHistoricalReceived = MoneyUtil.money(statement.receivedAmount);
        BigDecimal beforeHistoricalReceived = MoneyUtil.money(statement.historicalReceivedAmount);
        BigDecimal beforeArrears = MoneyUtil.money(statement.arrearsAmount);
        String beforeMethod = statement.settlementMethod;
        String beforeStatus = statement.settlementStatus;
        String beforeLabel = statement.status;
        LocalDateTime beforePaidAt = statement.paidAt;
        recalcStatement(statement);
        if (beforeInvoiced.compareTo(MoneyUtil.money(statement.invoicedAmount)) != 0
                || beforeSystemAndHistoricalReceived.compareTo(MoneyUtil.money(statement.receivedAmount)) != 0
                || beforeHistoricalReceived.compareTo(MoneyUtil.money(statement.historicalReceivedAmount)) != 0
                || beforeArrears.compareTo(MoneyUtil.money(statement.arrearsAmount)) != 0
                || !Objects.equals(beforeMethod, statement.settlementMethod)
                || !Objects.equals(beforeStatus, statement.settlementStatus)
                || !Objects.equals(beforeLabel, statement.status)
                || !Objects.equals(beforePaidAt, statement.paidAt)) {
            statementRepository.save(statement);
        }
    }

    private void refreshHistoricalReceivedBaseline(Statement statement) {
        if (Boolean.TRUE.equals(statement.receiptRebuilt)) {
            statement.historicalReceivedAmount = BigDecimal.ZERO;
            return;
        }
        BigDecimal historicalPaid = ledgerEntryRepository.findByLinkedStatementIdAndDeletedFalse(statement.id).stream()
                .map(entry -> MoneyUtil.nvl(entry.paidAmount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal systemReceived = receiptRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(receipt -> MoneyUtil.nvl(receipt.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statement.historicalReceivedAmount = MoneyUtil.money(
                MoneyUtil.nvl(statement.receivableAmount).min(historicalPaid)
                        .subtract(systemReceived).max(BigDecimal.ZERO));
    }

    private List<PaymentTarget> customerPaymentTargets(List<CustomerLedgerEntry> ledgerEntries,
                                                       List<Statement> statements) {
        List<PaymentTarget> targets = new ArrayList<>();
        for (CustomerLedgerEntry entry : ledgerEntries) {
            BigDecimal ledgerDebt = MoneyUtil.money(MoneyUtil.nvl(entry.remainingAmount));
            if (ledgerDebt.compareTo(BigDecimal.ZERO) > 0) {
                targets.add(new PaymentTarget(entry, null, targetDate(entry, null), ledgerDebt));
            }
        }
        for (Statement statement : statements) {
            BigDecimal debt = MoneyUtil.money(MoneyUtil.nvl(statement.arrearsAmount));
            if (debt.compareTo(BigDecimal.ZERO) > 0) {
                targets.add(new PaymentTarget(null, statement, targetDate(null, statement), debt));
            }
        }
        return targets.stream().sorted(Comparator
                .comparing((PaymentTarget target) -> target.statement() != null && target.statement().sourceSortDate != null
                        ? target.statement().sourceSortDate : target.date(), Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(target -> target.statement() == null ? null : target.statement().sourceSequence,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(target -> target.ledgerEntry() == null ? Long.MAX_VALUE : target.ledgerEntry().id)
                .thenComparing(target -> target.statement() == null ? Long.MAX_VALUE : target.statement().id))
                .toList();
    }

    private Receipt createCustomerPaymentReceipt(FinanceGroup group, PaymentTarget target, BigDecimal amount,
                                                 LocalDate paymentDate, String batchNo,
                                                 CustomerPaymentRequest request) {
        Receipt receipt = new Receipt();
        paymentRepository.findByGroupKeyAndPaymentNoAndDeletedFalse(group.groupKey, batchNo).ifPresent(payment -> {
            receipt.paymentId = payment.id;
            receipt.receiptNo = "ALLOC-" + UUID.randomUUID().toString();
        });
        receipt.statementId = target.statement() == null ? null : target.statement().id;
        receipt.receiptDate = paymentDate;
        receipt.amount = MoneyUtil.money(amount);
        receipt.method = request.method == null || request.method.isBlank() ? "客户汇款" : request.method.trim();
        receipt.bankSerial = request.bankSerial;
        receipt.remark = request.remark;
        receipt.customerId = group.customer == null ? null : group.customer.id;
        receipt.customerName = group.displayName();
        receipt.paymentBatchNo = batchNo;
        receipt.ledgerAccountId = target.ledgerEntry() == null
                ? group.accounts.stream().map(account -> account.id).findFirst().orElse(null)
                : target.ledgerEntry().accountId;
        receipt.ledgerEntryId = target.ledgerEntry() == null ? null : target.ledgerEntry().id;
        receipt.customerLevelPayment = true;
        if (target.ledgerEntry() != null) {
            CustomerLedgerEntry entry = target.ledgerEntry();
            receipt.ledgerPreviousPaymentDate = entry.lastPaymentDate;
            entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount).add(amount));
            entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.remainingAmount).subtract(amount).max(BigDecimal.ZERO));
            entry.lastPaymentDate = paymentDate;
            ledgerEntryRepository.save(entry);
        }
        if (receipt.statementId != null) return registerReceipt(receipt);
        if (receipt.receiptNo == null) receipt.receiptNo = documentNumberService.nextReceipt(paymentDate, "客户收款单号生成失败，请稍后重试");
        return receiptRepository.save(receipt);
    }

    private List<Map<String, Object>> customerPaymentRows(FinanceGroup group) {
        Set<Long> accountIds = group.accounts.stream().map(account -> account.id).collect(Collectors.toSet());
        Set<Long> statementIds = group.statements.stream().map(statement -> statement.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Statement> statementsById = group.statements.stream()
                .filter(statement -> statement.id != null)
                .collect(Collectors.toMap(statement -> statement.id, statement -> statement));
        Map<Long, Receipt> receiptsById = new LinkedHashMap<>();
        receiptRepository.findByCustomerLevelPaymentTrueAndDeletedFalse().stream()
                .filter(receipt -> receipt.paymentId == null)
                .filter(receipt -> groupContainsReceipt(group, accountIds, statementIds, receipt))
                .forEach(receipt -> receiptsById.put(receipt.id, receipt));
        if (!statementIds.isEmpty()) {
            receiptRepository.findByStatementIdInAndDeletedFalse(new ArrayList<>(statementIds)).stream()
                    .filter(receipt -> receipt.paymentId == null)
                    .filter(receipt -> groupContainsReceipt(group, accountIds, statementIds, receipt))
                    .forEach(receipt -> receiptsById.put(receipt.id, receipt));
        }

        Map<String, List<Receipt>> grouped = receiptsById.values().stream()
                .filter(receipt -> Boolean.TRUE.equals(receipt.customerLevelPayment))
                .filter(receipt -> receipt.paymentBatchNo != null && !receipt.paymentBatchNo.isBlank())
                .collect(Collectors.groupingBy(receipt -> receipt.paymentBatchNo, LinkedHashMap::new, Collectors.toList()));
        List<Map<String, Object>> rows = grouped.entrySet().stream().map(entry -> {
            List<Receipt> receipts = entry.getValue();
            Receipt first = receipts.get(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordType", "COMPANY");
            row.put("statementNo", "定期还款");
            row.put("recordNo", entry.getKey());
            row.put("paymentBatchNo", entry.getKey());
            row.put("paymentDate", first.receiptDate);
            row.put("amount", MoneyUtil.money(receipts.stream().map(item -> MoneyUtil.nvl(item.amount))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            row.put("method", first.method);
            row.put("bankSerial", first.bankSerial);
            row.put("remark", first.remark);
            row.put("allocationCount", receipts.size());
            return row;
        }).collect(Collectors.toCollection(ArrayList::new));

        receiptsById.values().stream()
                .filter(receipt -> !Boolean.TRUE.equals(receipt.customerLevelPayment))
                .forEach(receipt -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Statement statement = receipt.statementId == null ? null : statementsById.get(receipt.statementId);
                    row.put("recordType", "STATEMENT");
                    row.put("recordNo", receipt.receiptNo);
                    row.put("id", receipt.id);
                    row.put("statementId", receipt.statementId);
                    row.put("statementNo", statement == null ? null : statement.statementNo);
                    row.put("paymentDate", receipt.receiptDate);
                    row.put("amount", MoneyUtil.money(receipt.amount));
                    row.put("method", receipt.method);
                    row.put("bankSerial", receipt.bankSerial);
                    row.put("remark", receipt.remark);
                    row.put("allocationCount", 1);
                    rows.add(row);
                });

        for (Statement statement : group.statements) {
            BigDecimal historicalReceived = MoneyUtil.money(statement.historicalReceivedAmount);
            if (historicalReceived.compareTo(BigDecimal.ZERO) <= 0) continue;
            List<CustomerLedgerEntry> linkedEntries = ledgerEntryRepository
                    .findByLinkedStatementIdAndDeletedFalse(statement.id);
            LocalDate paymentDate = linkedEntries.stream().map(entry -> entry.lastPaymentDate)
                    .filter(Objects::nonNull).max(LocalDate::compareTo)
                    .orElseGet(() -> linkedEntries.stream().map(entry -> entry.transactionDate)
                            .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("recordType", "HISTORICAL_BASELINE");
            row.put("recordNo", "历史账页同步");
            row.put("statementId", statement.id);
            row.put("statementNo", statement.statementNo);
            row.put("paymentDate", paymentDate);
            row.put("amount", historicalReceived);
            row.put("method", "历史账页同步");
            row.put("remark", "历史账页已到账金额与系统到账记录的差额");
            row.put("allocationCount", 1);
            row.put("readOnly", true);
            rows.add(row);
        }

        paymentRepository.findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(group.groupKey)
                .forEach(payment -> rows.add(paymentRow(payment)));
        return rows.stream().sorted(Comparator
                .comparing((Map<String, Object> row) -> (LocalDate) row.get("paymentDate"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> String.valueOf(row.get("recordNo")), Comparator.reverseOrder()))
                .toList();
    }

    private boolean groupContainsReceipt(FinanceGroup group, Set<Long> accountIds,
                                         Set<Long> statementIds, Receipt receipt) {
        if (receipt.ledgerAccountId != null && accountIds.contains(receipt.ledgerAccountId)) return true;
        if (receipt.statementId != null && statementIds.contains(receipt.statementId)) return true;
        return group.customer != null && Objects.equals(group.customer.id, receipt.customerId);
    }

    private Map<String, Object> groupSummaryRow(FinanceGroup group, GroupLedgerData data) {
        BigDecimal unallocatedPayments = paymentRepository
                .findByGroupKeyAndDeletedFalseOrderByPaymentDateAscIdAsc(group.groupKey).stream()
                .map(payment -> MoneyUtil.nvl(payment.unallocatedAmount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return groupSummaryRow(group, data, unallocatedPayments);
    }

    private Map<String, Object> groupSummaryRow(FinanceGroup group, GroupLedgerData data,
                                                BigDecimal unallocatedPayments) {
        BigDecimal historicalAmount = data.entries.stream().map(entry -> MoneyUtil.nvl(entry.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal newStatementAmount = data.statements.stream()
                .map(statement -> MoneyUtil.nvl(statement.receivableAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentDebt = data.targets.stream().map(PaymentTarget::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("groupKey", group.groupKey);
        row.put("customerId", group.customer == null ? null : group.customer.id);
        row.put("customerName", group.displayName());
        row.put("contactPerson", group.customer == null ? null : group.customer.contactPerson);
        row.put("phone", group.customer == null ? null : group.customer.phone);
        row.put("ledgerAccountIds", group.accounts.stream().map(account -> account.id).toList());
        row.put("statementAmount", MoneyUtil.money(historicalAmount.add(newStatementAmount)));
        row.put("currentDebt", MoneyUtil.money(currentDebt));
        row.put("statementCount", data.entries.size() + data.statements.size());
        row.put("unpaidStatementCount", data.targets.size());
        row.put("accountStatus", currentDebt.compareTo(BigDecimal.ZERO) > 0 ? "DEBT" : "SETTLED");
        row.put("status", currentDebt.compareTo(BigDecimal.ZERO) > 0 ? "存在欠款" : "已结清");
        row.put("creditBalance", MoneyUtil.money(group.accounts.stream().map(account -> MoneyUtil.nvl(account.creditBalance))
                .reduce(BigDecimal.ZERO, BigDecimal::add).add(MoneyUtil.nvl(unallocatedPayments))));
        row.put("reconciliationNote", group.accounts.stream().map(a -> a.reconciliationNote)
                .filter(Objects::nonNull).filter(s -> !s.isBlank()).distinct().collect(Collectors.joining("；")));
        row.put("currentBalance", MoneyUtil.money(currentDebt.subtract((BigDecimal) row.get("creditBalance"))));
        if (group.accounts.stream().anyMatch(a -> a.sourceNetAmount != null)) {
            BigDecimal sourceNet = group.accounts.stream().map(a -> MoneyUtil.nvl(a.sourceNetAmount))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            row.put("sourceNetAmount", sourceNet);
            row.put("reconciliationDifference", currentDebt.subtract((BigDecimal) row.get("creditBalance")).subtract(sourceNet));
        }
        row.put("latestStatementAt", group.statements.stream().map(statement -> statement.generatedAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        return row;
    }

    private List<Map<String, Object>> legacyCustomerSummaries() {
        if (ledgerAccountRepository.countByDeletedFalse() > 0) {
            Map<String, Customer> customersByName = customerRepository.findAll().stream()
                    .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                    .filter(customer -> customer.name != null && !customer.name.isBlank())
                    .collect(Collectors.toMap(customer -> customer.name.trim(), customer -> customer, (first, ignored) -> first));
            List<CustomerLedgerAccount> accounts = ledgerAccountRepository.findByDeletedFalse();
            List<Long> accountIds = accounts.stream().map(account -> account.id).filter(Objects::nonNull).toList();
            Map<Long, List<CustomerLedgerEntry>> entriesByAccount = accountIds.isEmpty()
                    ? Map.of()
                    : ledgerEntryRepository.findByAccountIdInAndDeletedFalse(accountIds).stream()
                    .collect(Collectors.groupingBy(entry -> entry.accountId));

            List<Long> customerIds = accounts.stream()
                    .map(account -> customersByName.get(account.sheetName))
                    .filter(Objects::nonNull)
                    .map(customer -> customer.id)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<Long, List<Statement>> statementsByCustomer = customerIds.isEmpty()
                    ? Map.of()
                    : statementRepository.findByCustomerIdInAndDeletedFalse(customerIds).stream()
                    .collect(Collectors.groupingBy(statement -> statement.customerId));
            List<Long> statementIds = statementsByCustomer.values().stream()
                    .flatMap(Collection::stream)
                    .map(statement -> statement.id)
                    .filter(Objects::nonNull)
                    .toList();
            Map<Long, List<Invoice>> invoicesByStatement = statementIds.isEmpty()
                    ? Map.of()
                    : invoiceRepository.findByStatementIdInAndDeletedFalse(statementIds).stream()
                    .collect(Collectors.groupingBy(invoice -> invoice.statementId));

            return accounts.stream()
                    .map(account -> {
                        Customer customer = customersByName.get(account.sheetName);
                        List<PaymentTarget> targets = customerPaymentTargets(
                                account,
                                entriesByAccount.getOrDefault(account.id, List.of()),
                                customer == null ? List.of() : statementsByCustomer.getOrDefault(customer.id, List.of()),
                                invoicesByStatement);
                        return ledgerSummaryRow(account, customer, targets);
                    })
                    .sorted(Comparator
                            .comparing((Map<String, Object> row) -> (BigDecimal) row.get("currentDebt"), Comparator.reverseOrder())
                            .thenComparing(row -> String.valueOf(row.get("customerName"))))
                    .toList();
        }
        Map<Long, List<Statement>> statementsByCustomer = activeStatements().stream()
                .filter(statement -> statement.customerId != null)
                .collect(Collectors.groupingBy(statement -> statement.customerId));
        return customerRepository.findAll().stream()
                .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                .map(customer -> customerSummaryRow(customer, statementsByCustomer.getOrDefault(customer.id, List.of())))
                .sorted(Comparator
                        .comparing((Map<String, Object> row) -> (BigDecimal) row.get("currentDebt"), Comparator.reverseOrder())
                        .thenComparing(row -> String.valueOf(row.get("customerName"))))
                .toList();
    }

    private Map<String, Object> legacyCustomerSummaryDetail(Long customerId) {
        if (ledgerAccountRepository.countByDeletedFalse() > 0) {
            CustomerLedgerAccount account = ledgerAccountRepository.findById(customerId)
                    .filter(item -> !Boolean.TRUE.equals(item.deleted))
                    .orElseThrow(() -> new BusinessException("客户账页不存在"));
            Customer customer = customerRepository.findAll().stream()
                    .filter(item -> !Boolean.TRUE.equals(item.deleted))
                    .filter(item -> account.sheetName.equals(item.name))
                    .findFirst()
                    .orElse(null);
            Map<String, Object> customerInfo = new LinkedHashMap<>();
            customerInfo.put("id", account.id);
            customerInfo.put("name", account.sheetName);
            customerInfo.put("contactPerson", customer == null ? null : customer.contactPerson);
            customerInfo.put("phone", customer == null ? null : customer.phone);

            List<CustomerLedgerEntry> entries = ledgerEntryRepository.findByAccountIdAndDeletedFalse(account.id).stream()
                    .sorted(Comparator
                            .comparing((CustomerLedgerEntry entry) -> MoneyUtil.nvl(entry.remainingAmount).compareTo(BigDecimal.ZERO) > 0 ? 0 : 1)
                            .thenComparing(entry -> entry.transactionDate, Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(entry -> entry.sourceRow, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
            List<Statement> statements = customer == null
                    ? List.of()
                    : sortedStatements(statementRepository.findByCustomerIdAndDeletedFalse(customer.id));
            List<Long> statementIds = statements.stream().map(statement -> statement.id).filter(Objects::nonNull).toList();
            Map<Long, List<Invoice>> invoicesByStatement = statementIds.isEmpty()
                    ? Map.of()
                    : invoiceRepository.findByStatementIdInAndDeletedFalse(statementIds).stream()
                    .collect(Collectors.groupingBy(invoice -> invoice.statementId));
            List<PaymentTarget> targets = customerPaymentTargets(account, entries, statements, invoicesByStatement);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("customer", customerInfo);
            result.put("summary", ledgerSummaryRow(account, customer, targets));
            result.put("statements", collectionRows(statements));
            result.put("ledgerEntries", entries.stream().map(entry -> ledgerEntryRow(account, entry)).toList());
            result.put("payments", customerPaymentRows(account.id));
            return result;
        }
        Customer customer = customerRepository.findById(customerId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("客户不存在"));
        List<Statement> statements = sortedStatements(statementRepository.findByCustomerIdAndDeletedFalse(customerId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("customer", customer);
        result.put("summary", customerSummaryRow(customer, statements));
        result.put("statements", collectionRows(statements));
        result.put("ledgerEntries", List.of());
        result.put("payments", List.of());
        return result;
    }

    @Transactional
    private Map<String, Object> legacyRegisterCustomerPayment(Long ledgerAccountId, CustomerPaymentRequest request) {
        CustomerLedgerAccount account = ledgerAccountRepository.findById(ledgerAccountId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("客户账页不存在"));
        BigDecimal amount = MoneyUtil.money(MoneyUtil.nvl(request.amount));
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("收款金额必须大于0");
        LocalDate paymentDate = request.paymentDate == null ? LocalDate.now() : request.paymentDate;
        Customer customer = findCustomerByLedgerAccount(account);
        List<PaymentTarget> targets = customerPaymentTargets(account, customer);
        BigDecimal totalOutstanding = targets.stream().map(PaymentTarget::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(totalOutstanding) > 0) {
            throw new BusinessException("收款金额不能超过当前可核销欠款 " + MoneyUtil.money(totalOutstanding));
        }

        String batchNo = "PAY-" + paymentDate.toString().replace("-", "") + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        BigDecimal remaining = amount;
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (PaymentTarget target : targets) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal allocated = remaining.min(target.outstanding());
            Receipt receipt = createCustomerPaymentReceipt(account, customer, target, allocated, paymentDate, batchNo, request);
            allocations.add(paymentAllocationRow(receipt, target, allocated));
            remaining = remaining.subtract(allocated);
        }
        if (remaining.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("客户收款自动核销未完整分配");
        }
        recalcLedgerAccount(account);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("paymentBatchNo", batchNo);
        result.put("amount", amount);
        result.put("paymentDate", paymentDate);
        result.put("allocations", allocations);
        result.put("detail", customerSummaryDetail(account.id));
        return result;
    }

    @Transactional
    private void legacyDeleteCustomerPayment(Long ledgerAccountId, String batchNo) {
        CustomerLedgerAccount account = ledgerAccountRepository.findById(ledgerAccountId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("客户账页不存在"));
        List<Receipt> receipts = receiptRepository
                .findByLedgerAccountIdAndPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(ledgerAccountId, batchNo)
                .stream().sorted(Comparator.comparing((Receipt receipt) -> receipt.id).reversed()).toList();
        if (receipts.isEmpty()) throw new BusinessException("客户收款记录不存在");
        Set<Long> statementIds = new LinkedHashSet<>();
        for (Receipt receipt : receipts) {
            if (receipt.ledgerEntryId != null) {
                CustomerLedgerEntry entry = ledgerEntryRepository.findById(receipt.ledgerEntryId).orElseThrow();
                entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount)
                        .subtract(MoneyUtil.nvl(receipt.amount)).max(BigDecimal.ZERO));
                entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.remainingAmount).add(MoneyUtil.nvl(receipt.amount)));
                entry.lastPaymentDate = receipt.ledgerPreviousPaymentDate;
                ledgerEntryRepository.save(entry);
            }
            if (receipt.statementId != null) statementIds.add(receipt.statementId);
            receiptRepository.delete(receipt);
        }
        statementIds.forEach(this::recalcStatement);
        recalcLedgerAccount(account);
    }

    private List<PaymentTarget> customerPaymentTargets(CustomerLedgerAccount account, Customer customer) {
        List<Statement> statements = customer == null
                ? List.of()
                : statementRepository.findByCustomerIdAndDeletedFalse(customer.id);
        Set<Long> activeStatementIds = statements.stream().map(statement -> statement.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        List<CustomerLedgerEntry> entries = ledgerEntryRepository.findByAccountIdAndDeletedFalse(account.id).stream()
                .filter(entry -> entry.linkedStatementId == null || !activeStatementIds.contains(entry.linkedStatementId))
                .toList();
        return customerPaymentTargets(entries, statements);
    }

    private List<PaymentTarget> customerPaymentTargets(CustomerLedgerAccount account,
                                                       List<CustomerLedgerEntry> ledgerEntries,
                                                       List<Statement> statements,
                                                       Map<Long, List<Invoice>> invoicesByStatement) {
        Set<Long> activeStatementIds = statements.stream().map(statement -> statement.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return customerPaymentTargets(ledgerEntries.stream()
                .filter(entry -> entry.linkedStatementId == null || !activeStatementIds.contains(entry.linkedStatementId))
                .toList(), statements);
    }

    private Receipt createCustomerPaymentReceipt(CustomerLedgerAccount account, Customer customer,
                                                 PaymentTarget target, BigDecimal amount, LocalDate paymentDate,
                                                 String batchNo, CustomerPaymentRequest request) {
        Receipt receipt = new Receipt();
        receipt.statementId = target.statement() == null ? null : target.statement().id;
        receipt.receiptDate = paymentDate;
        receipt.amount = MoneyUtil.money(amount);
        receipt.method = request.method == null || request.method.isBlank() ? "客户汇款" : request.method.trim();
        receipt.bankSerial = request.bankSerial;
        receipt.remark = request.remark;
        receipt.customerId = customer == null ? null : customer.id;
        receipt.customerName = account.sheetName;
        receipt.paymentBatchNo = batchNo;
        receipt.ledgerAccountId = account.id;
        receipt.ledgerEntryId = target.ledgerEntry() == null ? null : target.ledgerEntry().id;
        receipt.customerLevelPayment = true;
        if (target.ledgerEntry() != null) {
            CustomerLedgerEntry entry = target.ledgerEntry();
            receipt.ledgerPreviousPaymentDate = entry.lastPaymentDate;
            entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount).add(amount));
            entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.remainingAmount).subtract(amount).max(BigDecimal.ZERO));
            entry.lastPaymentDate = paymentDate;
            ledgerEntryRepository.save(entry);
        }
        if (receipt.statementId != null) return registerReceipt(receipt);
        receipt.receiptNo = documentNumberService.nextReceipt(paymentDate, "客户收款单号生成失败，请稍后重试");
        return receiptRepository.save(receipt);
    }

    private Map<String, Object> paymentAllocationRow(Receipt receipt, PaymentTarget target, BigDecimal amount) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("receiptNo", receipt.receiptNo);
        row.put("amount", MoneyUtil.money(amount));
        row.put("statementNo", target.statement() == null ? null : target.statement().statementNo);
        row.put("ledgerRecordNo", target.ledgerEntry() == null ? null : target.ledgerEntry().invoiceNo);
        return row;
    }

    private List<Map<String, Object>> customerPaymentRows(Long ledgerAccountId) {
        Map<String, List<Receipt>> grouped = receiptRepository
                .findByLedgerAccountIdAndCustomerLevelPaymentTrueAndDeletedFalse(ledgerAccountId).stream()
                .filter(receipt -> receipt.paymentBatchNo != null && !receipt.paymentBatchNo.isBlank())
                .collect(Collectors.groupingBy(receipt -> receipt.paymentBatchNo, LinkedHashMap::new, Collectors.toList()));
        return grouped.entrySet().stream().map(entry -> {
            List<Receipt> receipts = entry.getValue();
            Receipt first = receipts.get(0);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paymentBatchNo", entry.getKey());
            row.put("paymentDate", first.receiptDate);
            row.put("amount", MoneyUtil.money(receipts.stream().map(item -> MoneyUtil.nvl(item.amount))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            row.put("method", first.method);
            row.put("bankSerial", first.bankSerial);
            row.put("remark", first.remark);
            row.put("allocationCount", receipts.size());
            return row;
        }).sorted(Comparator
                .comparing((Map<String, Object> row) -> (LocalDate) row.get("paymentDate"), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> String.valueOf(row.get("paymentBatchNo")), Comparator.reverseOrder()))
                .toList();
    }

    private void recalcLedgerAccount(CustomerLedgerAccount account) {
        List<CustomerLedgerEntry> entries = ledgerEntryRepository.findByAccountIdAndDeletedFalse(account.id);
        account.statementAmount = MoneyUtil.money(entries.stream().map(entry -> MoneyUtil.nvl(entry.amount))
                .filter(amount -> amount.compareTo(BigDecimal.ZERO) > 0).reduce(BigDecimal.ZERO, BigDecimal::add));
        account.currentDebt = MoneyUtil.money(entries.stream().map(entry -> MoneyUtil.nvl(entry.remainingAmount))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        account.entryCount = entries.size();
        account.openEntryCount = (int) entries.stream()
                .filter(entry -> MoneyUtil.nvl(entry.remainingAmount).compareTo(BigDecimal.ZERO) > 0).count();
        ledgerAccountRepository.save(account);
    }

    private Customer findCustomerByLedgerAccount(CustomerLedgerAccount account) {
        return customerRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> account.sheetName.equals(item.name))
                .findFirst().orElse(null);
    }

    private Set<String> invoiceNumberTokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = INVOICE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) tokens.add(matcher.group());
        return tokens;
    }

    private LocalDate targetDate(CustomerLedgerEntry entry, Statement statement) {
        if (entry != null && entry.transactionDate != null) return entry.transactionDate;
        return statement == null || statement.generatedAt == null ? null : statement.generatedAt.toLocalDate();
    }

    private BigDecimal sumStatements(List<Statement> statements, Function<Statement, BigDecimal> value) {
        return MoneyUtil.money(statements.stream().map(value).map(MoneyUtil::nvl)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static final class FinanceGroup {
        private final String groupKey;
        private final Customer customer;
        private final String fallbackName;
        private final List<CustomerLedgerAccount> accounts = new ArrayList<>();
        private final List<Statement> statements = new ArrayList<>();

        private FinanceGroup(String groupKey, Customer customer, String fallbackName) {
            this.groupKey = groupKey;
            this.customer = customer;
            this.fallbackName = fallbackName;
        }

        private String displayName() {
            if (customer != null && customer.name != null && !customer.name.isBlank()) return customer.name;
            return fallbackName == null || fallbackName.isBlank() ? "未命名客户" : fallbackName;
        }
    }

    private static final class CollectionBoardGroup {
        private final String groupKey;
        private final Customer customer;
        private final String fallbackName;
        private final List<CustomerLedgerAccount> accounts = new ArrayList<>();
        private final List<CustomerLedgerEntry> ledgerEntries = new ArrayList<>();
        private int statementCount;
        private int unpaidStatementCount;
        private BigDecimal statementReceivable = BigDecimal.ZERO;
        private BigDecimal statementInvoiced = BigDecimal.ZERO;
        private BigDecimal statementInvoicedUnpaid = BigDecimal.ZERO;
        private BigDecimal statementReceived = BigDecimal.ZERO;
        private BigDecimal statementDebt = BigDecimal.ZERO;
        private LocalDateTime latestStatementAt;

        private CollectionBoardGroup(String groupKey, Customer customer, String fallbackName) {
            this.groupKey = groupKey;
            this.customer = customer;
            this.fallbackName = fallbackName;
        }

        private void add(StatementRepository.CustomerCollectionTotal total) {
            statementCount += Math.toIntExact(total.getStatementCount());
            unpaidStatementCount += Math.toIntExact(total.getUnpaidStatementCount());
            statementReceivable = statementReceivable.add(MoneyUtil.nvl(total.getStatementAmount()));
            statementInvoiced = statementInvoiced.add(MoneyUtil.nvl(total.getInvoicedAmount()));
            statementInvoicedUnpaid = statementInvoicedUnpaid.add(MoneyUtil.nvl(total.getInvoicedUnpaidAmount()));
            statementReceived = statementReceived.add(MoneyUtil.nvl(total.getReceivedAmount()));
            statementDebt = statementDebt.add(MoneyUtil.nvl(total.getCurrentDebt()));
            updateLatestStatementAt(total.getLatestStatementAt());
        }

        private void add(Statement statement) {
            statementCount++;
            statementReceivable = statementReceivable.add(MoneyUtil.nvl(statement.receivableAmount));
            statementInvoiced = statementInvoiced.add(MoneyUtil.nvl(statement.invoicedAmount));
            statementInvoicedUnpaid = statementInvoicedUnpaid.add(MoneyUtil.nvl(statement.invoicedAmount)
                    .subtract(MoneyUtil.nvl(statement.receivedAmount)).max(BigDecimal.ZERO));
            statementReceived = statementReceived.add(MoneyUtil.nvl(statement.receivedAmount));
            BigDecimal debt = MoneyUtil.nvl(statement.arrearsAmount);
            if (debt.compareTo(BigDecimal.ZERO) > 0) {
                statementDebt = statementDebt.add(debt);
                unpaidStatementCount++;
            }
            updateLatestStatementAt(statement.generatedAt);
        }

        private void updateLatestStatementAt(LocalDateTime candidate) {
            if (candidate != null && (latestStatementAt == null || candidate.isAfter(latestStatementAt))) {
                latestStatementAt = candidate;
            }
        }

        private String displayName() {
            if (customer != null && customer.name != null && !customer.name.isBlank()) return customer.name;
            return fallbackName == null || fallbackName.isBlank() ? "未命名客户" : fallbackName;
        }
    }

    private static final class CollectionBoardJdbcGroup {
        private String groupKey;
        private Long customerId;
        private String customerName;
        private String contactPerson;
        private String phone;
        private int statementCount;
        private BigDecimal totalSalesAmount = BigDecimal.ZERO;
        private int unpaidStatementCount;
        private BigDecimal receivableAmount = BigDecimal.ZERO;
        private BigDecimal invoicedAmount = BigDecimal.ZERO;
        private BigDecimal invoicedUnpaidAmount = BigDecimal.ZERO;
        private BigDecimal receivedAmount = BigDecimal.ZERO;
        private BigDecimal currentDebt = BigDecimal.ZERO;
        private BigDecimal creditBalance = BigDecimal.ZERO;
        private BigDecimal sourceNetAmount;
        private LocalDateTime latestStatementAt;

        private void addLedgerEntry(BigDecimal amount, String invoiceNo,
                                    BigDecimal paidAmount, BigDecimal remainingAmount) {
            statementCount++;
            receivableAmount = receivableAmount.add(MoneyUtil.nvl(amount));
            if (!invoiceNumberTokensStatic(invoiceNo).isEmpty()) {
                invoicedAmount = invoicedAmount.add(MoneyUtil.nvl(amount));
                BigDecimal remaining = MoneyUtil.nvl(remainingAmount);
                if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                    invoicedUnpaidAmount = invoicedUnpaidAmount.add(remaining);
                }
            }
            receivedAmount = receivedAmount.add(MoneyUtil.nvl(paidAmount));
            BigDecimal remaining = MoneyUtil.nvl(remainingAmount);
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                currentDebt = currentDebt.add(remaining);
                unpaidStatementCount++;
            }
        }

        private Map<String, Object> toRow() {
            BigDecimal arrearsAmount = currentDebt.subtract(creditBalance);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("groupKey", groupKey);
            row.put("customerId", customerId);
            row.put("customerName", customerName);
            row.put("contactPerson", contactPerson);
            row.put("phone", phone);
            row.put("statementCount", statementCount);
            row.put("totalSalesAmount", MoneyUtil.money(totalSalesAmount));
            row.put("unpaidStatementCount", unpaidStatementCount);
            row.put("receivableAmount", MoneyUtil.money(receivableAmount));
            row.put("invoicedAmount", MoneyUtil.money(invoicedAmount));
            row.put("invoicedUnpaidAmount", MoneyUtil.money(invoicedUnpaidAmount));
            row.put("receivedAmount", MoneyUtil.money(receivedAmount.add(creditBalance)));
            row.put("arrearsAmount", MoneyUtil.money(arrearsAmount));
            row.put("creditBalance", MoneyUtil.money(creditBalance));
            row.put("sourceNetAmount", sourceNetAmount);
            row.put("reconciliationDifference", sourceNetAmount == null
                    ? null : MoneyUtil.money(arrearsAmount.subtract(sourceNetAmount)));
            row.put("latestStatementAt", latestStatementAt);
            return row;
        }

        private static Set<String> invoiceNumberTokensStatic(String value) {
            if (value == null || value.isBlank()) return Set.of();
            Set<String> tokens = new LinkedHashSet<>();
            Matcher matcher = INVOICE_NUMBER_PATTERN.matcher(value);
            while (matcher.find()) tokens.add(matcher.group());
            return tokens;
        }
    }

    private record GroupLedgerData(List<CustomerLedgerEntry> entries,
                                   List<PaymentTarget> targets,
                                   List<Statement> statements) {
    }

    private record PaymentTarget(CustomerLedgerEntry ledgerEntry, Statement statement, LocalDate date, BigDecimal outstanding) {
    }

    public boolean isDeliveryStatemented(Long deliveryId) {
        return deliveryId != null && (!statementItemRepository.findByDeliveryIdAndDeletedFalse(deliveryId).isEmpty()
                || deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(deliveryId).stream()
                .anyMatch(item -> MoneyUtil.nvl(item.historicalReconciledQty).signum() > 0));
    }

    public void syncDeliveryDate(Long deliveryId, LocalDate deliveryDate) {
        if (deliveryId == null) return;
        List<StatementItem> items = statementItemRepository.findByDeliveryIdAndDeletedFalse(deliveryId);
        items.forEach(item -> item.deliveryDate = deliveryDate);
        if (!items.isEmpty()) statementItemRepository.saveAll(items);
    }

    public boolean hasPendingReconciliation(DeliveryNote delivery) {
        return delivery != null
                && !Boolean.TRUE.equals(delivery.reconciliationExcluded)
                && reconciliationBalance(delivery).remainingQty().compareTo(BigDecimal.ZERO) > 0;
    }

    public List<Map<String, Object>> collectionRowsForDeliveries(Collection<Long> deliveryIds) {
        if (deliveryIds == null || deliveryIds.isEmpty()) return List.of();
        List<Long> distinctDeliveryIds = deliveryIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctDeliveryIds.isEmpty()) return List.of();
        Set<Long> statementIds = statementItemRepository.findByDeliveryIdInAndDeletedFalse(distinctDeliveryIds).stream()
                .map(item -> item.statementId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (statementIds.isEmpty()) return List.of();
        List<Statement> statements = statementRepository.findAllById(statementIds).stream()
                .filter(statement -> !Boolean.TRUE.equals(statement.deleted))
                .sorted(statementComparator())
                .toList();
        return collectionRows(statements);
    }

    @Transactional
    public StatementItem createStatementAdjustment(Long statementId, StatementAdjustmentRequest request) {
        Statement statement = activeStatement(statementId);
        StatementItem item = new StatementItem();
        applyReturnAdjustment(item, statement, request);
        validateAdjustedStatementTotal(statement, null, item.amount);
        StatementItem saved = statementItemRepository.save(item);
        recalcStatementAmount(statement);
        return saved;
    }

    @Transactional
    public StatementItem updateStatementAdjustment(Long statementId, Long itemId,
                                                   StatementAdjustmentRequest request) {
        Statement statement = activeStatement(statementId);
        StatementItem item = statementItemRepository.findById(itemId)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .filter(existing -> Objects.equals(existing.statementId, statement.id))
                .filter(existing -> ADJUSTMENT_RETURN.equals(existing.adjustmentType))
                .orElseThrow(() -> new BusinessException("退货调整记录不存在"));
        applyReturnAdjustment(item, statement, request);
        validateAdjustedStatementTotal(statement, item.id, item.amount);
        StatementItem saved = statementItemRepository.save(item);
        recalcStatementAmount(statement);
        return saved;
    }

    @Transactional
    public void deleteStatementAdjustment(Long statementId, Long itemId) {
        Statement statement = activeStatement(statementId);
        StatementItem item = statementItemRepository.findById(itemId)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .filter(existing -> Objects.equals(existing.statementId, statement.id))
                .filter(existing -> ADJUSTMENT_RETURN.equals(existing.adjustmentType))
                .orElseThrow(() -> new BusinessException("退货调整记录不存在"));
        validateAdjustedStatementTotal(statement, item.id, null);
        statementItemRepository.delete(item);
        statementItemRepository.flush();
        recalcStatementAmount(statement);
    }

    private void applyReturnAdjustment(StatementItem item, Statement statement,
                                       StatementAdjustmentRequest request) {
        if (item.returnId != null) throw new BusinessException("发货管理退货请先移除此对账行，再重新选择数量对账");
        if (request == null) throw new BusinessException("请填写退货调整信息");
        String productName = request.productName == null ? "" : request.productName.trim();
        BigDecimal qty = MoneyUtil.nvl(request.qty);
        BigDecimal unitPrice = MoneyUtil.nvl(request.unitPrice);
        if (productName.isBlank()) throw new BusinessException("请填写退货产品名称");
        if (qty.compareTo(BigDecimal.ZERO) >= 0) throw new BusinessException("退货数量必须为负数");
        if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("退货单价必须大于0");
        item.statementId = statement.id;
        item.deliveryId = null;
        item.deliveryNo = "退货记录";
        item.orderNo = trimToNull(request.documentNo);
        item.productName = productName;
        item.spec = trimToNull(request.spec);
        item.unit = trimToNull(request.unit);
        item.qty = qty;
        item.unitPrice = unitPrice;
        item.amount = MoneyUtil.money(qty.multiply(unitPrice));
        item.deliveryDate = request.adjustmentDate == null ? LocalDate.now() : request.adjustmentDate;
        item.adjustmentType = ADJUSTMENT_RETURN;
    }

    private void validateAdjustedStatementTotal(Statement statement, Long replacedItemId,
                                                BigDecimal replacementAmount) {
        BigDecimal total = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .filter(item -> replacedItemId == null || !Objects.equals(item.id, replacedItemId))
                .map(item -> MoneyUtil.nvl(item.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (replacementAmount != null) total = total.add(replacementAmount);
        total = MoneyUtil.money(total);
        recalcStatement(statement);
        if (total.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("退货调整后账单总额不能小于0");
        }
        BigDecimal financialFloor = MoneyUtil.money(MoneyUtil.nvl(statement.invoicedAmount)
                .max(MoneyUtil.nvl(statement.receivedAmount)));
        if (total.compareTo(financialFloor) < 0) {
            throw new BusinessException("退货调整后账单总额不能低于已开票或已到账金额 " + financialFloor);
        }
    }

    private void recalcStatementAmount(Statement statement) {
        if (Boolean.TRUE.equals(statement.sourceAmountOnly)) {
            throw new BusinessException("该历史账单只有原表汇总金额，不能通过商品明细重算");
        }
        statement.receivableAmount = MoneyUtil.money(statementItemRepository
                .findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(item -> MoneyUtil.nvl(item.amount)).reduce(BigDecimal.ZERO, BigDecimal::add));
        recalcStatement(statement);
        statementRepository.save(statement);
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    @Transactional
    public void deleteStatement(Long statementId) {
        Statement statement = statementRepository.findById(statementId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("对账单不存在"));
        FinanceGroup group = financeGroupForStatementDeletion(statement);
        lockPaymentGroup(group);

        List<StatementItem> statementItems = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id);
        List<Long> deliveryIds = statementItems.stream()
                .map(item -> item.deliveryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Invoice> invoices = invoiceRepository.findByStatementIdAndDeletedFalse(statement.id);
        List<Receipt> receipts = receiptRepository.findByStatementIdAndDeletedFalse(statement.id);
        preserveLegacyReceiptPayments(group, receipts);
        List<CustomerLedgerEntry> linkedEntries = ledgerEntryRepository
                .findByLinkedStatementIdAndDeletedFalse(statement.id);
        List<Long> invoiceIds = invoices.stream()
                .map(invoice -> invoice.id)
                .filter(Objects::nonNull)
                .toList();
        if (!invoiceIds.isEmpty()) {
            attachmentRepository.deleteAll(attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse("INVOICE_ATTACHMENT", invoiceIds));
        }
        invoiceRepository.deleteAll(invoices);
        linkedEntries.forEach(entry -> entry.linkedStatementId = null);
        ledgerEntryRepository.saveAll(linkedEntries);
        statementItemRepository.deleteAll(statementItems);
        statementRepository.delete(statement);
        entityManager.flush();
        group.statements.removeIf(item -> Objects.equals(item.id, statement.id));
        clearStatementInvoiceInfo(deliveryIds, statement.id);
        replayPayments(group);
        clearDashboardCache();
    }

    private FinanceGroup financeGroupForStatementDeletion(Statement statement) {
        List<FinanceGroup> groups = financeGroups();
        FinanceGroup group = groups.stream()
                .filter(candidate -> candidate.statements.stream()
                        .anyMatch(item -> Objects.equals(item.id, statement.id)))
                .findFirst().orElse(null);
        if (group == null && statement.customerId != null) {
            group = groups.stream()
                    .filter(candidate -> candidate.customer != null
                            && Objects.equals(candidate.customer.id, statement.customerId))
                    .findFirst().orElse(null);
        }
        if (group == null && statement.ledgerAccountId != null) {
            group = groups.stream()
                    .filter(candidate -> candidate.accounts.stream()
                            .anyMatch(account -> Objects.equals(account.id, statement.ledgerAccountId)))
                    .findFirst().orElse(null);
        }
        if (group == null) {
            Customer customer = statement.customerId == null ? null : customerRepository.findById(statement.customerId)
                    .filter(item -> !Boolean.TRUE.equals(item.deleted)).orElse(null);
            String groupKey = customer == null ? "statement-delete-" + statement.id : "customer-" + customer.id;
            group = new FinanceGroup(groupKey, customer, statement.customerName);
            if (statement.ledgerAccountId != null) {
                ledgerAccountRepository.findById(statement.ledgerAccountId)
                        .filter(account -> !Boolean.TRUE.equals(account.deleted))
                        .ifPresent(group.accounts::add);
            }
        }
        if (group.statements.stream().noneMatch(item -> Objects.equals(item.id, statement.id))) {
            group.statements.add(statement);
        }
        return group;
    }

    private void preserveLegacyReceiptPayments(FinanceGroup group, List<Receipt> statementReceipts) {
        Set<Long> processedReceiptIds = new HashSet<>();
        Set<Long> accountIds = group.accounts.stream().map(account -> account.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> statementIds = group.statements.stream().map(item -> item.id)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        for (Receipt receipt : statementReceipts) {
            if (receipt.paymentId != null || !processedReceiptIds.add(receipt.id)) continue;
            List<Receipt> allocations;
            if (Boolean.TRUE.equals(receipt.customerLevelPayment)
                    && receipt.paymentBatchNo != null && !receipt.paymentBatchNo.isBlank()) {
                allocations = receiptRepository
                        .findByPaymentBatchNoAndCustomerLevelPaymentTrueAndDeletedFalse(receipt.paymentBatchNo).stream()
                        .filter(item -> groupContainsReceipt(group, accountIds, statementIds, item))
                        .toList();
            } else {
                allocations = List.of(receipt);
            }
            allocations.stream().map(item -> item.id).filter(Objects::nonNull).forEach(processedReceiptIds::add);
            preserveLegacyPaymentHeader(group, allocations);
            for (Receipt allocation : allocations) {
                if (allocation.ledgerEntryId != null) {
                    ledgerEntryRepository.findById(allocation.ledgerEntryId).ifPresent(entry -> {
                        entry.paidAmount = MoneyUtil.money(MoneyUtil.nvl(entry.paidAmount)
                                .subtract(MoneyUtil.nvl(allocation.amount)).max(BigDecimal.ZERO));
                        entry.remainingAmount = MoneyUtil.money(MoneyUtil.nvl(entry.amount)
                                .subtract(entry.paidAmount).max(BigDecimal.ZERO));
                        entry.lastPaymentDate = allocation.ledgerPreviousPaymentDate;
                        ledgerEntryRepository.save(entry);
                    });
                }
                allocation.deleted = true;
            }
            receiptRepository.saveAll(allocations);
        }
    }

    private void preserveLegacyPaymentHeader(FinanceGroup group, List<Receipt> allocations) {
        if (allocations.isEmpty()) return;
        Receipt first = allocations.get(0);
        ReceiptPayment payment = new ReceiptPayment();
        payment.groupKey = group.groupKey;
        payment.customerId = group.customer == null ? first.customerId : group.customer.id;
        payment.paymentDate = first.receiptDate;
        payment.sourceSortDate = first.receiptDate;
        payment.amount = MoneyUtil.money(allocations.stream()
                .map(item -> MoneyUtil.nvl(item.amount)).reduce(BigDecimal.ZERO, BigDecimal::add));
        payment.unallocatedAmount = payment.amount;
        payment.method = first.method;
        payment.remark = first.remark;
        String originalNo = first.paymentBatchNo == null || first.paymentBatchNo.isBlank()
                ? first.receiptNo : first.paymentBatchNo;
        payment.paymentNo = originalNo == null || originalNo.isBlank() || paymentRepository.existsByPaymentNo(originalNo)
                ? "PAY-ROLLBACK-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT)
                : originalNo;
        paymentRepository.save(payment);
    }

    @Transactional
    public int repairSettlementLabelsImportedAsInvoices() {
        List<Invoice> invalid = invoiceRepository.findAll().stream()
                .filter(i -> !Boolean.TRUE.equals(i.deleted) && i.invoiceNo != null
                        && NON_INVOICE_LABELS.contains(i.invoiceNo.trim()))
                .toList();
        Set<Long> statementIds = new LinkedHashSet<>();
        for (Invoice invoice : invalid) {
            invoice.deleted = true;
            invoice.status = "结算备注误导入，已修正为非发票";
            invoiceRepository.save(invoice);
            if (invoice.statementId != null) statementIds.add(invoice.statementId);
        }
        invoiceRepository.flush();
        for (Long id : statementIds) {
            Statement statement = statementRepository.findById(id).orElse(null);
            if (statement == null || Boolean.TRUE.equals(statement.deleted)) continue;
            BigDecimal receivable = MoneyUtil.money(statement.receivableAmount);
            BigDecimal received = MoneyUtil.money(statement.receivedAmount);
            BigDecimal arrears = MoneyUtil.money(statement.arrearsAmount);
            BigDecimal invalidAmount = invalid.stream().filter(i -> Objects.equals(i.statementId, id))
                    .map(i -> MoneyUtil.nvl(i.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
            statement.sourceInvoicedAmount = MoneyUtil.money(MoneyUtil.nvl(statement.sourceInvoicedAmount)
                    .subtract(invalidAmount).max(BigDecimal.ZERO));
            List<Invoice> realInvoices = invoiceRepository.findByStatementIdAndDeletedFalse(id);
            if (realInvoices.isEmpty() && MoneyUtil.nvl(statement.sourceInvoicedAmount).signum() == 0) {
                statement.settlementMethod = METHOD_CASH;
            }
            recalcStatement(statement);
            if (receivable.compareTo(MoneyUtil.money(statement.receivableAmount)) != 0
                    || received.compareTo(MoneyUtil.money(statement.receivedAmount)) != 0
                    || arrears.compareTo(MoneyUtil.money(statement.arrearsAmount)) != 0) {
                throw new BusinessException("结算备注修正不能改变原应收或回款：" + statement.statementNo);
            }
            statement.sourceRemark = (statement.sourceRemark == null ? "" : statement.sourceRemark)
                    + "；2026-10-08修正：结算备注不是发票号，应收及回款不变";
            statementRepository.save(statement);
            for (CustomerLedgerEntry entry : ledgerEntryRepository.findByLinkedStatementIdAndDeletedFalse(id)) {
                if (entry.invoiceNo != null && NON_INVOICE_LABELS.contains(entry.invoiceNo.trim())) {
                    entry.invoiceNo = null;
                    ledgerEntryRepository.save(entry);
                }
            }
            if (!realInvoices.isEmpty()) {
                syncStatementInvoicesToDeliveries(id);
            } else {
                for (StatementItem item : statementItemRepository.findByStatementIdAndDeletedFalse(id)) {
                    if (item.deliveryId == null) continue;
                    deliveryNoteRepository.findById(item.deliveryId).ifPresent(delivery -> {
                        if (delivery.statementId != null && !Objects.equals(delivery.statementId, id)) return;
                        delivery.invoiced = false;
                        delivery.invoiceNo = null;
                        delivery.invoiceDate = null;
                        delivery.invoiceAmount = null;
                        // Preserve reconciliation links; removing a false invoice is not undoing reconciliation.
                        deliveryNoteRepository.save(delivery);
                    });
                }
            }
        }
        if (!invalid.isEmpty()) {
            clearDashboardCache();
            clearDeliveryBoardCache();
        }
        return invalid.size();
    }

    public void recalcStatement(Long statementId) {
        Statement statement = statementRepository.findById(statementId).orElseThrow(() -> new BusinessException("账单不存在"));
        recalcStatement(statement);
        statementRepository.save(statement);
    }

    @Transactional
    public void backfillSettlementState() {
        financeGroups().forEach(this::synchronizeHistoricalLedgerLinks);
        for (Statement statement : statementRepository.findAll()) {
            if (Boolean.TRUE.equals(statement.deleted)) continue;
            List<Invoice> invoices = invoiceRepository.findByStatementIdAndDeletedFalse(statement.id);
            List<Receipt> receipts = receiptRepository.findByStatementIdAndDeletedFalse(statement.id);
            if ((statement.settlementMethod == null || statement.settlementMethod.isBlank()) && !invoices.isEmpty()) {
                statement.settlementMethod = METHOD_INVOICE;
            } else if ((statement.settlementMethod == null || statement.settlementMethod.isBlank()) && !receipts.isEmpty()) {
                statement.settlementMethod = METHOD_CASH;
            }
            if (statement.registeredAt == null && (!invoices.isEmpty() || !receipts.isEmpty())) {
                statement.registeredAt = statement.createdAt == null ? LocalDateTime.now() : statement.createdAt;
            }
            recalcStatement(statement);
            statementRepository.save(statement);
        }
    }

    private void recalcStatement(Statement statement) {
        BigDecimal invoiced = invoiceRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(i -> MoneyUtil.nvl(i.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal systemReceived = receiptRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(r -> MoneyUtil.nvl(r.amount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Boolean.TRUE.equals(statement.sourceAmountOnly)) invoiced = invoiced.max(MoneyUtil.nvl(statement.sourceInvoicedAmount));
        BigDecimal historicalBaseline = Boolean.TRUE.equals(statement.receiptRebuilt) ? BigDecimal.ZERO
                : MoneyUtil.money(statement.historicalReceivedAmount);
        BigDecimal received = MoneyUtil.money(systemReceived.add(historicalBaseline));
        statement.invoicedAmount = MoneyUtil.money(invoiced);
        statement.historicalReceivedAmount = historicalBaseline;
        statement.receivedAmount = received;
        statement.arrearsAmount = MoneyUtil.money(MoneyUtil.nvl(statement.receivableAmount).subtract(statement.receivedAmount).max(BigDecimal.ZERO));
        if (MoneyUtil.nvl(statement.receivableAmount).compareTo(BigDecimal.ZERO) > 0
                && statement.arrearsAmount.compareTo(BigDecimal.ZERO) == 0) {
            statement.settlementStatus = STATUS_PAID;
            if (statement.paidAt == null) statement.paidAt = LocalDateTime.now();
        } else if (statement.receivedAmount.compareTo(BigDecimal.ZERO) > 0) {
            statement.settlementStatus = STATUS_PARTIALLY_PAID;
            statement.paidAt = null;
        } else if (METHOD_CASH.equals(statement.settlementMethod)) {
            statement.settlementStatus = STATUS_CASH_PENDING;
            statement.paidAt = null;
        } else if (METHOD_INVOICE.equals(statement.settlementMethod) || statement.invoicedAmount.compareTo(BigDecimal.ZERO) > 0) {
            statement.settlementMethod = METHOD_INVOICE;
            statement.settlementStatus = STATUS_INVOICED_PENDING;
            statement.paidAt = null;
        } else {
            statement.settlementStatus = STATUS_UNREGISTERED;
            statement.paidAt = null;
        }
        statement.status = settlementStatusLabel(statement.settlementStatus);
    }

    private Statement createStatement(String month, List<DeliveryNote> deliveries) {
        return createStatement(month, deliveries, Map.of());
    }

    private Statement createStatement(String month, List<DeliveryNote> deliveries,
                                      Map<Long, BigDecimal> selectedQtyByDelivery) {
        return createStatement(month, deliveries, selectedQtyByDelivery, Map.of());
    }

    private Statement createStatement(String month, List<DeliveryNote> deliveries,
                                      Map<Long, BigDecimal> selectedQtyByDelivery,
                                      Map<Long, Map<Long, BigDecimal>> selectedQtyByItem) {
        if (deliveries == null || deliveries.isEmpty()) throw new BusinessException("没有可生成对账单的送货记录");
        DeliveryNote first = deliveries.get(0);
        Statement statement = new Statement();
        statement.generatedAt = LocalDateTime.now();
        statement.statementNo = documentNumberService.nextStatement(statement.generatedAt.toLocalDate(), "对账单号生成失败，请手动填写对账单号");
        statement.customerId = first.customerId;
        statement.customerName = first.customerName;
        statement.month = month;
        statement.receivableAmount = BigDecimal.ZERO;
        statement.invoicedAmount = BigDecimal.ZERO;
        statement.receivedAmount = BigDecimal.ZERO;
        statement.arrearsAmount = BigDecimal.ZERO;
        statement.settlementStatus = STATUS_UNREGISTERED;
        statement.status = settlementStatusLabel(STATUS_UNREGISTERED);
        statement = statementRepository.save(statement);
        for (DeliveryNote delivery : deliveries.stream().sorted(Comparator.comparing(note -> note.id)).toList()) {
            appendDeliveryToStatement(statement, delivery, selectedQtyByDelivery.get(delivery.id),
                    selectedQtyByItem.get(delivery.id));
        }
        recalcStatement(statement);
        statementRepository.saveAndFlush(statement);
        return statement;
    }

    private List<PendingDelivery> activePendingDeliveries() {
        List<DeliveryNote> deliveries = deliveryNoteRepository.findAll().stream()
                .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                .filter(delivery -> "已发货".equals(delivery.status))
                .toList();
        return pendingDeliveries(deliveries).stream()
                .sorted(Comparator
                        .comparing((PendingDelivery pending) -> pending.delivery().deliveryDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(pending -> pending.delivery().id, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private List<PendingDelivery> pendingDeliveries(List<DeliveryNote> deliveries) {
        List<DeliveryNote> activeDeliveries = deliveries.stream()
                .filter(delivery -> delivery != null && delivery.id != null)
                .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                .filter(delivery -> "已发货".equals(delivery.status))
                .filter(delivery -> !Boolean.TRUE.equals(delivery.reconciliationExcluded))
                .toList();
        List<Long> deliveryIds = activeDeliveries.stream().map(delivery -> delivery.id).toList();
        if (deliveryIds.isEmpty()) return List.of();

        Map<Long, List<DeliveryNoteItem>> deliveryItemsByDelivery = deliveryNoteItemRepository
                .findByDeliveryIdInAndDeletedFalse(deliveryIds).stream()
                .collect(Collectors.groupingBy(item -> item.deliveryId));
        Map<Long, List<StatementItem>> statementItemsByDelivery = statementItemRepository
                .findByDeliveryIdInAndDeletedFalse(deliveryIds).stream()
                .collect(Collectors.groupingBy(item -> item.deliveryId));

        List<PendingDelivery> pending = new ArrayList<>();
        for (DeliveryNote delivery : activeDeliveries) {
            List<DeliveryNoteItem> deliveryItems = orderedDeliveryItems(
                    deliveryItemsByDelivery.getOrDefault(delivery.id, List.of()));
            ReconciliationBalance balance = reconciliationBalance(
                    deliveryItems,
                    statementItemsByDelivery.getOrDefault(delivery.id, List.of()));
            if (balance.remainingQty().compareTo(BigDecimal.ZERO) > 0) {
                pending.add(new PendingDelivery(delivery, balance, deliveryItems));
            }
        }
        return pending;
    }

    private Map<String, Object> customerBoardRow(Customer customer, List<PendingDelivery> deliveries) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerId", customer.id);
        row.put("customerName", customer.name);
        row.put("contactPerson", customer.contactPerson);
        row.put("phone", customer.phone);
        row.put("pendingDeliveryCount", deliveries.size());
        row.put("pendingAmount", MoneyUtil.money(deliveries.stream().map(pending -> pending.balance().remainingAmount()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        row.put("latestDeliveryDate", deliveries.stream().map(pending -> pending.delivery().deliveryDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null));
        return row;
    }

    private Map<String, Object> collectionRow(Statement statement) {
        return collectionRow(
                statement,
                invoiceRepository.findByStatementIdAndDeletedFalse(statement.id),
                statementItemRepository.findByStatementIdAndDeletedFalse(statement.id));
    }

    private List<Map<String, Object>> collectionRows(List<Statement> statements) {
        List<Long> statementIds = statements.stream().map(statement -> statement.id).filter(Objects::nonNull).toList();
        if (statementIds.isEmpty()) return List.of();
        Map<Long, List<Invoice>> invoicesByStatement = invoiceRepository
                .findByStatementIdInAndDeletedFalse(statementIds).stream()
                .collect(Collectors.groupingBy(invoice -> invoice.statementId));
        Map<Long, List<StatementItem>> itemsByStatement = statementItemRepository
                .findByStatementIdInAndDeletedFalse(statementIds).stream()
                .collect(Collectors.groupingBy(item -> item.statementId));
        return statements.stream()
                .map(statement -> collectionRow(
                        statement,
                        invoicesByStatement.getOrDefault(statement.id, List.of()),
                        itemsByStatement.getOrDefault(statement.id, List.of())))
                .toList();
    }

    private Map<String, Object> collectionRow(Statement statement, List<Invoice> invoices, List<StatementItem> items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", statement.id);
        row.put("statementNo", statement.statementNo);
        row.put("customerId", statement.customerId);
        row.put("customerName", statement.customerName);
        row.put("month", statement.month);
        row.put("receivableAmount", MoneyUtil.nvl(statement.receivableAmount));
        row.put("invoicedAmount", MoneyUtil.nvl(statement.invoicedAmount));
        row.put("receivedAmount", MoneyUtil.nvl(statement.receivedAmount));
        row.put("arrearsAmount", MoneyUtil.nvl(statement.arrearsAmount));
        row.put("settlementMethod", statement.settlementMethod);
        row.put("settlementStatus", statement.settlementStatus == null ? STATUS_UNREGISTERED : statement.settlementStatus);
        row.put("status", statement.status == null ? settlementStatusLabel(statement.settlementStatus) : statement.status);
        row.put("invoiceStatus", MoneyUtil.nvl(statement.invoicedAmount).compareTo(BigDecimal.ZERO) > 0 ? "INVOICED" : "UNINVOICED");
        row.put("invoiceNos", invoices.stream().map(invoice -> invoice.invoiceNo).filter(Objects::nonNull).filter(no -> !no.isBlank()).distinct().collect(Collectors.joining("、")));
        row.put("deliveryIds", items.stream().map(item -> item.deliveryId).filter(Objects::nonNull).distinct().toList());
        row.put("orderNos", items.stream().map(item -> item.orderNo).filter(Objects::nonNull).filter(no -> !no.isBlank()).distinct().collect(Collectors.joining("、")));
        row.put("generatedAt", statement.generatedAt);
        row.put("sourceDateText", statement.sourceDateText);
        row.put("sourceSequence", statement.sourceSequence);
        row.put("registeredAt", statement.registeredAt);
        row.put("paidAt", statement.paidAt);
        return row;
    }

    private Map<String, Object> customerSummaryRow(Customer customer, List<Statement> statements) {
        BigDecimal statementAmount = statements.stream().map(statement -> MoneyUtil.nvl(statement.receivableAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentDebt = statements.stream().map(statement -> MoneyUtil.nvl(statement.arrearsAmount)).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerId", customer.id);
        row.put("customerName", customer.name);
        row.put("contactPerson", customer.contactPerson);
        row.put("phone", customer.phone);
        row.put("statementAmount", MoneyUtil.money(statementAmount));
        row.put("currentDebt", MoneyUtil.money(currentDebt));
        row.put("statementCount", statements.size());
        row.put("unpaidStatementCount", statements.stream().filter(statement -> !STATUS_PAID.equals(statement.settlementStatus)).count());
        boolean hasDebt = currentDebt.compareTo(BigDecimal.ZERO) > 0;
        row.put("accountStatus", hasDebt ? "DEBT" : "SETTLED");
        row.put("status", hasDebt ? "存在欠款" : "已结清");
        row.put("latestStatementAt", statements.stream().map(statement -> statement.generatedAt).filter(Objects::nonNull).max(LocalDateTime::compareTo).orElse(null));
        return row;
    }

    private Map<String, Object> ledgerSummaryRow(CustomerLedgerAccount account, Customer customer) {
        return ledgerSummaryRow(account, customer, customerPaymentTargets(account, customer));
    }

    private Map<String, Object> ledgerSummaryRow(CustomerLedgerAccount account, Customer customer,
                                                 List<PaymentTarget> targets) {
        BigDecimal currentDebt = customer == null
                ? MoneyUtil.money(MoneyUtil.nvl(account.currentDebt))
                : MoneyUtil.money(targets.stream()
                        .map(PaymentTarget::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add));
        boolean hasDebt = currentDebt.compareTo(BigDecimal.ZERO) > 0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerId", account.id);
        row.put("customerName", account.sheetName);
        row.put("contactPerson", customer == null ? null : customer.contactPerson);
        row.put("phone", customer == null ? null : customer.phone);
        row.put("statementAmount", MoneyUtil.nvl(account.statementAmount));
        row.put("currentDebt", currentDebt);
        row.put("statementCount", account.entryCount == null ? 0 : account.entryCount);
        row.put("unpaidStatementCount", customer == null
                ? account.openEntryCount == null ? 0 : account.openEntryCount
                : targets.size());
        row.put("accountStatus", hasDebt ? "DEBT" : "SETTLED");
        row.put("status", hasDebt ? "存在欠款" : "已结清");
        row.put("creditBalance", MoneyUtil.nvl(account.creditBalance));
        row.put("invoiceCutoff", account.invoiceCutoff);
        return row;
    }

    private Map<String, Object> ledgerEntryRow(CustomerLedgerAccount account, CustomerLedgerEntry entry) {
        boolean hasDebt = MoneyUtil.nvl(entry.remainingAmount).compareTo(BigDecimal.ZERO) > 0;
        boolean invoiced = entry.invoiceNo != null && entry.invoiceNo.matches(".*\\d{15,20}.*");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.id);
        row.put("statementNo", invoiced ? entry.invoiceNo : account.sheetName + "-" + entry.sourceRow);
        row.put("month", entry.transactionDate == null ? "-" : entry.transactionDate.toString().substring(0, 7));
        row.put("receivableAmount", MoneyUtil.nvl(entry.amount));
        row.put("invoicedAmount", invoiced ? MoneyUtil.nvl(entry.amount) : BigDecimal.ZERO);
        row.put("receivedAmount", MoneyUtil.nvl(entry.paidAmount));
        row.put("arrearsAmount", MoneyUtil.nvl(entry.remainingAmount));
        row.put("settlementMethod", invoiced ? METHOD_INVOICE : null);
        row.put("settlementStatus", hasDebt ? "DEBT" : STATUS_PAID);
        row.put("status", hasDebt ? "存在欠款" : "已结清");
        row.put("invoiceStatus", invoiced ? "INVOICED" : "UNINVOICED");
        row.put("invoiceNos", invoiced ? entry.invoiceNo : "");
        row.put("generatedAt", entry.transactionDate == null ? entry.sourceDate : entry.transactionDate.atStartOfDay());
        row.put("registeredAt", null);
        row.put("paidAt", entry.lastPaymentDate == null ? null : entry.lastPaymentDate.atStartOfDay());
        row.put("summary", entry.summary);
        row.put("sourceDate", entry.sourceDate);
        row.put("sourceRow", entry.sourceRow);
        row.put("sourceNotes", entry.notes);
        row.put("ledgerEntry", true);
        row.put("linkedStatementId", entry.linkedStatementId);
        row.put("canVoid", entry.linkedStatementId == null
                && MoneyUtil.nvl(entry.paidAmount).compareTo(BigDecimal.ZERO) == 0);
        return row;
    }

    private List<Statement> activeStatements() {
        return statementRepository.findAll().stream()
                .filter(statement -> !Boolean.TRUE.equals(statement.deleted))
                .sorted(statementComparator())
                .toList();
    }

    private Statement activeStatement(Long id) {
        return statementRepository.findById(id)
                .filter(statement -> !Boolean.TRUE.equals(statement.deleted))
                .orElseThrow(() -> new BusinessException("对账单不存在"));
    }

    private List<Statement> sortedStatements(List<Statement> statements) {
        return statements.stream().filter(statement -> !Boolean.TRUE.equals(statement.deleted)).sorted(statementComparator()).toList();
    }

    private Comparator<Statement> statementComparator() {
        return Comparator
                .comparing((Statement statement) -> !STATUS_PAID.equals(statement.settlementStatus) ? 0 : 1)
                .thenComparing(statement -> statement.generatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(statement -> statement.statementNo == null ? "" : statement.statementNo, Comparator.reverseOrder());
    }

    private void ensureSettlementMethod(Statement statement, String method) {
        if (statement.settlementMethod != null && !statement.settlementMethod.isBlank() && !statement.settlementMethod.equals(method)) {
            boolean hasFinancialRecords = !invoiceRepository.findByStatementIdAndDeletedFalse(statement.id).isEmpty()
                    || !receiptRepository.findByStatementIdAndDeletedFalse(statement.id).isEmpty();
            if (hasFinancialRecords) throw new BusinessException("该对账单已选择其他结算方式，不能混合登记");
        }
        statement.settlementMethod = method;
    }

    private String normalizeMethod(String method) {
        if (METHOD_CASH.equalsIgnoreCase(method)) return METHOD_CASH;
        if (METHOD_INVOICE.equalsIgnoreCase(method)) return METHOD_INVOICE;
        throw new BusinessException("请选择现汇或开票");
    }

    private String settlementStatusLabel(String status) {
        if (STATUS_CASH_PENDING.equals(status)) return "现汇待到账";
        if (STATUS_INVOICED_PENDING.equals(status)) return "已开票待回款";
        if (STATUS_PARTIALLY_PAID.equals(status)) return "部分到账";
        if (STATUS_PAID.equals(status)) return "已完成付款";
        return "未登记";
    }

    private String customerKey(DeliveryNote delivery) {
        return (delivery.customerId == null ? "" : delivery.customerId.toString()) + ":" + (delivery.customerName == null ? "" : delivery.customerName.trim());
    }

    private void appendDeliveryToStatement(Statement statement, DeliveryNote delivery) {
        appendDeliveryToStatement(statement, delivery, null);
    }

    private void appendDeliveryToStatement(Statement statement, DeliveryNote delivery,
                                           BigDecimal selectedQty) {
        appendDeliveryToStatement(statement, delivery, selectedQty, null);
    }

    private void appendDeliveryToStatement(Statement statement, DeliveryNote delivery,
                                           BigDecimal selectedQty, Map<Long, BigDecimal> selectedItems) {
        deliveryNoteRepository.findForReconciliation(delivery.id)
                .orElseThrow(() -> new BusinessException("送货记录不存在"));
        if (Boolean.TRUE.equals(delivery.reconciliationExcluded)) {
            throw new BusinessException("送货记录不参与对账：" + delivery.deliveryNo);
        }
        ReconciliationBalance balance = reconciliationBalance(delivery);
        if (balance.remainingQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("送货记录没有剩余待对账数量：" + delivery.deliveryNo);
        }
        BigDecimal qtyToWrite = selectedItems == null
                ? (selectedQty == null ? balance.remainingQty() : selectedQty)
                : selectedItems.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        validateStatementSelectionQty(delivery, qtyToWrite, balance.remainingQty());

        List<DeliveryNoteItem> items = orderedDeliveryItems(
                deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id));
        for (int index = 0; index < items.size(); index++) {
            DeliveryNoteItem item = items.get(index);
            if (selectedItems != null && !selectedItems.containsKey(item.id)) continue;
            BigDecimal remainingQty = balance.remainingByItem().getOrDefault(item.id, BigDecimal.ZERO);
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal itemSelectedQty = selectedItems == null ? remainingQty.min(qtyToWrite) : selectedItems.get(item.id);
            validateStatementSelectionQty(delivery, itemSelectedQty, remainingQty);
            if (itemSelectedQty.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal selectedAmount = MoneyUtil.money(itemSelectedQty.multiply(MoneyUtil.nvl(item.unitPrice)));
            StatementItem si = new StatementItem();
            si.statementId = statement.id;
            si.deliveryId = delivery.id;
            si.deliveryItemId = item.id;
            si.deliveryNo = delivery.deliveryNo;
            si.orderNo = items.size() == 1 ? delivery.orderNo : orderNoForDeliveryItem(item, delivery.orderNo);
            si.productName = item.productName;
            si.spec = item.spec;
            si.unit = item.unit;
            si.qty = itemSelectedQty;
            si.unitPrice = item.unitPrice;
            si.amount = selectedAmount;
            si.deliveryDate = delivery.deliveryDate;
            statementItemRepository.save(si);
            qtyToWrite = qtyToWrite.subtract(itemSelectedQty).max(BigDecimal.ZERO);
            if (qtyToWrite.compareTo(BigDecimal.ZERO) == 0) break;
        }
        if (qtyToWrite.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("对账数量超过送货明细剩余数量：" + delivery.deliveryNo);
        }
        statement.receivableAmount = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .map(item -> MoneyUtil.nvl(item.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Object> deliveryCandidateRow(PendingDelivery pending) {
        DeliveryNote delivery = pending.delivery();
        ReconciliationBalance balance = pending.balance();
        Map<String, Object> row = new LinkedHashMap<>();
        List<DeliveryNoteItem> items = pending.items();
        row.put("id", delivery.id);
        row.put("deliveryNo", delivery.deliveryNo);
        row.put("orderId", delivery.orderId);
        String orderNos = items.size() <= 1
                ? delivery.orderNo
                : items.stream().map(item -> orderNoForDeliveryItem(item, delivery.orderNo))
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct()
                .collect(Collectors.joining("、"));
        row.put("orderNo", orderNos.isBlank() ? delivery.orderNo : orderNos);
        row.put("orderNos", orderNos);
        row.put("customerId", delivery.customerId);
        row.put("customerName", delivery.customerName);
        row.put("deliveryDate", delivery.deliveryDate);
        row.put("deliveryMethod", delivery.deliveryMethod);
        row.put("productNames", items.stream().map(item -> item.productName).filter(Objects::nonNull).distinct().collect(Collectors.joining("、")));
        row.put("productIdentities", items.stream().map(this::productIdentityForDeliveryItem)
                .filter(value -> value != null && !value.isBlank()).distinct().collect(Collectors.joining("、")));
        row.put("products", items.stream()
                .filter(item -> balance.remainingByItem().getOrDefault(item.id, BigDecimal.ZERO).signum() > 0)
                .map(item -> {
                    Map<String, Object> product = productDetailsForDeliveryItem(item);
                    product.put("id", item.id);
                    product.put("name", item.productName);
                    product.put("unitPrice", MoneyUtil.nvl(item.unitPrice));
                    product.put("remainingQty", balance.remainingByItem().get(item.id));
                    product.put("orderNo", orderNoForDeliveryItem(item, delivery.orderNo));
                    product.put("unit", item.unit);
                    product.put("amount", MoneyUtil.money(balance.remainingByItem().get(item.id)
                            .multiply(MoneyUtil.nvl(item.unitPrice))));
                    return product;
                }).toList());
        row.put("deliveredQty", balance.totalQty());
        row.put("statementedQty", balance.reconciledQty());
        row.put("qty", balance.remainingQty());
        row.put("maxQty", balance.remainingQty());
        row.put("amount", balance.remainingAmount());
        row.put("quantitySegments", remainingQuantitySegments(items, balance.remainingByItem()));
        return row;
    }

    private List<Map<String, BigDecimal>> remainingQuantitySegments(List<DeliveryNoteItem> items,
                                                                    Map<Long, BigDecimal> remainingByItem) {
        List<Map<String, BigDecimal>> segments = new ArrayList<>();
        for (DeliveryNoteItem item : orderedDeliveryItems(items)) {
            BigDecimal remainingQty = remainingByItem.getOrDefault(item.id, BigDecimal.ZERO);
            if (remainingQty.compareTo(BigDecimal.ZERO) <= 0) continue;
            Map<String, BigDecimal> segment = new LinkedHashMap<>();
            segment.put("qty", remainingQty);
            segment.put("unitPrice", MoneyUtil.nvl(item.unitPrice));
            segments.add(segment);
        }
        return segments;
    }

    private List<DeliveryNoteItem> orderedDeliveryItems(List<DeliveryNoteItem> items) {
        return items.stream()
                .sorted(Comparator.comparing(item -> item.id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private String productIdentityForDeliveryItem(DeliveryNoteItem item) {
        Map<String, Object> details = productDetailsForDeliveryItem(item);
        String code = (String) details.get("code");
        String spec = (String) details.get("spec");
        if (code != null && spec != null) return code + " / " + spec;
        if (code != null) return code;
        if (spec != null) return spec;
        return item == null ? null : trimToNull(item.productName);
    }

    private Map<String, Object> productDetailsForDeliveryItem(DeliveryNoteItem item) {
        SalesOrderItem orderItem = item == null || item.orderItemId == null
                ? null
                : salesOrderItemRepository.findById(item.orderItemId).orElse(null);
        String code = orderItem == null ? null : trimToNull(orderItem.productCode);
        String spec = trimToNull(item == null ? null : item.spec);
        if (spec == null && orderItem != null) spec = trimToNull(orderItem.spec);
        Long productId = item == null ? null : item.productId;
        if (productId == null && orderItem != null) productId = orderItem.productId;
        if ((code == null || spec == null) && productId != null) {
            Product product = productRepository.findById(productId).orElse(null);
            if (product != null) {
                if (code == null) code = trimToNull(product.code);
                if (spec == null) spec = trimToNull(product.spec);
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", code);
        details.put("spec", spec);
        return details;
    }

    private String orderNoForDeliveryItem(DeliveryNoteItem deliveryItem, String fallback) {
        if (deliveryItem == null || deliveryItem.orderItemId == null) return fallback;
        SalesOrderItem orderItem = salesOrderItemRepository.findById(deliveryItem.orderItemId).orElse(null);
        if (orderItem == null || orderItem.orderId == null) return fallback;
        return salesOrderRepository.findById(orderItem.orderId)
                .map(order -> order.orderNo).filter(value -> value != null && !value.isBlank()).orElse(fallback);
    }

    private ReconciliationBalance reconciliationBalance(DeliveryNote delivery) {
        if (delivery == null || delivery.id == null) {
            return new ReconciliationBalance(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of());
        }
        List<DeliveryNoteItem> deliveryItems = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id);
        List<StatementItem> statementItems = statementItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id);
        return reconciliationBalance(deliveryItems, statementItems);
    }

    private ReconciliationBalance reconciliationBalance(List<DeliveryNoteItem> deliveryItems,
                                                        List<StatementItem> statementItems) {
        BigDecimal totalQty = deliveryItems.stream()
                .map(item -> MoneyUtil.nvl(item.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reconciledQty = statementItems.stream()
                .map(item -> MoneyUtil.nvl(item.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(deliveryItems.stream().map(this::historicalReconciledQuantity)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<DeliveryNoteItem> orderedItems = orderedDeliveryItems(deliveryItems);
        Map<Long, BigDecimal> remainingByItem = new LinkedHashMap<>();
        orderedItems.forEach(item -> remainingByItem.put(item.id,
                MoneyUtil.nvl(item.qty).subtract(historicalReconciledQuantity(item)).max(BigDecimal.ZERO)));
        // New statements retain the exact source line, including after an earlier partial statement is deleted.
        for (StatementItem allocated : statementItems) {
            if (allocated.deliveryItemId == null) continue;
            BigDecimal available = remainingByItem.get(allocated.deliveryItemId);
            if (available != null) remainingByItem.put(allocated.deliveryItemId,
                    available.subtract(MoneyUtil.nvl(allocated.qty).max(BigDecimal.ZERO)).max(BigDecimal.ZERO));
        }
        // Older statements have no source-line ID: use their product/price first, then the original line order.
        for (StatementItem allocated : statementItems) {
            if (allocated.deliveryItemId != null) continue;
            BigDecimal unallocated = MoneyUtil.nvl(allocated.qty).max(BigDecimal.ZERO);
            List<DeliveryNoteItem> matching = orderedItems.stream()
                    .filter(item -> Objects.equals(item.productName, allocated.productName))
                    .filter(item -> Objects.equals(trimToNull(item.spec), trimToNull(allocated.spec)))
                    .filter(item -> MoneyUtil.nvl(item.unitPrice).compareTo(MoneyUtil.nvl(allocated.unitPrice)) == 0)
                    .sorted(Comparator.comparing(item -> !Objects.equals(
                            orderNoForDeliveryItem(item, allocated.orderNo), allocated.orderNo)))
                    .toList();
            unallocated = consumeReconciledQuantity(remainingByItem, matching, unallocated);
            consumeReconciledQuantity(remainingByItem, orderedItems, unallocated);
        }
        BigDecimal remainingAmount = MoneyUtil.money(orderedItems.stream()
                .map(item -> MoneyUtil.money(remainingByItem.get(item.id).multiply(MoneyUtil.nvl(item.unitPrice))))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return new ReconciliationBalance(
                totalQty,
                reconciledQty,
                totalQty.subtract(reconciledQty).max(BigDecimal.ZERO),
                remainingAmount,
                remainingByItem
        );
    }

    private BigDecimal consumeReconciledQuantity(Map<Long, BigDecimal> remainingByItem,
                                                 List<DeliveryNoteItem> items, BigDecimal qty) {
        for (DeliveryNoteItem item : items) {
            if (qty.signum() <= 0) break;
            BigDecimal available = remainingByItem.get(item.id);
            BigDecimal consumed = available.min(qty);
            remainingByItem.put(item.id, available.subtract(consumed));
            qty = qty.subtract(consumed);
        }
        return qty;
    }

    private BigDecimal historicalReconciledQuantity(DeliveryNoteItem item) {
        return MoneyUtil.nvl(item.historicalReconciledQty).max(BigDecimal.ZERO)
                .min(MoneyUtil.nvl(item.qty).max(BigDecimal.ZERO));
    }

    private record PendingDelivery(DeliveryNote delivery, ReconciliationBalance balance,
                                   List<DeliveryNoteItem> items) {
    }

    private record ReconciliationBalance(BigDecimal totalQty, BigDecimal reconciledQty,
                                         BigDecimal remainingQty, BigDecimal remainingAmount,
                                         Map<Long, BigDecimal> remainingByItem) {
    }

    private Map<Long, List<Attachment>> invoiceAttachmentMap(List<Invoice> invoices) {
        List<Long> ids = invoices.stream().map(invoice -> invoice.id).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        return attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse("INVOICE_ATTACHMENT", ids).stream()
                .collect(Collectors.groupingBy(attachment -> attachment.businessId));
    }

    private void fillStatementNo(Invoice invoice) {
        if (invoice == null || invoice.statementId == null) return;
        statementRepository.findById(invoice.statementId)
                .filter(statement -> !Boolean.TRUE.equals(statement.deleted))
                .ifPresent(statement -> invoice.statementNo = statement.statementNo);
    }

    private void syncStatementInvoicesToDeliveries(Long statementId) {
        if (statementId == null) return;
        Statement statement = statementRepository.findById(statementId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElse(null);
        List<Long> deliveryIds = statementItemRepository.findByStatementIdAndDeletedFalse(statementId).stream()
                .map(item -> item.deliveryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (deliveryIds.isEmpty()) return;

        List<Invoice> invoices = invoiceRepository.findByStatementIdAndDeletedFalse(statementId);
        if (invoices.isEmpty()) {
            clearStatementInvoiceInfo(deliveryIds, statementId);
            return;
        }

        String invoiceNos = invoices.stream()
                .map(invoice -> invoice.invoiceNo == null ? "" : invoice.invoiceNo.trim())
                .filter(no -> !no.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        LocalDate latestInvoiceDate = invoices.stream()
                .map(invoice -> invoice.invoiceDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        BigDecimal invoiceAmount = invoices.stream()
                .map(invoice -> MoneyUtil.nvl(invoice.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (Long deliveryId : deliveryIds) {
            deliveryNoteRepository.findById(deliveryId)
                    .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                    .ifPresent(delivery -> {
                        delivery.invoiced = true;
                        delivery.invoiceNo = invoiceNos.isBlank() ? null : invoiceNos;
                        delivery.invoiceDate = latestInvoiceDate;
                        delivery.invoiceAmount = MoneyUtil.money(invoiceAmount);
                        delivery.statementId = statementId;
                        delivery.statementNo = statement == null ? null : statement.statementNo;
                        deliveryNoteRepository.save(delivery);
                    });
        }
        clearDeliveryBoardCache();
    }

    private void clearStatementInvoiceInfo(List<Long> deliveryIds, Long statementId) {
        for (Long deliveryId : deliveryIds) {
            deliveryNoteRepository.findById(deliveryId)
                    .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                    .ifPresent(delivery -> {
                        if (statementId != null && delivery.statementId != null && !Objects.equals(delivery.statementId, statementId)) {
                            return;
                        }
                        delivery.invoiced = false;
                        delivery.invoiceNo = null;
                        delivery.invoiceDate = null;
                        delivery.invoiceAmount = null;
                        delivery.statementId = null;
                        delivery.statementNo = null;
                        deliveryNoteRepository.save(delivery);
                    });
        }
        clearDeliveryBoardCache();
    }

    private void clearDeliveryBoardCache() {
        var cache = cacheManager.getCache("deliveryBoardPages");
        if (cache != null) cache.clear();
    }

    private void clearDashboardCache() {
        var cache = cacheManager.getCache("dashboard");
        if (cache != null) cache.clear();
    }

    public static class StatementSelectionRequest {
        public String month;
        public List<Long> deliveryIds;
        public List<StatementDeliverySelection> deliverySelections;
    }

    public static class StatementDeliverySelection {
        public Long deliveryItemId;
        public Long deliveryId;
        public Long returnId;
        public BigDecimal qty;

        public StatementDeliverySelection() {
        }

        public StatementDeliverySelection(Long deliveryId, BigDecimal qty) {
            this.deliveryId = deliveryId;
            this.qty = qty;
        }
    }

    public static class StatementAdjustmentRequest {
        public LocalDate adjustmentDate;
        public String documentNo;
        public String productName;
        public String spec;
        public String unit;
        public BigDecimal qty;
        public BigDecimal unitPrice;
    }

    public static class StatementAmountRequest {
        public BigDecimal receivableAmount;
    }

    public static class CustomerPaymentRequest {
        public Long statementId;
        public BigDecimal amount;
        public LocalDate paymentDate;
        public String method;
        public String bankSerial;
        public String remark;
    }

    public static class CollectionRegisterRequest {
        public String method;
        public Boolean arrived;
        public BigDecimal amount;
        public LocalDate transactionDate;
        public String bankSerial;
        public String invoiceNo;
        public BigDecimal taxRate;
        public String remark;
    }
}

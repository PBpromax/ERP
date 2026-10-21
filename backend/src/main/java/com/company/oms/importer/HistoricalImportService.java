package com.company.oms.importer;

import com.company.oms.common.DocumentNumberService;
import com.company.oms.finance.*;
import com.company.oms.masterdata.*;
import com.company.oms.sales.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class HistoricalImportService {
    private final ObjectMapper objectMapper;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;
    private final SalesOrderRepository orderRepository;
    private final SalesOrderItemRepository orderItemRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final DeliveryNoteRepository deliveryRepository;
    private final DeliveryNoteItemRepository deliveryItemRepository;
    private final StatementRepository statementRepository;
    private final StatementItemRepository statementItemRepository;
    private final InvoiceRepository invoiceRepository;
    private final ReceiptRepository receiptRepository;
    private final ArBalanceSnapshotRepository snapshotRepository;
    private final DocumentNumberService numberService;
    private final FinanceService financeService;

    public HistoricalImportService(ObjectMapper objectMapper,
                                   CustomerRepository customerRepository,
                                   CustomerContactRepository contactRepository,
                                   SalesOrderRepository orderRepository,
                                   SalesOrderItemRepository orderItemRepository,
                                   QuotationRepository quotationRepository,
                                   QuotationItemRepository quotationItemRepository,
                                   DeliveryNoteRepository deliveryRepository,
                                   DeliveryNoteItemRepository deliveryItemRepository,
                                   StatementRepository statementRepository,
                                   StatementItemRepository statementItemRepository,
                                   InvoiceRepository invoiceRepository,
                                   ReceiptRepository receiptRepository,
                                   ArBalanceSnapshotRepository snapshotRepository,
                                   DocumentNumberService numberService,
                                   FinanceService financeService) {
        this.objectMapper = objectMapper;
        this.customerRepository = customerRepository;
        this.contactRepository = contactRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.quotationRepository = quotationRepository;
        this.quotationItemRepository = quotationItemRepository;
        this.deliveryRepository = deliveryRepository;
        this.deliveryItemRepository = deliveryItemRepository;
        this.statementRepository = statementRepository;
        this.statementItemRepository = statementItemRepository;
        this.invoiceRepository = invoiceRepository;
        this.receiptRepository = receiptRepository;
        this.snapshotRepository = snapshotRepository;
        this.numberService = numberService;
        this.financeService = financeService;
    }

    @Transactional
    public Map<String, Object> importPlan(Path planPath) throws IOException {
        JsonNode root = objectMapper.readTree(planPath.toFile());
        Map<String, Integer> counts = new LinkedHashMap<>();
        mergeCustomers(root.path("customerMerges"), counts);
        applyOrderUpdates(root.path("orderUpdates"), counts);
        importQuotations(root.path("quotations"), counts);
        Map<String, DeliveryNote> deliveries = importDeliveries(root.path("deliveries"), counts);
        updateImportedOrderStatuses(root.path("orderUpdates"));
        importStatements(root.path("statements"), deliveries, counts);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", planPath.toAbsolutePath().toString());
        result.put("completedAt", LocalDateTime.now().toString());
        result.put("counts", counts);
        result.put("activeCustomers", customerRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("activeQuotations", quotationRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("activeDeliveries", deliveryRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("activeStatements", statementRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("activeInvoices", invoiceRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        result.put("activeReceipts", receiptRepository.findAll().stream().filter(item -> !Boolean.TRUE.equals(item.deleted)).count());
        return result;
    }

    private void mergeCustomers(JsonNode merges, Map<String, Integer> counts) {
        int merged = 0;
        int contacts = 0;
        for (JsonNode node : merges) {
            Long masterId = node.path("masterCustomerId").asLong();
            Customer master = customerRepository.findById(masterId).orElseThrow(() -> new IllegalStateException("主客户不存在：" + masterId));
            String company = node.path("company").asText();
            List<Customer> related = new ArrayList<>();
            related.add(master);
            for (JsonNode duplicateId : node.path("duplicateCustomerIds")) {
                customerRepository.findById(duplicateId.asLong()).ifPresent(related::add);
            }

            Set<Long> duplicateIds = new LinkedHashSet<>();
            for (Customer customer : related) {
                if (!Objects.equals(customer.id, master.id)) duplicateIds.add(customer.id);
            }
            if (!duplicateIds.isEmpty()) {
                rebindCustomerReferences(master, duplicateIds);
                for (Customer duplicate : related) {
                    if (duplicateIds.contains(duplicate.id) && !Boolean.TRUE.equals(duplicate.deleted)) {
                        duplicate.deleted = true;
                        customerRepository.save(duplicate);
                        merged++;
                    }
                }
            }

            LinkedHashMap<String, CustomerContact> contactSeeds = new LinkedHashMap<>();
            for (Customer customer : related) {
                if (notBlank(customer.contactPerson)) {
                    CustomerContact seed = new CustomerContact();
                    seed.name = customer.contactPerson.trim();
                    seed.phone = customer.phone;
                    seed.email = customer.email;
                    seed.primaryContact = Objects.equals(customer.id, master.id);
                    contactSeeds.putIfAbsent(seed.name, seed);
                }
            }
            for (JsonNode contactName : node.path("contacts")) {
                String name = contactName.asText().trim();
                if (!name.isBlank()) {
                    CustomerContact seed = new CustomerContact();
                    seed.name = name;
                    seed.primaryContact = false;
                    contactSeeds.putIfAbsent(name, seed);
                }
            }
            boolean hasPrimary = contactRepository.findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(master.id).stream()
                    .anyMatch(contact -> Boolean.TRUE.equals(contact.primaryContact));
            for (CustomerContact seed : contactSeeds.values()) {
                if (contactRepository.existsByCustomerIdAndNameAndDeletedFalse(master.id, seed.name)) continue;
                seed.customerId = master.id;
                seed.enabled = true;
                seed.primaryContact = !hasPrimary && Boolean.TRUE.equals(seed.primaryContact);
                if (!hasPrimary && contactSeeds.values().iterator().next() == seed) seed.primaryContact = true;
                if (Boolean.TRUE.equals(seed.primaryContact)) hasPrimary = true;
                seed.createdAt = historicalTimestamp(LocalDate.of(2026, 1, 1));
                contactRepository.save(seed);
                contacts++;
            }
            CustomerContact primary = contactRepository.findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(master.id).stream().findFirst().orElse(null);
            master.name = company;
            if (primary != null) {
                master.contactPerson = primary.name;
                if (notBlank(primary.phone)) master.phone = primary.phone;
                if (notBlank(primary.email)) master.email = primary.email;
            }
            customerRepository.save(master);
        }
        counts.put("customersMerged", merged);
        counts.put("contactsCreated", contacts);
    }

    private void rebindCustomerReferences(Customer master, Set<Long> duplicateIds) {
        for (SalesOrder item : orderRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                orderRepository.save(item);
            }
        }
        for (Quotation item : quotationRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                quotationRepository.save(item);
            }
        }
        for (DeliveryNote item : deliveryRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                deliveryRepository.save(item);
            }
        }
        for (Statement item : statementRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                statementRepository.save(item);
            }
        }
        for (Invoice item : invoiceRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                invoiceRepository.save(item);
            }
        }
        for (Receipt item : receiptRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                receiptRepository.save(item);
            }
        }
        for (ArBalanceSnapshot item : snapshotRepository.findAll()) {
            if (duplicateIds.contains(item.customerId)) {
                item.customerId = master.id;
                item.customerName = master.name;
                snapshotRepository.save(item);
            }
        }
        for (CustomerContact contact : contactRepository.findAll()) {
            if (duplicateIds.contains(contact.customerId)) {
                if (contactRepository.existsByCustomerIdAndNameAndDeletedFalse(master.id, contact.name)) {
                    contact.deleted = true;
                } else {
                    contact.customerId = master.id;
                }
                contactRepository.save(contact);
            }
        }
    }

    private void applyOrderUpdates(JsonNode updates, Map<String, Integer> counts) {
        int changed = 0;
        for (JsonNode node : updates) {
            SalesOrder order = orderRepository.findById(node.path("orderId").asLong()).orElseThrow();
            String append = node.path("appendRemark").asText();
            if (notBlank(append) && (order.remark == null || !order.remark.contains(append))) {
                order.remark = appendRemark(order.remark, append);
                changed++;
            }
            if (node.path("cancelled").asBoolean()) {
                order.status = "已取消";
                order.remark = appendRemark(order.remark, "历史源表标记取消：保留报价单和订单，不生成发货及对账记录。 ");
                changed++;
            }
            orderRepository.save(order);
        }
        counts.put("ordersUpdated", changed);
    }

    private void importQuotations(JsonNode nodes, Map<String, Integer> counts) {
        int created = 0;
        int skipped = 0;
        for (JsonNode node : nodes) {
            String sourceKey = node.path("sourceKey").asText();
            if (quotationRepository.findBySourceKeyAndDeletedFalse(sourceKey).isPresent()) {
                skipped++;
                continue;
            }
            SalesOrder order = orderRepository.findById(node.path("orderId").asLong()).orElseThrow();
            SalesOrderItem orderItem = orderItemRepository.findById(node.path("itemId").asLong()).orElseThrow();
            LocalDate date = LocalDate.parse(node.path("quoteDate").asText());
            String quoteNo = node.path("quoteNo").asText();
            if (quotationRepository.existsByQuoteNo(quoteNo)) {
                quoteNo = numberService.nextQuotation(date, "历史报价单号生成失败");
            }
            Quotation quote = new Quotation();
            quote.quoteNo = quoteNo;
            quote.customerId = order.customerId;
            quote.customerName = order.customerName;
            quote.customerAddress = order.customerAddress;
            quote.customerPhone = order.customerPhone;
            quote.customerEmail = order.customerEmail;
            quote.contactPerson = order.contactPerson;
            quote.status = "已转订单";
            quote.quoteDate = date;
            quote.totalAmount = money(order.totalAmount);
            quote.remark = "历史补齐报价单；来源销售订单：" + order.orderNo;
            quote.versionNo = 1;
            quote.convertedOrderNo = order.orderNo;
            quote.sourceKey = sourceKey;
            quote.historicalImported = true;
            quote.createdAt = historicalTimestamp(date);
            quote = quotationRepository.save(quote);

            QuotationItem item = new QuotationItem();
            item.quotationId = quote.id;
            item.productId = orderItem.productId;
            item.productCode = orderItem.productCode;
            item.productName = orderItem.productName;
            item.spec = orderItem.spec;
            item.unit = orderItem.unit;
            item.qty = orderItem.qty;
            item.unitPrice = orderItem.unitPrice;
            item.amount = orderItem.amount;
            item.createdAt = historicalTimestamp(date);
            quotationItemRepository.save(item);
            order.sourceQuoteNo = quote.quoteNo;
            orderRepository.save(order);
            created++;
        }
        counts.put("quotationsCreated", created);
        counts.put("quotationsSkipped", skipped);
    }

    private Map<String, DeliveryNote> importDeliveries(JsonNode nodes, Map<String, Integer> counts) {
        Map<String, DeliveryNote> result = new LinkedHashMap<>();
        int created = 0;
        int skipped = 0;
        for (JsonNode node : nodes) {
            String sourceKey = node.path("sourceKey").asText();
            Optional<DeliveryNote> existing = deliveryRepository.findBySourceKeyAndDeletedFalse(sourceKey);
            if (existing.isPresent()) {
                result.put(sourceKey, existing.get());
                skipped++;
                continue;
            }
            SalesOrder order = orderRepository.findById(node.path("orderId").asLong()).orElseThrow();
            SalesOrderItem orderItem = orderItemRepository.findById(node.path("itemId").asLong()).orElseThrow();
            LocalDate date = LocalDate.parse(node.path("deliveryDate").asText());
            BigDecimal qty = decimal(node.path("qty").asText());
            BigDecimal remaining = nvl(orderItem.qty).subtract(nvl(orderItem.shippedQty));
            if (qty.compareTo(remaining) > 0) {
                throw new IllegalStateException("历史发货超过订单数量：" + order.orderNo + "，剩余" + remaining + "，拟发" + qty);
            }
            int seq = deliveryRepository.findByOrderIdAndDeletedFalse(order.id).stream()
                    .map(item -> item.deliverySeq == null ? 0 : item.deliverySeq)
                    .max(Integer::compareTo).orElse(0) + 1;
            DeliveryNote delivery = new DeliveryNote();
            delivery.deliveryNo = numberService.nextDelivery(date, "历史送货单号生成失败");
            delivery.deliverySeq = seq;
            delivery.orderId = order.id;
            delivery.orderNo = order.orderNo;
            delivery.customerId = order.customerId;
            delivery.customerName = order.customerName;
            delivery.status = "已发货";
            delivery.deliveryDate = date;
            delivery.deliveryQty = qty;
            delivery.deliveryMethod = node.path("method").asText();
            delivery.totalAmount = money(qty.multiply(nvl(orderItem.unitPrice)));
            delivery.printPrice = true;
            delivery.qrCodeContent = "/delivery/" + delivery.deliveryNo;
            delivery.confirmer = "历史导入";
            delivery.confirmTime = historicalTimestamp(date);
            delivery.historicalImported = true;
            delivery.inventoryPosted = false;
            delivery.invoiced = false;
            delivery.sourceKey = sourceKey;
            delivery.sourceComment = node.path("sourceComment").asText();
            delivery.remark = "历史导入，不扣减当前库存";
            delivery.createdAt = historicalTimestamp(date);
            delivery = deliveryRepository.save(delivery);

            DeliveryNoteItem item = new DeliveryNoteItem();
            item.deliveryId = delivery.id;
            item.orderItemId = orderItem.id;
            item.productId = orderItem.productId;
            item.productName = orderItem.productName;
            item.spec = orderItem.spec;
            item.unit = orderItem.unit;
            item.qty = qty;
            item.unitPrice = orderItem.unitPrice;
            item.amount = delivery.totalAmount;
            item.createdAt = historicalTimestamp(date);
            deliveryItemRepository.save(item);

            orderItem.shippedQty = nvl(orderItem.shippedQty).add(qty);
            orderItemRepository.save(orderItem);
            result.put(sourceKey, delivery);
            created++;
        }
        counts.put("deliveriesCreated", created);
        counts.put("deliveriesSkipped", skipped);
        return result;
    }

    private void updateImportedOrderStatuses(JsonNode updates) {
        for (JsonNode node : updates) {
            SalesOrder order = orderRepository.findById(node.path("orderId").asLong()).orElseThrow();
            if (node.path("cancelled").asBoolean()) {
                order.status = "已取消";
                orderRepository.save(order);
                continue;
            }
            boolean any = false;
            boolean all = true;
            for (SalesOrderItem item : orderItemRepository.findByOrderIdAndDeletedFalse(order.id)) {
                any |= nvl(item.shippedQty).compareTo(BigDecimal.ZERO) > 0;
                all &= nvl(item.shippedQty).compareTo(nvl(item.qty)) >= 0;
            }
            order.status = all
                    ? SalesService.DELIVERY_STATUS_DELIVERED
                    : any ? SalesService.DELIVERY_STATUS_PARTIAL : SalesService.DELIVERY_STATUS_UNSHIPPED;
            order.actualDeliveryDate = all
                    ? deliveryRepository.findByOrderIdAndDeletedFalse(order.id).stream()
                            .map(delivery -> delivery.deliveryDate)
                            .filter(Objects::nonNull)
                            .max(LocalDate::compareTo)
                            .orElse(null)
                    : null;
            orderRepository.save(order);
        }
    }

    private void importStatements(JsonNode nodes, Map<String, DeliveryNote> deliveries, Map<String, Integer> counts) {
        int statements = 0;
        int invoices = 0;
        int receipts = 0;
        int skipped = 0;
        for (JsonNode node : nodes) {
            String sourceKey = node.path("sourceKey").asText();
            if (statementRepository.findBySourceKeyAndDeletedFalse(sourceKey).isPresent()) {
                skipped++;
                continue;
            }
            List<DeliveryNote> statementDeliveries = new ArrayList<>();
            for (JsonNode deliveryKey : node.path("deliverySourceKeys")) {
                DeliveryNote delivery = deliveries.get(deliveryKey.asText());
                if (delivery == null) {
                    delivery = deliveryRepository.findBySourceKeyAndDeletedFalse(deliveryKey.asText())
                            .orElseThrow(() -> new IllegalStateException("找不到历史发货来源：" + deliveryKey.asText()));
                }
                statementDeliveries.add(delivery);
            }
            if (statementDeliveries.isEmpty()) continue;
            DeliveryNote first = statementDeliveries.get(0);
            LocalDate date = LocalDate.parse(node.path("generatedDate").asText());
            BigDecimal receivable = decimal(node.path("receivableAmount").asText());
            Statement statement = new Statement();
            statement.statementNo = numberService.nextStatement(date, "历史对账单号生成失败");
            statement.customerId = first.customerId;
            statement.customerName = first.customerName;
            statement.month = date.toString().substring(0, 7);
            statement.receivableAmount = money(receivable);
            statement.invoicedAmount = BigDecimal.ZERO;
            statement.receivedAmount = BigDecimal.ZERO;
            statement.arrearsAmount = money(receivable);
            statement.settlementMethod = node.path("method").asText();
            statement.settlementStatus = FinanceService.STATUS_UNREGISTERED;
            statement.status = "未登记";
            statement.generatedAt = historicalTimestamp(date);
            statement.registeredAt = historicalTimestamp(date);
            statement.sourceKey = sourceKey;
            statement.historicalImported = true;
            statement.sourceRemark = node.path("sourceRemark").asText();
            statement.createdAt = historicalTimestamp(date);
            statement = statementRepository.save(statement);

            BigDecimal itemTotal = BigDecimal.ZERO;
            for (DeliveryNote delivery : statementDeliveries) {
                for (DeliveryNoteItem deliveryItem : deliveryItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id)) {
                    StatementItem item = new StatementItem();
                    item.statementId = statement.id;
                    item.deliveryId = delivery.id;
                    item.deliveryNo = delivery.deliveryNo;
                    item.orderNo = delivery.orderNo;
                    item.productName = deliveryItem.productName;
                    item.spec = deliveryItem.spec;
                    item.unit = deliveryItem.unit;
                    item.qty = deliveryItem.qty;
                    item.unitPrice = deliveryItem.unitPrice;
                    item.amount = money(deliveryItem.amount);
                    item.deliveryDate = delivery.deliveryDate;
                    item.createdAt = historicalTimestamp(date);
                    statementItemRepository.save(item);
                    itemTotal = itemTotal.add(nvl(item.amount));
                }
                delivery.statementId = statement.id;
                delivery.statementNo = statement.statementNo;
                deliveryRepository.save(delivery);
            }
            BigDecimal adjustment = money(receivable.subtract(itemTotal));
            if (adjustment.compareTo(BigDecimal.ZERO) != 0) {
                StatementItem item = new StatementItem();
                item.statementId = statement.id;
                item.orderNo = "历史调整";
                item.productName = "历史对账金额调整";
                item.unit = "项";
                item.qty = BigDecimal.ONE;
                item.unitPrice = adjustment;
                item.amount = adjustment;
                item.deliveryDate = date;
                item.createdAt = historicalTimestamp(date);
                statementItemRepository.save(item);
            }

            JsonNode invoiceNode = node.path("invoice");
            Invoice invoice = null;
            if (!invoiceNode.isMissingNode() && !invoiceNode.isNull()) {
                invoice = new Invoice();
                invoice.statementId = statement.id;
                invoice.invoiceNo = invoiceNode.path("invoiceNo").asText();
                invoice.invoiceDate = LocalDate.parse(invoiceNode.path("invoiceDate").asText());
                invoice.amount = money(decimal(invoiceNode.path("amount").asText()));
                invoice.customerId = statement.customerId;
                invoice.customerName = statement.customerName;
                invoice.status = "已开票";
                invoice.remark = invoiceNode.path("remark").asText();
                invoice.createdAt = historicalTimestamp(invoice.invoiceDate);
                invoice = invoiceRepository.save(invoice);
                for (DeliveryNote delivery : statementDeliveries) {
                    delivery.invoiced = true;
                    delivery.invoiceNo = invoice.invoiceNo;
                    delivery.invoiceDate = invoice.invoiceDate;
                    delivery.invoiceAmount = delivery.totalAmount;
                    deliveryRepository.save(delivery);
                }
                invoices++;
            }

            JsonNode receiptNode = node.path("receipt");
            LocalDate receiptDate = null;
            if (!receiptNode.isMissingNode() && !receiptNode.isNull()) {
                BigDecimal amount = money(decimal(receiptNode.path("amount").asText()));
                if (amount.compareTo(BigDecimal.ZERO) > 0) {
                    receiptDate = LocalDate.parse(receiptNode.path("date").asText());
                    Receipt receipt = new Receipt();
                    receipt.receiptNo = numberService.nextReceipt(receiptDate, "历史收款单号生成失败");
                    receipt.statementId = statement.id;
                    receipt.invoiceId = invoice == null ? null : invoice.id;
                    receipt.customerId = statement.customerId;
                    receipt.customerName = statement.customerName;
                    receipt.receiptDate = receiptDate;
                    receipt.amount = amount.min(receivable);
                    receipt.method = receiptNode.path("method").asText();
                    receipt.remark = receiptNode.path("remark").asText();
                    receipt.createdAt = historicalTimestamp(receiptDate);
                    receiptRepository.save(receipt);
                    receipts++;
                }
            }
            financeService.recalcStatement(statement.id);
            statement = statementRepository.findById(statement.id).orElseThrow();
            statement.generatedAt = historicalTimestamp(date);
            statement.registeredAt = historicalTimestamp(date);
            if (FinanceService.STATUS_PAID.equals(statement.settlementStatus) && receiptDate != null) {
                statement.paidAt = historicalTimestamp(receiptDate);
            }
            statementRepository.save(statement);
            statements++;
        }
        counts.put("statementsCreated", statements);
        counts.put("statementsSkipped", skipped);
        counts.put("invoicesCreated", invoices);
        counts.put("receiptsCreated", receipts);
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static BigDecimal money(BigDecimal value) {
        return nvl(value);
    }

    private static LocalDateTime historicalTimestamp(LocalDate date) {
        return LocalDateTime.of(date, LocalTime.of(10, 0));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String appendRemark(String existing, String value) {
        if (!notBlank(value)) return existing;
        if (!notBlank(existing)) return value.trim();
        if (existing.contains(value.trim())) return existing;
        return existing.trim() + "\n" + value.trim();
    }
}

package com.company.oms.sales;

import com.company.oms.common.*;
import com.company.oms.finance.FinanceService;
import com.company.oms.inventory.InventoryService;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import com.company.oms.notify.NotifyService;
import com.company.oms.production.ProductionService;
import com.company.oms.quality.Attachment;
import com.company.oms.quality.AttachmentRepository;
import com.company.oms.system.CurrentUser;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SalesService {
    public static final String DELIVERY_STATUS_UNSHIPPED = "未发货";
    public static final String DELIVERY_STATUS_PARTIAL = "部分发货";
    public static final String DELIVERY_STATUS_DELIVERED = "已送";
    public static final String FULFILLMENT_STATUS_DELIVERED = "已发送";
    public static final String FULFILLMENT_STATUS_OVERDUE_UNSHIPPED = "逾期未发货";
    public static final String FULFILLMENT_STATUS_OVERDUE_PARTIAL = "逾期部分发货";
    private static final String DEFAULT_ORDER_PAGE_SQL = """
            select o.id, o.order_no, o.source_order_no, o.customer_id, o.customer_name,
                   o.customer_address, o.customer_phone, o.customer_email, o.contact_person,
                   o.auditor, o.order_date, o.status, o.delivery_date, o.actual_delivery_date,
                   o.remark, count(*) over() as total_count
            from sales_order o
            where o.deleted = false
              and (:filterByYear = false or (o.order_date >= :startDate and o.order_date < :endDate))
            order by o.order_no desc, o.id desc
            limit :limit offset :offset
            """;
    private static final String DEFAULT_ORDER_ITEMS_SQL = """
            select i.id, i.order_id, i.product_id, i.product_code, i.product_name, i.spec,
                   i.unit, i.qty, i.shipped_qty, i.unit_price, i.amount, i.due_date,
                   (select max(d.delivery_date)
                      from delivery_note_item di
                      join delivery_note d on d.id = di.delivery_id
                     where di.order_item_id = i.id
                       and di.deleted = false
                       and d.deleted = false
                       and d.status = '已发货'
                       and d.delivery_date is not null) as last_delivery_date
            from sales_order_item i
            where i.deleted = false and i.order_id in (:orderIds)
            order by i.created_at desc, i.id desc
            """;
    private static final String DEFAULT_DELIVERY_BOARD_SQL = """
            select i.id as item_id, i.product_code, i.product_name, i.unit, i.qty, i.shipped_qty,
                   i.due_date, o.id as order_id, o.order_no, o.source_order_no, o.customer_id,
                   o.customer_name, o.customer_address, o.customer_phone, o.customer_email,
                   o.contact_person, o.auditor, o.order_date, o.status as order_status,
                   o.delivery_date as order_delivery_date, o.actual_delivery_date, o.remark,
                   coalesce((select sum(r.qty) from sales_return r
                              where r.order_item_id = i.id and r.deleted = false), 0) as returned_qty,
                   (select max(d.delivery_date)
                      from delivery_note_item di
                      join delivery_note d on d.id = di.delivery_id
                     where di.order_item_id = i.id and di.deleted = false and d.deleted = false
                       and d.status = '已发货' and d.delivery_date is not null) as last_delivery_date,
                   coalesce((select sum(di.qty)
                               from delivery_note_item di
                               join delivery_note d on d.id = di.delivery_id
                              where di.order_item_id = i.id and di.deleted = false and d.deleted = false
                                and (d.invoiced = false or d.invoiced is null)), 0) as uninvoiced_qty,
                   count(*) over() as total_count
            from sales_order_item i
            join sales_order o on o.id = i.order_id
            where i.deleted = false and o.deleted = false
            order by o.order_no desc, o.id desc, i.created_at desc, i.id desc
            limit :limit offset :offset
            """;

    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliveryNoteItemRepository deliveryNoteItemRepository;
    private final AttachmentRepository attachmentRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final ProductionService productionService;
    private final NotifyService notifyService;
    private final DocumentNumberService documentNumberService;
    private final FinanceService financeService;
    private final SalesReturnService salesReturnService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SalesService(QuotationRepository quotationRepository,
                        QuotationItemRepository quotationItemRepository,
                        SalesOrderRepository salesOrderRepository,
                        SalesOrderItemRepository salesOrderItemRepository,
                        DeliveryNoteRepository deliveryNoteRepository,
                        DeliveryNoteItemRepository deliveryNoteItemRepository,
                        AttachmentRepository attachmentRepository,
                        CustomerRepository customerRepository,
                        ProductRepository productRepository,
                        InventoryService inventoryService,
                        ProductionService productionService,
                        NotifyService notifyService,
                        DocumentNumberService documentNumberService,
                        FinanceService financeService, SalesReturnService salesReturnService,
                        NamedParameterJdbcTemplate jdbcTemplate) {
        this.quotationRepository = quotationRepository;
        this.quotationItemRepository = quotationItemRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.deliveryNoteItemRepository = deliveryNoteItemRepository;
        this.attachmentRepository = attachmentRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
        this.inventoryService = inventoryService;
        this.productionService = productionService;
        this.notifyService = notifyService;
        this.documentNumberService = documentNumberService;
        this.financeService = financeService;
        this.salesReturnService = salesReturnService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Quotation saveQuotationWithItems(Quotation quotation, List<QuotationItem> items) {
        applyCustomerFromMasterData(quotation);
        if (quotation.quoteDate == null) quotation.quoteDate = LocalDate.now();
        if (quotation.quoteNo == null || quotation.quoteNo.isBlank()) {
            quotation.quoteNo = nextAvailableQuotationNo(quotation.quoteDate);
        } else {
            quotation.quoteNo = quotation.quoteNo.trim();
            boolean duplicated = quotation.id == null
                    ? documentNumberService.quotationExists(quotation.quoteNo)
                    : documentNumberService.quotationExistsExcept(quotation.quoteNo, quotation.id);
            if (duplicated) {
                throw new BusinessException("报价单号已存在，请更换报价单号");
            }
        }
        if (quotation.status == null || quotation.status.isBlank()) quotation.status = "草稿";
        quotation.versionNo = quotation.versionNo == null ? 1 : quotation.versionNo + 1;
        List<Long> productIds = items.stream()
                .map(item -> item.productId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, Product> productsById = productIds.isEmpty()
                ? Map.of()
                : productRepository.findAllById(productIds).stream()
                .filter(product -> !Boolean.TRUE.equals(product.deleted))
                .collect(Collectors.toMap(product -> product.id, product -> product));
        if (productsById.size() != productIds.size()) {
            throw new BusinessException("报价产品明细关联的产品不存在");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (QuotationItem item : items) {
            item.productCode = trim(item.productCode);
            item.productName = trim(item.productName);
            if (item.productName == null || item.productName.isBlank()) {
                throw new BusinessException("报价产品明细请填写产品名称");
            }
            if (item.productId != null) {
                Product product = productsById.get(item.productId);
                item.productCode = trim(product.code);
                item.productName = product.name;
            } else {
                throw new BusinessException("报价产品明细请选择产品资料");
            }
            item.amount = MoneyUtil.money(MoneyUtil.nvl(item.qty).multiply(MoneyUtil.nvl(item.unitPrice)));
            total = total.add(item.amount);
        }
        quotation.totalAmount = MoneyUtil.money(total);
        Quotation saved = quotationRepository.save(quotation);

        List<QuotationItem> oldItems = quotationItemRepository.findByQuotationIdAndDeletedFalse(saved.id);
        oldItems.forEach(old -> old.deleted = true);
        if (!oldItems.isEmpty()) quotationItemRepository.saveAll(oldItems);
        for (QuotationItem item : items) {
            item.id = null;
            item.quotationId = saved.id;
        }
        quotationItemRepository.saveAll(items);
        return saved;
    }

    public String previewNextQuotationNo(LocalDate date) {
        return nextAvailableQuotationNo(date == null ? LocalDate.now() : date);
    }

    public String previewNextOrderNo(LocalDate date) {
        return nextAvailableOrderNo(date == null ? LocalDate.now() : date);
    }

    @Transactional
    public void deleteQuotation(Long id) {
        Quotation quotation = quotationRepository.findById(id)
                .orElseThrow(() -> new BusinessException("报价单不存在"));
        quotationItemRepository.deleteAll(quotationItemRepository.findByQuotationId(id));
        quotationRepository.delete(quotation);
    }

    private String nextAvailableQuotationNo(LocalDate date) {
        return documentNumberService.nextQuotation(date, "报价单号生成失败，请手动填写报价单号");
    }

    private String nextAvailableOrderNo(LocalDate date) {
        return documentNumberService.nextOrder(date, "订单号生成失败，请手动填写订单号");
    }

    @Transactional
    public Quotation sendQuotation(Long id) {
        Quotation quotation = quotationRepository.findById(id).orElseThrow(() -> new BusinessException("报价单不存在"));
        quotation.status = "已发送";
        return quotationRepository.save(quotation);
    }

    public Map<String, Object> quotationDetail(Long id) {
        Quotation quotation = quotationRepository.findById(id).orElseThrow(() -> new BusinessException("报价单不存在"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("quotation", quotation);
        detail.put("items", quotationItemRepository.findByQuotationIdAndDeletedFalse(id));
        return detail;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder convertQuotationToOrder(Long quotationId) {
        return convertQuotationToOrder(quotationId, new QuotationConvertRequest());
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder convertQuotationToOrder(Long quotationId, QuotationConvertRequest request) {
        Quotation quotation = quotationRepository.findById(quotationId).orElseThrow(() -> new BusinessException("报价单不存在"));
        if ("已转订单".equals(quotation.status)) {
            throw new BusinessException("报价单已转订单，不能重复转换");
        }
        SalesOrder order = new SalesOrder();
        order.orderDate = request.orderDate == null ? LocalDate.now() : request.orderDate;
        order.orderNo = resolveNewOrderNo(request.orderNo, order.orderDate);
        order.sourceQuoteNo = quotation.quoteNo;
        order.customerId = quotation.customerId;
        order.customerName = quotation.customerName;
        order.customerAddress = quotation.customerAddress;
        order.customerPhone = quotation.customerPhone;
        order.customerEmail = quotation.customerEmail;
        order.contactPerson = quotation.contactPerson;
        order.auditor = request.auditor;
        order.status = DELIVERY_STATUS_UNSHIPPED;
        order.deliveryDate = request.deliveryDate;
        order.totalAmount = quotation.totalAmount;
        SalesOrder saved = salesOrderRepository.save(order);

        List<SalesOrderItem> orderItems = new ArrayList<>();
        for (QuotationItem qi : quotationItemRepository.findByQuotationIdAndDeletedFalse(quotationId)) {
            SalesOrderItem oi = new SalesOrderItem();
            oi.orderId = saved.id;
            oi.productId = qi.productId;
            oi.productCode = qi.productCode;
            oi.productName = qi.productName;
            oi.spec = qi.spec;
            oi.unit = qi.unit;
            oi.qty = qi.qty;
            oi.shippedQty = BigDecimal.ZERO;
            oi.unitPrice = qi.unitPrice;
            oi.taxRate = qi.taxRate;
            oi.amount = qi.amount;
            oi.dueDate = request.deliveryDate;
            orderItems.add(oi);
        }
        salesOrderItemRepository.saveAll(orderItems);
        productionService.syncPlansForOrder(saved);

        quotation.status = "已转订单";
        quotation.convertedOrderNo = saved.orderNo;
        quotationRepository.save(quotation);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder saveOrderWithItems(SalesOrder order, List<SalesOrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException("请至少填写一条产品明细");
        }
        SalesOrder target = order.id == null
                ? order
                : salesOrderRepository.findById(order.id)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .orElseThrow(() -> new BusinessException("订单不存在"));
        if (order.id != null) copyOrderFields(order, target);
        order = target;
        applyCustomerFromMasterData(order);
        if (order.orderDate == null) order.orderDate = LocalDate.now();
        if (order.orderNo == null || order.orderNo.isBlank()) {
            order.orderNo = nextAvailableOrderNo(order.orderDate);
        } else {
            order.orderNo = order.orderNo.trim();
            boolean duplicated = order.id == null
                    ? documentNumberService.orderExists(order.orderNo)
                    : documentNumberService.orderExistsExcept(order.orderNo, order.id);
            if (duplicated) {
                throw new BusinessException("订单号已存在，请更换订单号");
            }
        }
        if (order.status == null || order.status.isBlank()) order.status = DELIVERY_STATUS_UNSHIPPED;

        SalesOrder saved = salesOrderRepository.save(order);
        List<SalesOrderItem> oldItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(saved.id);
        Map<Long, SalesOrderItem> oldById = oldItems.stream()
                .collect(Collectors.toMap(item -> item.id, item -> item));
        Set<Long> retainedIds = new HashSet<>();
        List<SalesOrderItem> savedItems = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrderItem requested : items) {
            SalesOrderItem item;
            if (requested.id == null) {
                item = new SalesOrderItem();
                item.orderId = saved.id;
                item.shippedQty = BigDecimal.ZERO;
            } else {
                item = oldById.get(requested.id);
                if (item == null) throw new BusinessException("订单产品明细不存在或不属于当前订单");
                retainedIds.add(item.id);
            }
            validateAndCopyOrderItem(requested, item, order.deliveryDate);
            total = total.add(MoneyUtil.nvl(item.amount));
            savedItems.add(item);
        }
        for (SalesOrderItem old : oldItems) {
            if (retainedIds.contains(old.id)) continue;
            if (MoneyUtil.nvl(old.shippedQty).compareTo(BigDecimal.ZERO) > 0
                    || !deliveryNoteItemRepository.findByOrderItemIdAndDeletedFalse(old.id).isEmpty()) {
                throw new BusinessException("产品“" + old.productName + "”已有发货记录，不能从订单中删除");
            }
            old.deleted = true;
            salesOrderItemRepository.save(old);
        }
        salesOrderItemRepository.saveAll(savedItems);
        saved.totalAmount = MoneyUtil.money(total);
        saved = salesOrderRepository.save(saved);
        productionService.syncPlansForOrder(saved);
        updateSimpleOrderStatus(saved);
        return saved;
    }

    private void applyCustomerFromMasterData(Quotation quotation) {
        Customer customer = requireCustomer(quotation.customerId);
        quotation.customerName = customer.name;
    }

    private void applyCustomerFromMasterData(SalesOrder order) {
        Customer customer = requireCustomer(order.customerId);
        order.customerName = customer.name;
    }

    private Customer requireCustomer(Long customerId) {
        if (customerId == null) throw new BusinessException("客户资料中没有该客户，请先在客户档案新增");
        return customerRepository.findById(customerId)
                .filter(customer -> !Boolean.TRUE.equals(customer.deleted))
                .orElseThrow(() -> new BusinessException("客户资料中没有该客户，请先在客户档案新增"));
    }

    private void copyOrderFields(SalesOrder source, SalesOrder target) {
        target.orderNo = source.orderNo;
        target.sourceOrderNo = source.sourceOrderNo;
        target.sourceQuoteNo = source.sourceQuoteNo;
        target.customerId = source.customerId;
        target.customerName = source.customerName;
        target.customerAddress = source.customerAddress;
        target.customerPhone = source.customerPhone;
        target.customerEmail = source.customerEmail;
        target.contactPerson = source.contactPerson;
        target.orderDate = source.orderDate;
        target.deliveryDate = source.deliveryDate;
        target.remark = source.remark;
        target.auditor = source.auditor;
    }

    private void validateAndCopyOrderItem(SalesOrderItem source, SalesOrderItem target, LocalDate fallbackDueDate) {
        if (source.productId == null) throw new BusinessException("订单产品明细请选择产品资料");
        Product product = productRepository.findById(source.productId)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .orElseThrow(() -> new BusinessException("订单产品明细关联的产品不存在"));
        BigDecimal requestedQty = MoneyUtil.nvl(source.qty);
        if (requestedQty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("订单产品数量必须大于 0");
        BigDecimal shippedQty = MoneyUtil.nvl(target.shippedQty);
        if (requestedQty.compareTo(shippedQty) < 0) {
            throw new BusinessException("产品“" + target.productName + "”的订单数量不能小于已发货数量");
        }
        if (target.id != null && shippedQty.compareTo(BigDecimal.ZERO) > 0
                && !Objects.equals(target.productId, source.productId)) {
            throw new BusinessException("产品“" + target.productName + "”已有发货记录，不能更换产品");
        }
        target.productId = product.id;
        target.productCode = trim(product.code);
        target.productName = product.name;
        target.spec = source.spec == null || source.spec.isBlank() ? product.spec : source.spec.trim();
        target.unit = source.unit == null || source.unit.isBlank() ? product.unit : source.unit.trim();
        target.qty = requestedQty;
        target.shippedQty = shippedQty;
        target.unitPrice = MoneyUtil.nvl(source.unitPrice);
        target.taxRate = source.taxRate;
        target.amount = MoneyUtil.money(requestedQty.multiply(target.unitPrice));
        target.dueDate = source.dueDate == null ? fallbackDueDate : source.dueDate;
        if (target.dueDate == null) throw new BusinessException("订单产品明细请填写计划交货日期");
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder saveSimpleOrder(SimpleOrderRequest request) {
        SalesOrder order = new SalesOrder();
        order.orderDate = request.orderDate == null ? LocalDate.now() : request.orderDate;
        order.orderNo = resolveNewOrderNo(request.orderNo, order.orderDate);
        order.sourceOrderNo = trim(request.sourceOrderNo);
        order.customerId = request.customerId;
        order.customerName = request.customerName;
        order.customerAddress = request.customerAddress;
        order.customerPhone = request.customerPhone;
        order.customerEmail = request.customerEmail;
        order.contactPerson = request.contactPerson;
        order.auditor = request.auditor;
        order.deliveryDate = request.deliveryDate;
        order.status = DELIVERY_STATUS_UNSHIPPED;
        order.totalAmount = MoneyUtil.money(MoneyUtil.nvl(request.amount));
        order.remark = trim(request.remark);
        SalesOrder saved = salesOrderRepository.save(order);

        SalesOrderItem item = new SalesOrderItem();
        item.orderId = saved.id;
        item.productId = request.productId;
        item.productCode = request.productCode;
        item.productName = request.productName;
        item.unit = request.unit;
        item.qty = MoneyUtil.nvl(request.qty);
        item.shippedQty = BigDecimal.ZERO;
        item.unitPrice = request.unitPrice;
        item.amount = MoneyUtil.money(request.amount == null
                ? MoneyUtil.nvl(request.qty).multiply(MoneyUtil.nvl(request.unitPrice))
                : request.amount);
        item.dueDate = request.deliveryDate;
        salesOrderItemRepository.save(item);
        saved.totalAmount = item.amount;
        SalesOrder result = salesOrderRepository.save(saved);
        productionService.syncPlansForOrder(result);
        return result;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder updateSimpleOrder(Long orderId, SimpleOrderRequest request) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .orElseThrow(() -> new BusinessException("订单不存在"));
        SalesOrderItem item = resolveOrderItem(order.id, request.itemId);
        if (request.orderNo != null && !request.orderNo.isBlank()) {
            String orderNo = request.orderNo.trim();
            if (documentNumberService.orderExistsExcept(orderNo, order.id)) {
                throw new BusinessException("订单号已存在，请更换订单号");
            }
            order.orderNo = orderNo;
        }
        order.sourceOrderNo = trim(request.sourceOrderNo);
        order.customerId = request.customerId;
        order.customerName = request.customerName;
        order.customerAddress = request.customerAddress;
        order.customerPhone = request.customerPhone;
        order.customerEmail = request.customerEmail;
        order.contactPerson = request.contactPerson;
        order.auditor = request.auditor;
        order.orderDate = request.orderDate == null ? order.orderDate : request.orderDate;
        order.deliveryDate = request.deliveryDate;
        order.remark = trim(request.remark);

        item.productCode = request.productCode;
        item.productName = request.productName;
        item.productId = request.productId == null ? item.productId : request.productId;
        item.unit = request.unit;
        if (item.shippedQty == null) item.shippedQty = BigDecimal.ZERO;
        BigDecimal requestedQty = MoneyUtil.nvl(request.qty);
        if (requestedQty.compareTo(item.shippedQty) < 0) {
            throw new BusinessException("订单数量不能小于已发货数量");
        }
        item.qty = requestedQty;
        item.unitPrice = request.unitPrice;
        item.amount = MoneyUtil.money(request.amount == null
                ? MoneyUtil.nvl(request.qty).multiply(MoneyUtil.nvl(request.unitPrice))
                : request.amount);
        item.dueDate = request.deliveryDate;
        salesOrderItemRepository.save(item);

        order.totalAmount = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id).stream()
                .map(orderItem -> MoneyUtil.nvl(orderItem.amount))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        SalesOrder saved = salesOrderRepository.save(order);
        productionService.syncPlansForOrder(saved);
        updateSimpleOrderStatus(saved);
        return saved;
    }

    private String resolveNewOrderNo(String orderNo, LocalDate orderDate) {
        if (orderNo == null || orderNo.isBlank()) {
            return nextAvailableOrderNo(orderDate == null ? LocalDate.now() : orderDate);
        }
        String trimmed = orderNo.trim();
        if (documentNumberService.orderExists(trimmed)) {
            throw new BusinessException("订单号已存在，请更换订单号");
        }
        return trimmed;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public void deleteSalesOrder(Long orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException("订单不存在"));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderId(order.id);
        boolean hasDelivery = orderItems.stream().filter(item -> item.id != null)
                .anyMatch(item -> !deliveryNoteItemRepository.findByOrderItemIdAndDeletedFalse(item.id).isEmpty());
        if (hasDelivery) throw new BusinessException("订单已有发货记录，请先删除相关发货记录");
        productionService.deletePlansForOrder(order.id);
        for (SalesOrderItem item : orderItems) {
            salesOrderItemRepository.delete(item);
        }
        salesOrderRepository.delete(order);
    }

    public List<Map<String, Object>> simpleOrders() {
        List<SalesOrder> orders = activeSalesOrdersLatestFirst();
        Map<Long, List<SalesOrderItem>> itemsByOrder = activeItemsByOrder(orders);
        Map<Long, LocalDate> lastDeliveryDates = lastDeliveryDatesByOrderItem(
                itemsByOrder.values().stream().flatMap(Collection::stream).toList());
        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesOrder order : orders) {
            List<SalesOrderItem> items = itemsByOrder.getOrDefault(order.id, List.of());
            result.add(aggregateOrderRow(order, items, lastDeliveryDates));
        }
        return result;
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "salesOrderPages", sync = true)
    public PageResponse<Map<String, Object>> simpleOrdersPage(int page, int size, String keyword,
                                                              String deliveryStatus, Integer year) {
        String normalizedKeyword = normalizeSearch(keyword);
        String normalizedStatus = normalizeFilter(deliveryStatus);
        LocalDate startDate = year == null ? null : LocalDate.of(year, 1, 1);
        LocalDate endDate = startDate == null ? null : startDate.plusYears(1);
        if (normalizedKeyword.isEmpty() && "all".equals(normalizedStatus)) {
            return defaultOrdersPage(page, size, startDate, endDate);
        }
        PageRequest pageable = PageRequest.of(page, size);
        boolean filterByYear = startDate != null;
        Page<SalesOrder> orders = salesOrderRepository.searchActiveOrders(
                normalizedKeyword, normalizedStatus, filterByYear,
                filterByYear ? startDate : LocalDate.of(1900, 1, 1),
                filterByYear ? endDate : LocalDate.of(2200, 1, 1), pageable);
        Map<Long, List<SalesOrderItem>> itemsByOrder = activeItemsByOrder(orders.getContent());
        List<SalesOrderItem> pageItems = itemsByOrder.values().stream().flatMap(Collection::stream).toList();
        Map<Long, LocalDate> lastDeliveryDates = lastDeliveryDatesByOrderItem(pageItems);
        List<Map<String, Object>> rows = orders.getContent().stream()
                .map(order -> aggregateOrderRow(order, itemsByOrder.getOrDefault(order.id, List.of()), lastDeliveryDates))
                .toList();
        return PageResponse.of(rows, orders);
    }

    private PageResponse<Map<String, Object>> defaultOrdersPage(int page, int size,
                                                                 LocalDate startDate, LocalDate endDate) {
        List<SalesOrder> orders = new ArrayList<>();
        long[] total = {0L};
        boolean filterByYear = startDate != null;
        MapSqlParameterSource pageParameters = new MapSqlParameterSource()
                .addValue("limit", size)
                .addValue("offset", (long) page * size)
                .addValue("filterByYear", filterByYear)
                .addValue("startDate", filterByYear ? startDate : LocalDate.of(1900, 1, 1))
                .addValue("endDate", filterByYear ? endDate : LocalDate.of(2200, 1, 1));
        jdbcTemplate.query(DEFAULT_ORDER_PAGE_SQL,
                pageParameters,
                resultSet -> {
                    SalesOrder order = new SalesOrder();
                    order.id = resultSet.getLong("id");
                    order.orderNo = resultSet.getString("order_no");
                    order.sourceOrderNo = resultSet.getString("source_order_no");
                    order.customerId = resultSet.getObject("customer_id", Long.class);
                    order.customerName = resultSet.getString("customer_name");
                    order.customerAddress = resultSet.getString("customer_address");
                    order.customerPhone = resultSet.getString("customer_phone");
                    order.customerEmail = resultSet.getString("customer_email");
                    order.contactPerson = resultSet.getString("contact_person");
                    order.auditor = resultSet.getString("auditor");
                    order.orderDate = resultSet.getObject("order_date", LocalDate.class);
                    order.status = resultSet.getString("status");
                    order.deliveryDate = resultSet.getObject("delivery_date", LocalDate.class);
                    order.actualDeliveryDate = resultSet.getObject("actual_delivery_date", LocalDate.class);
                    order.remark = resultSet.getString("remark");
                    total[0] = resultSet.getLong("total_count");
                    orders.add(order);
                });
        if (orders.isEmpty()) {
            Long count = jdbcTemplate.queryForObject(
                    "select count(*) from sales_order where deleted = false "
                            + "and (:filterByYear = false or (order_date >= :startDate and order_date < :endDate))",
                    pageParameters, Long.class);
            total[0] = count == null ? 0L : count;
        }

        Map<Long, List<SalesOrderItem>> itemsByOrder = new LinkedHashMap<>();
        Map<Long, LocalDate> lastDeliveryDates = new HashMap<>();
        List<Long> orderIds = orders.stream().map(order -> order.id).toList();
        if (!orderIds.isEmpty()) {
            jdbcTemplate.query(DEFAULT_ORDER_ITEMS_SQL,
                    new MapSqlParameterSource("orderIds", orderIds), resultSet -> {
                        SalesOrderItem item = new SalesOrderItem();
                        item.id = resultSet.getLong("id");
                        item.orderId = resultSet.getLong("order_id");
                        item.productId = resultSet.getObject("product_id", Long.class);
                        item.productCode = resultSet.getString("product_code");
                        item.productName = resultSet.getString("product_name");
                        item.spec = resultSet.getString("spec");
                        item.unit = resultSet.getString("unit");
                        item.qty = resultSet.getBigDecimal("qty");
                        item.shippedQty = resultSet.getBigDecimal("shipped_qty");
                        item.unitPrice = resultSet.getBigDecimal("unit_price");
                        item.amount = resultSet.getBigDecimal("amount");
                        item.dueDate = resultSet.getObject("due_date", LocalDate.class);
                        LocalDate lastDeliveryDate = resultSet.getObject("last_delivery_date", LocalDate.class);
                        if (lastDeliveryDate != null) lastDeliveryDates.put(item.id, lastDeliveryDate);
                        itemsByOrder.computeIfAbsent(item.orderId, ignored -> new ArrayList<>()).add(item);
                    });
        }
        List<Map<String, Object>> rows = orders.stream()
                .map(order -> aggregateOrderRow(
                        order, itemsByOrder.getOrDefault(order.id, List.of()), lastDeliveryDates))
                .toList();
        int totalPages = size == 0 ? 0 : (int) ((total[0] + size - 1) / size);
        return new PageResponse<>(rows, total[0], page, size, totalPages);
    }

    public Map<String, Object> simpleOrderRow(Long orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .filter(existing -> !Boolean.TRUE.equals(existing.deleted))
                .orElseThrow(() -> new BusinessException("订单不存在"));
        List<SalesOrderItem> items = activeItemsLatestFirst(order.id);
        return aggregateOrderRow(order, items, lastDeliveryDatesByOrderItem(items));
    }

    private Map<String, Object> aggregateOrderRow(SalesOrder order, List<SalesOrderItem> items,
                                                   Map<Long, LocalDate> lastDeliveryDates) {
        Map<String, Object> row = baseOrderRow(order);
        row.put("deliveryStatus", deliveryStatus(items));
        row.put("itemCount", items.size());
        row.put("productItems", items.stream().map(item -> productItemRow(item, lastDeliveryDates)).toList());
        row.put("productCodes", joinDistinct(items.stream().map(item -> item.productCode).toList()));
        row.put("productNames", joinDistinct(items.stream().map(item -> item.productName).toList()));
        row.put("units", joinDistinct(items.stream().map(item -> item.unit).toList()));
        row.put("qty", items.stream().map(item -> MoneyUtil.nvl(item.qty)).reduce(BigDecimal.ZERO, BigDecimal::add));
        row.put("amount", MoneyUtil.money(items.stream().map(item -> MoneyUtil.nvl(item.amount)).reduce(BigDecimal.ZERO, BigDecimal::add)));
        List<BigDecimal> prices = items.stream().map(item -> MoneyUtil.nvl(item.unitPrice)).distinct().toList();
        row.put("unitPrice", prices.size() == 1 ? prices.get(0) : null);
        row.put("unitPriceText", prices.size() <= 1 ? (prices.isEmpty() ? "-" : prices.get(0).toPlainString()) : "多种单价");
        List<LocalDate> dueDates = items.stream()
                .map(item -> item.dueDate == null ? order.deliveryDate : item.dueDate)
                .filter(Objects::nonNull).distinct().sorted().toList();
        row.put("plannedDeliveryDate", dueDates.size() == 1 ? dueDates.get(0) : null);
        row.put("plannedDeliveryDateText", dueDates.isEmpty() ? "-" : dueDates.stream().map(LocalDate::toString).collect(Collectors.joining("、")));
        return row;
    }

    private String deliveryStatus(List<SalesOrderItem> items) {
        boolean anyShipped = items.stream()
                .anyMatch(item -> MoneyUtil.nvl(item.shippedQty).compareTo(BigDecimal.ZERO) > 0);
        boolean allShipped = !items.isEmpty() && items.stream()
                .allMatch(item -> MoneyUtil.nvl(item.shippedQty).compareTo(MoneyUtil.nvl(item.qty)) >= 0);
        return allShipped ? DELIVERY_STATUS_DELIVERED : anyShipped ? DELIVERY_STATUS_PARTIAL : DELIVERY_STATUS_UNSHIPPED;
    }

    private Map<String, Object> productItemRow(SalesOrderItem item, Map<Long, LocalDate> lastDeliveryDates) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.id);
        row.put("productId", item.productId);
        row.put("productCode", item.productCode);
        row.put("productName", item.productName);
        row.put("spec", item.spec);
        row.put("unit", item.unit);
        row.put("qty", item.qty);
        row.put("shippedQty", item.shippedQty);
        row.put("unitPrice", item.unitPrice);
        row.put("amount", item.amount);
        row.put("dueDate", item.dueDate);
        boolean fullyDelivered = MoneyUtil.nvl(item.qty).compareTo(BigDecimal.ZERO) > 0
                && MoneyUtil.nvl(item.shippedQty).compareTo(MoneyUtil.nvl(item.qty)) >= 0;
        row.put("actualDeliveryDate", fullyDelivered ? lastDeliveryDates.get(item.id) : null);
        return row;
    }

    private Map<Long, LocalDate> lastDeliveryDatesByOrderItem(List<SalesOrderItem> items) {
        List<Long> orderItemIds = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        if (orderItemIds.isEmpty()) return Map.of();
        return deliveryNoteItemRepository.findLastShippedDatesByOrderItemIdIn(orderItemIds).stream()
                .collect(Collectors.toMap(
                        DeliveryNoteItemRepository.OrderItemLastDeliveryDate::getOrderItemId,
                        DeliveryNoteItemRepository.OrderItemLastDeliveryDate::getLastDeliveryDate));
    }

    private String joinDistinct(List<String> values) {
        return values.stream().map(this::trim).filter(value -> value != null && !value.isBlank())
                .distinct().collect(Collectors.joining("、"));
    }

    public List<Map<String, Object>> deliveryInvoiceRows() {
        List<SalesOrder> orders = activeSalesOrdersLatestFirst();
        Map<Long, List<SalesOrderItem>> itemsByOrder = activeItemsByOrder(orders);
        List<SalesOrderItem> allItems = itemsByOrder.values().stream().flatMap(Collection::stream).toList();
        Map<Long, SalesOrder> ordersById = orders.stream().collect(Collectors.toMap(order -> order.id, order -> order));
        return deliveryInvoiceRows(allItems, ordersById, salesReturnService.quantities());
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "deliveryBoardPages", sync = true)
    public PageResponse<Map<String, Object>> deliveryInvoicePage(int page, int size, String keyword,
                                                                 String filterMode, String fulfillmentStatus) {
        String normalizedKeyword = normalizeSearch(keyword);
        String normalizedFulfillmentStatus = normalizeFilter(fulfillmentStatus);
        if (normalizedKeyword.isEmpty() && "all".equals(filterMode)
                && "all".equals(normalizedFulfillmentStatus)) {
            return defaultDeliveryInvoicePage(page, size);
        }
        Page<SalesOrderItem> itemPage = salesOrderItemRepository.searchDeliveryBoard(
                normalizedKeyword, "unshipped".equals(filterMode), "overdue".equals(filterMode),
                normalizedFulfillmentStatus, LocalDate.now(), PageRequest.of(page, size));
        List<SalesOrderItem> items = itemPage.getContent();
        Map<Long, SalesOrder> ordersById = activeOrdersById(items);
        List<Long> itemIds = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        List<Map<String, Object>> rows = deliveryInvoiceRows(
                items, ordersById, salesReturnService.quantities(itemIds));
        return PageResponse.of(rows, itemPage);
    }

    private PageResponse<Map<String, Object>> defaultDeliveryInvoicePage(int page, int size) {
        List<Map<String, Object>> rows = new ArrayList<>();
        long[] total = {0L};
        LocalDate today = LocalDate.now();
        jdbcTemplate.query(DEFAULT_DELIVERY_BOARD_SQL,
                new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size),
                resultSet -> {
                    SalesOrder order = new SalesOrder();
                    order.id = resultSet.getLong("order_id");
                    order.orderNo = resultSet.getString("order_no");
                    order.sourceOrderNo = resultSet.getString("source_order_no");
                    order.customerId = resultSet.getObject("customer_id", Long.class);
                    order.customerName = resultSet.getString("customer_name");
                    order.customerAddress = resultSet.getString("customer_address");
                    order.customerPhone = resultSet.getString("customer_phone");
                    order.customerEmail = resultSet.getString("customer_email");
                    order.contactPerson = resultSet.getString("contact_person");
                    order.auditor = resultSet.getString("auditor");
                    order.orderDate = resultSet.getObject("order_date", LocalDate.class);
                    order.status = resultSet.getString("order_status");
                    order.deliveryDate = resultSet.getObject("order_delivery_date", LocalDate.class);
                    order.actualDeliveryDate = resultSet.getObject("actual_delivery_date", LocalDate.class);
                    order.remark = resultSet.getString("remark");

                    BigDecimal qty = MoneyUtil.nvl(resultSet.getBigDecimal("qty"));
                    BigDecimal shipped = MoneyUtil.nvl(resultSet.getBigDecimal("shipped_qty"));
                    BigDecimal returned = MoneyUtil.nvl(resultSet.getBigDecimal("returned_qty"));
                    LocalDate plannedDeliveryDate = resultSet.getObject("due_date", LocalDate.class);
                    if (plannedDeliveryDate == null) plannedDeliveryDate = order.deliveryDate;
                    Map<String, Object> row = baseOrderRow(order);
                    row.put("itemId", resultSet.getLong("item_id"));
                    row.put("productCode", resultSet.getString("product_code"));
                    row.put("productName", resultSet.getString("product_name"));
                    row.put("qty", qty);
                    row.put("returnedQty", returned);
                    row.put("returnableQty", shipped.subtract(returned).max(BigDecimal.ZERO));
                    row.put("unit", resultSet.getString("unit"));
                    row.put("deliveryDate", plannedDeliveryDate);
                    row.put("plannedDeliveryDate", plannedDeliveryDate);
                    row.put("lastDeliveryDate", resultSet.getObject("last_delivery_date", LocalDate.class));
                    row.put("fulfillmentStatus", fulfillmentStatus(qty, shipped, plannedDeliveryDate, today));
                    row.put("unshippedQty", qty.subtract(shipped).max(BigDecimal.ZERO));
                    row.put("uninvoicedQty", MoneyUtil.nvl(resultSet.getBigDecimal("uninvoiced_qty")));
                    total[0] = resultSet.getLong("total_count");
                    rows.add(row);
                });
        if (rows.isEmpty()) {
            Long count = jdbcTemplate.getJdbcTemplate().queryForObject("""
                    select count(*) from sales_order_item i
                    join sales_order o on o.id = i.order_id
                    where i.deleted = false and o.deleted = false
                    """, Long.class);
            total[0] = count == null ? 0L : count;
        }
        int totalPages = size == 0 ? 0 : (int) ((total[0] + size - 1) / size);
        return new PageResponse<>(rows, total[0], page, size, totalPages);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveryCandidates(Long customerId) {
        return deliveryCandidates(customerId, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveryCandidates(Long customerId, Long deliveryId) {
        if (customerId == null) return List.of();
        Map<Long, SalesOrderItem> itemsById = new LinkedHashMap<>();
        salesOrderItemRepository.findAvailableForCustomer(customerId, PageRequest.of(0, 500)).getContent()
                .forEach(item -> itemsById.put(item.id, item));
        if (deliveryId != null) {
            DeliveryNote delivery = deliveryNoteRepository.findById(deliveryId)
                    .filter(note -> !Boolean.TRUE.equals(note.deleted))
                    .orElseThrow(() -> new BusinessException("发货信息不存在"));
            if (!Objects.equals(delivery.customerId, customerId)) {
                throw new BusinessException("发货信息与客户不匹配");
            }
            List<Long> currentItemIds = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(deliveryId)
                    .stream().map(item -> item.orderItemId).filter(Objects::nonNull).distinct().toList();
            salesOrderItemRepository.findAllById(currentItemIds).stream()
                    .filter(item -> !Boolean.TRUE.equals(item.deleted))
                    .forEach(item -> itemsById.putIfAbsent(item.id, item));
        }
        List<SalesOrderItem> items = new ArrayList<>(itemsById.values());
        Map<Long, SalesOrder> ordersById = activeOrdersById(items);
        List<Long> itemIds = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        return deliveryInvoiceRows(items, ordersById, salesReturnService.quantities(itemIds));
    }

    private Map<Long, SalesOrder> activeOrdersById(List<SalesOrderItem> items) {
        List<Long> orderIds = items.stream().map(item -> item.orderId).filter(Objects::nonNull).distinct().toList();
        if (orderIds.isEmpty()) return Map.of();
        return salesOrderRepository.findAllById(orderIds).stream()
                .filter(order -> !Boolean.TRUE.equals(order.deleted))
                .collect(Collectors.toMap(order -> order.id, order -> order));
    }

    private List<Map<String, Object>> deliveryInvoiceRows(List<SalesOrderItem> allItems,
                                                           Map<Long, SalesOrder> ordersById,
                                                           Map<Long, BigDecimal> returnedQuantities) {
        List<Long> orderItemIds = allItems.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        List<DeliveryNoteItem> deliveryItems = orderItemIds.isEmpty()
                ? List.of()
                : deliveryNoteItemRepository.findByOrderItemIdInAndDeletedFalse(orderItemIds);
        List<Long> deliveryIds = deliveryItems.stream().map(item -> item.deliveryId).filter(Objects::nonNull).distinct().toList();
        Map<Long, DeliveryNote> deliveriesById = deliveryIds.isEmpty()
                ? Map.of()
                : deliveryNoteRepository.findAllById(deliveryIds).stream()
                .filter(note -> !Boolean.TRUE.equals(note.deleted))
                .collect(Collectors.toMap(note -> note.id, note -> note));
        Map<Long, BigDecimal> uninvoicedByOrderItem = new HashMap<>();
        Map<Long, LocalDate> lastDeliveryDateByOrderItem = new HashMap<>();
        for (DeliveryNoteItem deliveryItem : deliveryItems) {
            DeliveryNote note = deliveriesById.get(deliveryItem.deliveryId);
            if (note == null) continue;
            if ("已发货".equals(note.status) && note.deliveryDate != null) {
                lastDeliveryDateByOrderItem.merge(deliveryItem.orderItemId, note.deliveryDate,
                        (current, candidate) -> current.isAfter(candidate) ? current : candidate);
            }
            if (!isStatementInvoiced(note)) {
                uninvoicedByOrderItem.merge(deliveryItem.orderItemId, MoneyUtil.nvl(deliveryItem.qty), BigDecimal::add);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (SalesOrderItem item : allItems) {
            SalesOrder order = ordersById.get(item.orderId);
            if (order == null) continue;
            Map<String, Object> row = baseOrderRow(order);
            BigDecimal qty = MoneyUtil.nvl(item.qty);
            BigDecimal shipped = MoneyUtil.nvl(item.shippedQty);
            BigDecimal returned = returnedQuantities.getOrDefault(item.id, BigDecimal.ZERO);
            BigDecimal uninvoiced = uninvoicedByOrderItem.getOrDefault(item.id, BigDecimal.ZERO);
            row.put("itemId", item.id);
            row.put("productCode", item.productCode);
            row.put("productName", item.productName);
            row.put("qty", qty);
            row.put("returnedQty", returned);
            row.put("returnableQty", shipped.subtract(returned).max(BigDecimal.ZERO));
            row.put("unit", item.unit);
            LocalDate plannedDeliveryDate = item.dueDate == null ? order.deliveryDate : item.dueDate;
            LocalDate lastDeliveryDate = lastDeliveryDateByOrderItem.get(item.id);
            row.put("deliveryDate", plannedDeliveryDate);
            row.put("plannedDeliveryDate", plannedDeliveryDate);
            row.put("lastDeliveryDate", lastDeliveryDate);
            row.put("fulfillmentStatus", fulfillmentStatus(qty, shipped, plannedDeliveryDate, LocalDate.now()));
            row.put("unshippedQty", qty.subtract(shipped).max(BigDecimal.ZERO));
            row.put("uninvoicedQty", uninvoiced);
            result.add(row);
        }
        return result;
    }

    private String normalizeSearch(String value) {
        String normalized = trim(value);
        return normalized == null ? "" : normalized;
    }

    private String normalizeFilter(String value) {
        String normalized = trim(value);
        return normalized == null || normalized.isBlank() ? "all" : normalized;
    }

    public Map<String, Object> deliveryInvoiceDetail(Long orderId) {
        return deliveryInvoiceDetail(orderId, null);
    }

    public Map<String, Object> deliveryInvoiceDetail(Long orderId, Long orderItemId) {
        SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        List<SalesOrderItem> orderItems = salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId);
        SalesOrderItem selectedItem = orderItems.stream()
                .filter(item -> orderItemId == null || Objects.equals(item.id, orderItemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("订单产品明细不存在"));
        List<DeliveryNoteItem> linkedItems = orderItemId == null
                ? deliveryNoteItemRepository.findByOrderItemIdInAndDeletedFalse(orderItems.stream().map(item -> item.id).toList())
                : deliveryNoteItemRepository.findByOrderItemIdAndDeletedFalse(selectedItem.id);
        Map<Long, DeliveryNoteItem> linkedItemByDelivery = linkedItems.stream()
                .collect(Collectors.toMap(item -> item.deliveryId, item -> item, (first, second) -> first));
        List<DeliveryNote> deliveries = linkedItemByDelivery.isEmpty()
                ? List.of()
                : deliveryNoteRepository.findAllById(linkedItemByDelivery.keySet()).stream()
                .filter(note -> !Boolean.TRUE.equals(note.deleted))
                .sorted(Comparator
                        .comparing((DeliveryNote note) -> note.deliveryDate == null ? LocalDate.MIN : note.deliveryDate).reversed()
                        .thenComparing(note -> note.id == null ? 0L : note.id, Comparator.reverseOrder()))
                .toList();
        List<Map<String, Object>> deliveryRows = deliveries.stream()
                .map(note -> deliveryDetailRow(note, linkedItemByDelivery.get(note.id)))
                .toList();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("items", orderItemId == null ? orderItems : List.of(selectedItem));
        detail.put("deliveries", deliveryRows);
        detail.put("returns", salesReturnService.history(orderId, selectedItem.id));
        detail.put("qualityReports", qualityReportMap(deliveries));
        detail.put("registrations", financeService.collectionRowsForDeliveries(deliveries.stream().map(note -> note.id).toList()));
        if (selectedItem != null) {
            LocalDate lastDeliveryDate = deliveries.stream()
                    .filter(note -> "已发货".equals(note.status))
                    .map(note -> note.deliveryDate).filter(Objects::nonNull)
                    .max(LocalDate::compareTo).orElse(null);
            LocalDate plannedDeliveryDate = selectedItem.dueDate == null ? order.deliveryDate : selectedItem.dueDate;
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("plannedDeliveryDate", plannedDeliveryDate);
            summary.put("lastDeliveryDate", lastDeliveryDate);
            summary.put("fulfillmentStatus", fulfillmentStatus(
                    selectedItem.qty, selectedItem.shippedQty, plannedDeliveryDate, LocalDate.now()));
            summary.put("unshippedQty", MoneyUtil.nvl(selectedItem.qty)
                    .subtract(MoneyUtil.nvl(selectedItem.shippedQty)).max(BigDecimal.ZERO));
            detail.put("summary", summary);
        }
        return detail;
    }

    private Map<String, Object> deliveryDetailRow(DeliveryNote note, DeliveryNoteItem selectedItem) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", note.id);
        row.put("deliveryItemId", selectedItem == null ? null : selectedItem.id);
        row.put("deliveryNo", note.deliveryNo);
        row.put("deliverySeq", note.deliverySeq);
        row.put("orderId", note.orderId);
        row.put("orderNo", note.orderNo);
        row.put("customerId", note.customerId);
        row.put("customerName", note.customerName);
        row.put("status", note.status);
        row.put("deliveryDate", note.deliveryDate);
        row.put("deliveryQty", selectedItem == null ? note.deliveryQty : selectedItem.qty);
        row.put("totalDeliveryQty", note.deliveryQty);
        row.put("deliveryMethod", note.deliveryMethod);
        row.put("logisticsNo", note.logisticsNo);
        row.put("deliveryInfo", note.deliveryInfo);
        row.put("remark", note.remark);
        row.put("totalAmount", note.totalAmount);
        row.put("printPrice", note.printPrice);
        return row;
    }

    static String fulfillmentStatus(BigDecimal qtyValue, BigDecimal shippedValue,
                                    LocalDate plannedDeliveryDate, LocalDate today) {
        BigDecimal qty = MoneyUtil.nvl(qtyValue);
        BigDecimal shipped = MoneyUtil.nvl(shippedValue);
        if (shipped.compareTo(qty) >= 0) return FULFILLMENT_STATUS_DELIVERED;
        boolean overdue = plannedDeliveryDate != null && plannedDeliveryDate.isBefore(today);
        if (shipped.compareTo(BigDecimal.ZERO) <= 0) {
            return overdue ? FULFILLMENT_STATUS_OVERDUE_UNSHIPPED : DELIVERY_STATUS_UNSHIPPED;
        }
        return overdue ? FULFILLMENT_STATUS_OVERDUE_PARTIAL : DELIVERY_STATUS_PARTIAL;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public DeliveryNote addDelivery(Long orderId, DeliveryRegisterRequest request) {
        SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        SalesOrderItem item = resolveOrderItem(order.id, request.itemId);
        BigDecimal qty = MoneyUtil.nvl(request.deliveryQty);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("发货量必须大于 0");
        requireIntegerDeliveryQty(qty);
        BigDecimal remaining = MoneyUtil.nvl(item.qty).subtract(MoneyUtil.nvl(item.shippedQty));
        if (qty.compareTo(remaining) > 0) throw new BusinessException("发货量超过未发货数");

        int nextSeq = deliveryNoteRepository.findByOrderIdAndDeletedFalse(order.id).stream()
                .map(note -> note.deliverySeq == null ? 0 : note.deliverySeq)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        DeliveryNote note = new DeliveryNote();
        note.deliverySeq = nextSeq;
        note.orderId = order.id;
        note.orderNo = order.orderNo;
        note.customerId = order.customerId;
        note.customerName = order.customerName;
        note.status = "已发货";
        note.deliveryDate = request.deliveryDate == null ? LocalDate.now() : request.deliveryDate;
        note.deliveryNo = documentNumberService.nextDelivery(note.deliveryDate, "送货单号生成失败，请手动填写送货单号");
        note.deliveryQty = qty;
        note.deliveryMethod = request.deliveryMethod;
        note.invoiced = false;
        note.printPrice = true;
        note.qrCodeContent = "/delivery/" + note.deliveryNo;
        note.totalAmount = MoneyUtil.money(qty.multiply(MoneyUtil.nvl(item.unitPrice)));
        DeliveryNote saved = deliveryNoteRepository.save(note);

        DeliveryNoteItem deliveryItem = new DeliveryNoteItem();
        deliveryItem.deliveryId = saved.id;
        deliveryItem.orderItemId = item.id;
        deliveryItem.productId = item.productId;
        deliveryItem.productName = item.productName;
        deliveryItem.unit = item.unit;
        deliveryItem.qty = qty;
        deliveryItem.unitPrice = item.unitPrice;
        deliveryItem.amount = saved.totalAmount;
        deliveryNoteItemRepository.save(deliveryItem);

        item.shippedQty = MoneyUtil.nvl(item.shippedQty).add(qty);
        salesOrderItemRepository.save(item);
        updateSimpleOrderStatus(order);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public DeliveryNote addBatchDelivery(BatchDeliveryRequest request) {
        if (request == null || request.items == null || request.items.isEmpty()) {
            throw new BusinessException("请至少选择一个需要发货的订单产品");
        }
        Map<Long, BigDecimal> requestedQtyByItem = new LinkedHashMap<>();
        for (BatchDeliveryLine line : request.items) {
            if (line == null || line.orderItemId == null) throw new BusinessException("发货产品明细不完整");
            BigDecimal qty = MoneyUtil.nvl(line.qty);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("发货数量必须大于 0");
            requireIntegerDeliveryQty(qty);
            if (requestedQtyByItem.putIfAbsent(line.orderItemId, qty) != null) {
                throw new BusinessException("同一订单产品不能在一张发货单中重复选择");
            }
        }
        Map<Long, SalesOrderItem> orderItems = salesOrderItemRepository.findAllById(requestedQtyByItem.keySet()).stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .collect(Collectors.toMap(item -> item.id, item -> item));
        if (orderItems.size() != requestedQtyByItem.size()) throw new BusinessException("部分订单产品不存在");
        Map<Long, SalesOrder> orders = salesOrderRepository.findAllById(orderItems.values().stream()
                        .map(item -> item.orderId).filter(Objects::nonNull).distinct().toList()).stream()
                .filter(order -> !Boolean.TRUE.equals(order.deleted))
                .collect(Collectors.toMap(order -> order.id, order -> order));
        if (orders.size() != orderItems.values().stream().map(item -> item.orderId).distinct().count()) {
            throw new BusinessException("部分销售订单不存在");
        }
        Set<String> customerNames = orders.values().stream()
                .map(order -> trim(order.customerName)).filter(Objects::nonNull)
                .map(value -> value.replaceAll("\\s+", "")).collect(Collectors.toSet());
        if (customerNames.size() != 1) throw new BusinessException("一张发货单只能合并同一客户的订单产品");

        for (Map.Entry<Long, BigDecimal> entry : requestedQtyByItem.entrySet()) {
            SalesOrderItem item = orderItems.get(entry.getKey());
            BigDecimal remaining = MoneyUtil.nvl(item.qty).subtract(MoneyUtil.nvl(item.shippedQty));
            if (entry.getValue().compareTo(remaining) > 0) {
                throw new BusinessException("产品“" + item.productName + "”发货数量超过未发货数");
            }
        }

        SalesOrder firstOrder = requestedQtyByItem.keySet().stream()
                .map(orderItems::get).map(item -> orders.get(item.orderId)).findFirst().orElseThrow();
        LocalDate deliveryDate = request.deliveryDate == null ? LocalDate.now() : request.deliveryDate;
        DeliveryNote note = new DeliveryNote();
        note.deliverySeq = deliveryNoteRepository.findByOrderIdAndDeletedFalse(firstOrder.id).stream()
                .map(existing -> existing.deliverySeq == null ? 0 : existing.deliverySeq)
                .max(Integer::compareTo).orElse(0) + 1;
        note.orderId = firstOrder.id;
        note.orderNo = firstOrder.orderNo;
        note.customerId = firstOrder.customerId;
        note.customerName = firstOrder.customerName;
        note.status = "已发货";
        note.deliveryDate = deliveryDate;
        note.deliveryNo = documentNumberService.nextDelivery(deliveryDate, "送货单号生成失败，请手动填写送货单号");
        note.deliveryMethod = trim(request.deliveryMethod);
        note.logisticsNo = trim(request.logisticsNo);
        note.deliveryInfo = trim(request.deliveryInfo);
        note.remark = trim(request.remark);
        note.invoiced = false;
        note.inventoryPosted = false;
        note.printPrice = request.printPrice == null ? Boolean.TRUE : request.printPrice;
        note.qrCodeContent = "/delivery/" + note.deliveryNo;

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        DeliveryNote saved = deliveryNoteRepository.save(note);
        for (Map.Entry<Long, BigDecimal> entry : requestedQtyByItem.entrySet()) {
            SalesOrderItem orderItem = orderItems.get(entry.getKey());
            BigDecimal qty = entry.getValue();
            DeliveryNoteItem deliveryItem = new DeliveryNoteItem();
            deliveryItem.deliveryId = saved.id;
            deliveryItem.orderItemId = orderItem.id;
            deliveryItem.productId = orderItem.productId;
            deliveryItem.productName = orderItem.productName;
            deliveryItem.spec = orderItem.spec;
            deliveryItem.unit = orderItem.unit;
            deliveryItem.qty = qty;
            deliveryItem.unitPrice = orderItem.unitPrice;
            deliveryItem.amount = MoneyUtil.money(qty.multiply(MoneyUtil.nvl(orderItem.unitPrice)));
            deliveryNoteItemRepository.save(deliveryItem);
            orderItem.shippedQty = MoneyUtil.nvl(orderItem.shippedQty).add(qty);
            salesOrderItemRepository.save(orderItem);
            totalQty = totalQty.add(qty);
            totalAmount = totalAmount.add(deliveryItem.amount);
        }
        saved.deliveryQty = totalQty;
        saved.totalAmount = MoneyUtil.money(totalAmount);
        saved = deliveryNoteRepository.save(saved);
        orders.values().forEach(this::updateSimpleOrderStatus);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public void deleteDeliveryInfo(Long deliveryId) {
        DeliveryNote note = deliveryNoteRepository.findById(deliveryId)
                .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                .orElseThrow(() -> new BusinessException("发货信息不存在"));
        if (deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(note.id).stream()
                .anyMatch(item -> MoneyUtil.nvl(item.historicalReconciledQty).signum() > 0)) {
            throw new BusinessException("发货信息包含历史已对账数量，请先核对原始账页，不能直接删除");
        }
        if (financeService.isDeliveryStatemented(note.id)) throw new BusinessException("发货信息已进入对账单，请先删除对应的对账单");
        List<DeliveryNoteItem> items = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(note.id);
        Set<Long> affectedOrderIds = new HashSet<>();
        for (DeliveryNoteItem deliveryItem : items) {
            SalesOrderItem orderItem = salesOrderItemRepository.findById(deliveryItem.orderItemId)
                    .orElseThrow(() -> new BusinessException("订单明细不存在"));
            affectedOrderIds.add(orderItem.orderId);
            orderItem.shippedQty = MoneyUtil.nvl(orderItem.shippedQty).subtract(MoneyUtil.nvl(deliveryItem.qty));
            if (orderItem.shippedQty.compareTo(BigDecimal.ZERO) < 0) orderItem.shippedQty = BigDecimal.ZERO;
            salesOrderItemRepository.save(orderItem);
        }
        attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse("DELIVERY_QUALITY_REPORT", List.of(note.id))
                .forEach(attachmentRepository::delete);
        List<Long> deliveryItemIds = items.stream()
                .map(item -> item.id).filter(Objects::nonNull).toList();
        if (!deliveryItemIds.isEmpty()) {
            attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse(
                            "DELIVERY_ITEM_QUALITY_REPORT", deliveryItemIds)
                    .forEach(attachmentRepository::delete);
        }
        deliveryNoteItemRepository.deleteAll(items);
        deliveryNoteRepository.delete(note);
        affectedOrderIds.stream().map(id -> salesOrderRepository.findById(id).orElse(null))
                .filter(Objects::nonNull).forEach(this::updateSimpleOrderStatus);
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public DeliveryNote updateDeliveryInfo(Long deliveryId, BatchDeliveryRequest request) {
        DeliveryNote note = deliveryNoteRepository.findById(deliveryId)
                .filter(delivery -> !Boolean.TRUE.equals(delivery.deleted))
                .orElseThrow(() -> new BusinessException("发货信息不存在"));
        if (request == null || request.deliveryDate == null) throw new BusinessException("请选择发货日期");
        if (request.items == null || request.items.isEmpty()) throw new BusinessException("请至少保留一个发货产品");

        Map<Long, BigDecimal> requestedQtyByItem = new LinkedHashMap<>();
        for (BatchDeliveryLine line : request.items) {
            if (line == null || line.orderItemId == null) throw new BusinessException("发货产品明细不完整");
            BigDecimal qty = MoneyUtil.nvl(line.qty);
            if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("发货数量必须大于 0");
            requireIntegerDeliveryQty(qty);
            if (requestedQtyByItem.putIfAbsent(line.orderItemId, qty) != null) {
                throw new BusinessException("同一订单产品不能重复选择");
            }
        }

        List<DeliveryNoteItem> existingItems = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(note.id);
        Map<Long, DeliveryNoteItem> existingByOrderItem = existingItems.stream()
                .collect(Collectors.toMap(item -> item.orderItemId, item -> item,
                        (first, second) -> first, LinkedHashMap::new));
        boolean quantityLocked = Boolean.TRUE.equals(note.inventoryPosted) || financeService.isDeliveryStatemented(note.id);
        if (quantityLocked && !sameDeliveryQuantities(existingByOrderItem, requestedQtyByItem)) {
            throw new BusinessException("发货信息已出库或已进入对账，只能修改日期、物流方式和备注");
        }

        Map<Long, SalesOrderItem> orderItems = salesOrderItemRepository.findAllById(requestedQtyByItem.keySet()).stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .collect(Collectors.toMap(item -> item.id, item -> item));
        if (orderItems.size() != requestedQtyByItem.size()) throw new BusinessException("部分订单产品不存在");
        Set<Long> affectedOrderIds = existingItems.stream()
                .map(item -> salesOrderItemRepository.findById(item.orderItemId).orElse(null))
                .filter(Objects::nonNull).map(item -> item.orderId).collect(Collectors.toSet());
        affectedOrderIds.addAll(orderItems.values().stream().map(item -> item.orderId).collect(Collectors.toSet()));

        Map<Long, SalesOrder> orders = salesOrderRepository.findAllById(affectedOrderIds).stream()
                .filter(order -> !Boolean.TRUE.equals(order.deleted))
                .collect(Collectors.toMap(order -> order.id, order -> order));
        if (orders.size() != affectedOrderIds.size()) throw new BusinessException("部分销售订单不存在");
        Set<Long> customerIds = orders.values().stream().map(order -> order.customerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (customerIds.size() > 1 || (!customerIds.isEmpty() && note.customerId != null && !customerIds.contains(note.customerId))) {
            throw new BusinessException("一张发货单只能包含同一客户的订单产品");
        }

        if (!quantityLocked) {
            Set<Long> allOrderItemIds = new LinkedHashSet<>(existingByOrderItem.keySet());
            allOrderItemIds.addAll(requestedQtyByItem.keySet());
            Map<Long, SalesOrderItem> allOrderItems = salesOrderItemRepository.findAllById(allOrderItemIds).stream()
                    .collect(Collectors.toMap(item -> item.id, item -> item));
            for (Long orderItemId : allOrderItemIds) {
                SalesOrderItem orderItem = allOrderItems.get(orderItemId);
                if (orderItem == null || Boolean.TRUE.equals(orderItem.deleted)) throw new BusinessException("订单明细不存在");
                BigDecimal oldQty = existingByOrderItem.containsKey(orderItemId)
                        ? MoneyUtil.nvl(existingByOrderItem.get(orderItemId).qty) : BigDecimal.ZERO;
                BigDecimal newQty = requestedQtyByItem.getOrDefault(orderItemId, BigDecimal.ZERO);
                BigDecimal revisedShipped = MoneyUtil.nvl(orderItem.shippedQty).subtract(oldQty).add(newQty);
                if (revisedShipped.compareTo(BigDecimal.ZERO) < 0 || revisedShipped.compareTo(MoneyUtil.nvl(orderItem.qty)) > 0) {
                    throw new BusinessException("产品“" + orderItem.productName + "”发货数量超过未发货数");
                }
                orderItem.shippedQty = revisedShipped;
                salesOrderItemRepository.save(orderItem);
            }

            List<DeliveryNoteItem> removed = existingItems.stream()
                    .filter(item -> !requestedQtyByItem.containsKey(item.orderItemId)).toList();
            List<Long> removedIds = removed.stream().map(item -> item.id).filter(Objects::nonNull).toList();
            if (!removedIds.isEmpty()) {
                attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse(
                                "DELIVERY_ITEM_QUALITY_REPORT", removedIds)
                        .forEach(attachmentRepository::delete);
                deliveryNoteItemRepository.deleteAll(removed);
            }
        }

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> entry : requestedQtyByItem.entrySet()) {
            SalesOrderItem orderItem = orderItems.get(entry.getKey());
            DeliveryNoteItem deliveryItem = existingByOrderItem.get(entry.getKey());
            if (deliveryItem == null) deliveryItem = new DeliveryNoteItem();
            deliveryItem.deliveryId = note.id;
            deliveryItem.orderItemId = orderItem.id;
            deliveryItem.productId = orderItem.productId;
            deliveryItem.productName = orderItem.productName;
            deliveryItem.spec = orderItem.spec;
            deliveryItem.unit = orderItem.unit;
            deliveryItem.qty = entry.getValue();
            deliveryItem.unitPrice = orderItem.unitPrice;
            deliveryItem.amount = MoneyUtil.money(entry.getValue().multiply(MoneyUtil.nvl(orderItem.unitPrice)));
            deliveryNoteItemRepository.save(deliveryItem);
            totalQty = totalQty.add(entry.getValue());
            totalAmount = totalAmount.add(deliveryItem.amount);
        }

        SalesOrder primaryOrder = orderItems.values().stream()
                .map(item -> orders.get(item.orderId))
                .filter(order -> Objects.equals(order.id, note.orderId))
                .findFirst()
                .orElseGet(() -> orders.get(orderItems.values().iterator().next().orderId));
        note.orderId = primaryOrder.id;
        note.orderNo = primaryOrder.orderNo;
        note.customerId = primaryOrder.customerId;
        note.customerName = primaryOrder.customerName;
        note.deliveryDate = request.deliveryDate;
        note.deliveryMethod = trim(request.deliveryMethod);
        note.logisticsNo = trim(request.logisticsNo);
        note.deliveryInfo = trim(request.deliveryInfo);
        note.remark = trim(request.remark);
        if (request.printPrice != null) note.printPrice = request.printPrice;
        note.deliveryQty = totalQty;
        note.totalAmount = MoneyUtil.money(totalAmount);
        DeliveryNote saved = deliveryNoteRepository.save(note);
        financeService.syncDeliveryDate(note.id, note.deliveryDate);
        affectedOrderIds.stream().map(orders::get).filter(Objects::nonNull).forEach(this::updateSimpleOrderStatus);
        return saved;
    }

    private boolean sameDeliveryQuantities(Map<Long, DeliveryNoteItem> existing,
                                           Map<Long, BigDecimal> requested) {
        if (!existing.keySet().equals(requested.keySet())) return false;
        return existing.entrySet().stream().allMatch(entry ->
                MoneyUtil.nvl(entry.getValue().qty).compareTo(MoneyUtil.nvl(requested.get(entry.getKey()))) == 0);
    }

    private Map<String, Object> baseOrderRow(SalesOrder order) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", order.id);
        row.put("orderNo", order.orderNo);
        row.put("sourceOrderNo", order.sourceOrderNo);
        row.put("customerName", order.customerName);
        row.put("customerId", order.customerId);
        row.put("customerAddress", order.customerAddress);
        row.put("customerPhone", order.customerPhone);
        row.put("customerEmail", order.customerEmail);
        row.put("contactPerson", order.contactPerson);
        row.put("auditor", order.auditor);
        row.put("orderDate", order.orderDate);
        row.put("status", order.status);
        row.put("plannedDeliveryDate", order.deliveryDate);
        row.put("actualDeliveryDate", order.actualDeliveryDate);
        row.put("remark", order.remark);
        return row;
    }

    private boolean isStatementInvoiced(DeliveryNote note) {
        return Boolean.TRUE.equals(note.invoiced);
    }

    private List<SalesOrder> activeSalesOrdersLatestFirst() {
        return salesOrderRepository.findByDeletedFalseOrderByOrderNoDescIdDesc();
    }

    private List<SalesOrderItem> activeItemsLatestFirst(Long orderId) {
        return salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId).stream()
                .sorted(orderItemComparator())
                .toList();
    }

    private Map<Long, List<SalesOrderItem>> activeItemsByOrder(List<SalesOrder> orders) {
        List<Long> orderIds = orders.stream().map(order -> order.id).filter(Objects::nonNull).toList();
        if (orderIds.isEmpty()) return Map.of();
        return salesOrderItemRepository.findByOrderIdInAndDeletedFalse(orderIds).stream()
                .sorted(orderItemComparator())
                .collect(Collectors.groupingBy(item -> item.orderId, LinkedHashMap::new, Collectors.toList()));
    }

    private Comparator<SalesOrderItem> orderItemComparator() {
        return Comparator
                .comparing((SalesOrderItem item) -> item.createdAt == null ? LocalDateTime.MIN : item.createdAt)
                .reversed()
                .thenComparing(item -> item.id == null ? 0L : item.id, Comparator.reverseOrder());
    }

    private Map<Long, List<Attachment>> qualityReportMap(List<DeliveryNote> deliveries) {
        List<Long> ids = deliveries.stream()
                .map(delivery -> delivery.id)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, List<Attachment>> result = new HashMap<>();
        attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse("DELIVERY_QUALITY_REPORT", ids)
                .forEach(attachment -> result.computeIfAbsent(attachment.businessId, ignored -> new ArrayList<>()).add(attachment));
        List<Long> itemIds = deliveryNoteItemRepository.findByDeliveryIdInAndDeletedFalse(ids).stream()
                .map(item -> item.id).filter(Objects::nonNull).toList();
        if (!itemIds.isEmpty()) {
            attachmentRepository.findByBusinessTypeAndBusinessIdInAndDeletedFalse(
                            "DELIVERY_ITEM_QUALITY_REPORT", itemIds)
                    .forEach(attachment -> result.computeIfAbsent(attachment.businessId, ignored -> new ArrayList<>()).add(attachment));
        }
        return result;
    }

    private void updateSimpleOrderStatus(SalesOrder order) {
        DeliveryProgress progress = deliveryProgress(order.id);
        if (!"已取消".equals(order.status)) order.status = progress.status();
        order.actualDeliveryDate = progress.allShipped() ? latestDeliveryDate(order.id) : null;
        salesOrderRepository.save(order);
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public void backfillOrderDeliveryStates() {
        salesOrderRepository.findAll().stream()
                .filter(order -> !Boolean.TRUE.equals(order.deleted))
                .forEach(this::updateSimpleOrderStatus);
    }

    private DeliveryProgress deliveryProgress(Long orderId) {
        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId);
        boolean anyShipped = items.stream()
                .anyMatch(item -> MoneyUtil.nvl(item.shippedQty).compareTo(BigDecimal.ZERO) > 0);
        boolean allShipped = !items.isEmpty() && items.stream()
                .allMatch(item -> MoneyUtil.nvl(item.shippedQty).compareTo(MoneyUtil.nvl(item.qty)) >= 0);
        String status = allShipped
                ? DELIVERY_STATUS_DELIVERED
                : anyShipped ? DELIVERY_STATUS_PARTIAL : DELIVERY_STATUS_UNSHIPPED;
        return new DeliveryProgress(status, allShipped);
    }

    private String displayDeliveryStatus(SalesOrder order) {
        if (List.of(DELIVERY_STATUS_UNSHIPPED, DELIVERY_STATUS_PARTIAL, DELIVERY_STATUS_DELIVERED)
                .contains(order.status)) {
            return order.status;
        }
        return deliveryProgress(order.id).status();
    }

    private LocalDate latestDeliveryDate(Long orderId) {
        List<Long> orderItemIds = salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId).stream()
                .map(item -> item.id).toList();
        if (orderItemIds.isEmpty()) return null;
        List<Long> deliveryIds = deliveryNoteItemRepository.findByOrderItemIdInAndDeletedFalse(orderItemIds).stream()
                .map(item -> item.deliveryId).filter(Objects::nonNull).distinct().toList();
        if (deliveryIds.isEmpty()) return null;
        return deliveryNoteRepository.findAllById(deliveryIds).stream()
                .filter(note -> !Boolean.TRUE.equals(note.deleted))
                .filter(note -> "已发货".equals(note.status))
                .map(note -> note.deliveryDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    public Map<String, Object> orderDetail(Long id) {
        SalesOrder order = salesOrderRepository.findById(id).orElseThrow(() -> new BusinessException("订单不存在"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        detail.put("items", salesOrderItemRepository.findByOrderIdAndDeletedFalse(id));
        return detail;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public SalesOrder approveOrder(Long orderId) {
        SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (!List.of("草稿", "待审核").contains(order.status)) {
            throw new BusinessException("只有草稿或待审核订单可以审核");
        }

        boolean needProduction = false;
        for (SalesOrderItem item : salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id)) {
            BigDecimal required = MoneyUtil.nvl(item.qty).subtract(MoneyUtil.nvl(item.shippedQty));
            BigDecimal available = inventoryService.productAvailableQty(item.productId);
            if (available.compareTo(required) < 0) {
                BigDecimal shortage = required.subtract(available);
                needProduction = true;
                productionService.createPlanForShortage(order, item.productId, item.productName, shortage);
            }
        }
        order.auditor = CurrentUser.username();
        order.auditTime = LocalDateTime.now();
        order.status = DELIVERY_STATUS_UNSHIPPED;
        SalesOrder saved = salesOrderRepository.save(order);
        notifyService.create("订单已审核", saved.orderNo + " 审核完成，状态：" + saved.status, "订单", "普通", "SALES_ORDER", saved.orderNo);
        return saved;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public DeliveryNote createDeliveryFromOrder(Long orderId, List<DeliveryNoteItem> reqItems, Boolean printPrice) {
        SalesOrder order = salesOrderRepository.findById(orderId).orElseThrow(() -> new BusinessException("订单不存在"));
        if (!List.of(DELIVERY_STATUS_UNSHIPPED, DELIVERY_STATUS_PARTIAL, "待发货").contains(order.status)) {
            throw new BusinessException("订单当前状态不可发货：" + order.status);
        }

        DeliveryNote note = new DeliveryNote();
        note.orderId = order.id;
        note.orderNo = order.orderNo;
        note.customerId = order.customerId;
        note.customerName = order.customerName;
        note.status = "待发货";
        note.deliveryDate = LocalDate.now();
        note.deliveryNo = documentNumberService.nextDelivery(note.deliveryDate, "送货单号生成失败，请手动填写送货单号");
        note.printPrice = printPrice == null ? Boolean.TRUE : printPrice;
        note.qrCodeContent = "/delivery/" + note.deliveryNo;
        DeliveryNote saved = deliveryNoteRepository.save(note);

        BigDecimal total = BigDecimal.ZERO;
        Map<Long, DeliveryNoteItem> requested = new HashMap<>();
        if (reqItems != null) {
            for (DeliveryNoteItem item : reqItems) {
                if (item.orderItemId != null) requested.put(item.orderItemId, item);
            }
        }

        for (SalesOrderItem oi : salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id)) {
            BigDecimal remaining = MoneyUtil.nvl(oi.qty).subtract(MoneyUtil.nvl(oi.shippedQty));
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;

            DeliveryNoteItem item = requested.get(oi.id);
            BigDecimal sendQty = item == null || item.qty == null ? remaining : item.qty;
            if (sendQty.compareTo(BigDecimal.ZERO) <= 0) continue;
            requireIntegerDeliveryQty(sendQty);
            if (sendQty.compareTo(remaining) > 0) throw new BusinessException("发货数量超过未发数量：" + oi.productName);

            DeliveryNoteItem di = new DeliveryNoteItem();
            di.deliveryId = saved.id;
            di.orderItemId = oi.id;
            di.productId = oi.productId;
            di.productName = oi.productName;
            di.spec = oi.spec;
            di.unit = oi.unit;
            di.qty = sendQty;
            di.unitPrice = oi.unitPrice;
            di.amount = MoneyUtil.nvl(sendQty).multiply(MoneyUtil.nvl(oi.unitPrice));
            total = total.add(MoneyUtil.nvl(di.amount));
            deliveryNoteItemRepository.save(di);
        }
        saved.totalAmount = MoneyUtil.money(total);
        return deliveryNoteRepository.save(saved);
    }

    public Map<String, Object> deliveryDetail(Long id) {
        DeliveryNote note = deliveryNoteRepository.findById(id).orElseThrow(() -> new BusinessException("送货单不存在"));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("delivery", note);
        detail.put("items", deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(id));
        detail.put("quantityLocked", Boolean.TRUE.equals(note.inventoryPosted) || financeService.isDeliveryStatemented(note.id));
        return detail;
    }

    @Transactional
    @CacheEvict(cacheNames = {"salesOrderPages", "deliveryBoardPages"}, allEntries = true)
    public DeliveryNote confirmDelivery(Long deliveryId) {
        DeliveryNote note = deliveryNoteRepository.findById(deliveryId).orElseThrow(() -> new BusinessException("送货单不存在"));
        if ("已发货".equals(note.status)) {
            return note;
        }
        Set<Long> affectedOrderIds = new HashSet<>();
        List<DeliveryNoteItem> deliveryItems = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(note.id);
        for (DeliveryNoteItem di : deliveryItems) {
            requireIntegerDeliveryQty(di.qty);
        }
        for (DeliveryNoteItem di : deliveryItems) {
            Product product = productRepository.findById(di.productId).orElseThrow(() -> new BusinessException("产品不存在"));
            inventoryService.productOutbound(product, di.qty, "DELIVERY_NOTE", note.deliveryNo, "销售发货出库");

            SalesOrderItem oi = salesOrderItemRepository.findById(di.orderItemId).orElseThrow(() -> new BusinessException("订单明细不存在"));
            affectedOrderIds.add(oi.orderId);
            BigDecimal remaining = MoneyUtil.nvl(oi.qty).subtract(MoneyUtil.nvl(oi.shippedQty));
            if (di.qty.compareTo(remaining) > 0) {
                throw new BusinessException("发货数量超过订单剩余数量：" + oi.productName);
            }
            oi.shippedQty = MoneyUtil.nvl(oi.shippedQty).add(di.qty);
            salesOrderItemRepository.save(oi);
        }

        note.status = "已发货";
        note.confirmer = CurrentUser.username();
        note.confirmTime = LocalDateTime.now();
        DeliveryNote savedNote = deliveryNoteRepository.save(note);
        affectedOrderIds.stream()
                .map(id -> salesOrderRepository.findById(id).orElseThrow(() -> new BusinessException("订单不存在")))
                .forEach(this::updateSimpleOrderStatus);
        notifyService.create("送货已确认", note.deliveryNo + " 已发货并扣减库存", "送货", "普通", "DELIVERY_NOTE", note.deliveryNo);
        return savedNote;
    }

    public static class SimpleOrderRequest {
        public Long itemId;
        public LocalDate orderDate;
        public Long customerId;
        public String customerName;
        public String customerAddress;
        public String customerPhone;
        public String customerEmail;
        public String contactPerson;
        public String auditor;
        public String orderNo;
        public String sourceOrderNo;
        public String remark;
        public Long productId;
        public String productCode;
        public String productName;
        public String unit;
        public BigDecimal qty;
        public BigDecimal unitPrice;
        public BigDecimal amount;
        public LocalDate deliveryDate;
    }

    public static class DeliveryRegisterRequest {
        public BigDecimal deliveryQty;
        public String deliveryMethod;
        public LocalDate deliveryDate;
        public Long itemId;
    }

    public static class BatchDeliveryRequest {
        public LocalDate deliveryDate;
        public String deliveryMethod;
        public String logisticsNo;
        public String deliveryInfo;
        public String remark;
        public Boolean printPrice;
        public List<BatchDeliveryLine> items;
    }

    public static class BatchDeliveryLine {
        public Long orderItemId;
        public BigDecimal qty;
    }

    public static class QuotationConvertRequest {
        public LocalDate orderDate;
        public String auditor;
        public String orderNo;
        public LocalDate deliveryDate;
    }

    private record DeliveryProgress(String status, boolean allShipped) {
    }

    private SalesOrderItem resolveOrderItem(Long orderId, Long itemId) {
        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(orderId);
        if (items.isEmpty()) throw new BusinessException("订单明细不存在");
        if (itemId == null) return items.get(0);
        return items.stream()
                .filter(item -> Objects.equals(item.id, itemId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("订单明细不存在"));
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private void requireIntegerDeliveryQty(BigDecimal qty) {
        if (qty != null && qty.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) != 0) {
            throw new BusinessException("发货数量必须为整数");
        }
    }
}

package com.company.oms.report;

import com.company.oms.common.ApiResponse;
import com.company.oms.finance.Statement;
import com.company.oms.finance.StatementRepository;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import com.company.oms.masterdata.Product;
import com.company.oms.masterdata.ProductRepository;
import com.company.oms.sales.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/search")
@PreAuthorize("@menuPermissionService.has('customers','products','quotations','orders','deliveries','statements','invoices')")
public class GlobalSearchController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.company.oms.system.MenuPermissionService menuPermissions;
    private final QuotationRepository quotationRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final StatementRepository statementRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;

    public GlobalSearchController(QuotationRepository quotationRepository,
                                  SalesOrderRepository salesOrderRepository,
                                  DeliveryNoteRepository deliveryNoteRepository,
                                  StatementRepository statementRepository,
                                  CustomerRepository customerRepository,
                                  ProductRepository productRepository) {
        this.quotationRepository = quotationRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.statementRepository = statementRepository;
        this.customerRepository = customerRepository;
        this.productRepository = productRepository;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> search(@RequestParam String keyword) {
        String key = keyword == null ? "" : keyword.trim().toLowerCase();
        if (key.isBlank()) return ApiResponse.ok(List.of());

        List<Map<String, Object>> rows = new ArrayList<>();
        quotationRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.quoteNo, item.customerName, item.contactPerson))
                .limit(8)
                .forEach(item -> rows.add(row("报价单", item.quoteNo, item.customerName, "/quotations")));
        salesOrderRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.orderNo, item.customerName, item.contactPerson))
                .limit(8)
                .forEach(item -> rows.add(row("销售订单", item.orderNo, item.customerName, "/orders")));
        deliveryNoteRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.deliveryNo, item.orderNo, item.customerName))
                .limit(8)
                .forEach(item -> rows.add(row("送货单", item.deliveryNo, item.customerName, "/deliveries")));
        statementRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.statementNo, item.customerName, item.month))
                .limit(8)
                .forEach(item -> rows.add(row("对账单", item.statementNo, item.customerName, "/statements")));
        customerRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.name, item.contactPerson, item.phone))
                .limit(8)
                .forEach(item -> rows.add(row("客户", item.name, item.contactPerson, "/customers")));
        productRepository.findAll().stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .filter(item -> containsAny(key, item.code, item.name, item.spec,
                        item.aliases == null ? null : String.join(" ", item.aliases)))
                .limit(8)
                .forEach(item -> rows.add(row("产品", item.code, item.name, "/products")));
        return ApiResponse.ok(rows.stream().filter(row -> menuPermissions.has(((String) row.get("path")).substring(1))).limit(24).toList());
    }

    private boolean containsAny(String key, String... values) {
        return Stream.of(values)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase())
                .anyMatch(value -> value.contains(key));
    }

    private Map<String, Object> row(String type, String title, String subtitle, String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("title", title == null || title.isBlank() ? "-" : title);
        row.put("subtitle", subtitle);
        row.put("path", path);
        row.put("value", type + " " + (title == null ? "" : title) + " " + (subtitle == null ? "" : subtitle));
        return row;
    }
}

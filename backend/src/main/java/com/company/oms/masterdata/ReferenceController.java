package com.company.oms.masterdata;

import com.company.oms.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import java.util.*;
import java.util.stream.Collectors;

/** Read-only choices needed by permitted workflows; does not grant master-data CRUD. */
@RestController
@RequestMapping("/references")
@PreAuthorize("@menuPermissionService.has('customers','products','quotations','orders','deliveries','statements','invoices')")
public class ReferenceController {
    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final ProductService products;
    public ReferenceController(CustomerRepository customers, CustomerContactRepository contacts, ProductService products) {
        this.customers = customers; this.contacts = contacts; this.products = products;
    }
    @GetMapping("/customers") public ApiResponse<List<Customer>> customers() {
        List<Customer> rows = customers.findAll().stream().filter(c -> !Boolean.TRUE.equals(c.deleted)).toList();
        var ids = rows.stream().map(c -> c.id).toList();
        Map<Long, List<CustomerContact>> grouped = ids.isEmpty() ? Map.of()
            : contacts.findByCustomerIdInAndDeletedFalse(ids).stream()
                .sorted(Comparator.comparing((CustomerContact c) -> !Boolean.TRUE.equals(c.primaryContact)).thenComparing(c -> c.id))
                .collect(Collectors.groupingBy(c -> c.customerId));
        rows.forEach(c -> {
            c.contacts = grouped.getOrDefault(c.id, List.of());
            if (c.contacts.isEmpty() && c.contactPerson != null && !c.contactPerson.isBlank()) {
                CustomerContact contact = new CustomerContact(); contact.customerId = c.id; contact.name = c.contactPerson;
                contact.phone = c.phone; contact.email = c.email; contact.enabled = true; contact.primaryContact = true;
                c.contacts = List.of(contact);
            }
            c.contactNames = c.contacts.stream().map(contact -> contact.name).filter(Objects::nonNull).distinct().collect(Collectors.joining("、"));
        });
        return ApiResponse.ok(rows);
    }
    @GetMapping("/products") public ApiResponse<List<Product>> products() { return ApiResponse.ok(products.list()); }
}

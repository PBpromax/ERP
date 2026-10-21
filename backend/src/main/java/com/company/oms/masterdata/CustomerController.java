package com.company.oms.masterdata;

import com.company.oms.common.CrudController;
import com.company.oms.common.ApiResponse;
import com.company.oms.common.BusinessException;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;

import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/master/customers")
@PreAuthorize("@menuPermissionService.has('customers')")
public class CustomerController extends CrudController<Customer> {
    private final BusinessLicenseRecognitionService recognitionService;
    private final CustomerRepository customerRepository;
    private final CustomerContactRepository contactRepository;

    public CustomerController(CustomerRepository repository,
                              BusinessLicenseRecognitionService recognitionService,
                              CustomerContactRepository contactRepository) {
        super(repository);
        this.customerRepository = repository;
        this.recognitionService = recognitionService;
        this.contactRepository = contactRepository;
    }

    @Override
    @GetMapping
    @PreAuthorize("@menuPermissionService.has('customers')")
    @Cacheable(cacheNames = "masterCustomers", key = "@fieldPermissionService.currentRoleCacheKey()")
    public ApiResponse<List<Customer>> list() {
        List<Customer> customers = customerRepository.findAll().stream()
                .filter(e -> e.deleted == null || !e.deleted)
                .collect(Collectors.toList());
        List<Long> customerIds = customers.stream().map(customer -> customer.id).toList();
        Map<Long, List<CustomerContact>> contactsByCustomer = customerIds.isEmpty()
                ? Map.of()
                : contactRepository.findByCustomerIdInAndDeletedFalse(customerIds).stream()
                .sorted(Comparator
                        .comparing((CustomerContact contact) -> !Boolean.TRUE.equals(contact.primaryContact))
                        .thenComparing(contact -> contact.id == null ? Long.MAX_VALUE : contact.id))
                .collect(Collectors.groupingBy(contact -> contact.customerId));
        customers.forEach(customer -> fillContacts(customer, contactsByCustomer.getOrDefault(customer.id, List.of())));
        return ApiResponse.ok(customers);
    }

    @Override
    @GetMapping("/{id}")
    @PreAuthorize("@menuPermissionService.has('customers')")
    public ApiResponse<Customer> get(@PathVariable Long id) {
        Customer customer = customerRepository.findById(id)
                .filter(e -> e.deleted == null || !e.deleted)
                .orElseThrow(() -> new BusinessException("数据不存在"));
        fillContacts(customer);
        return ApiResponse.ok(customer);
    }

    @Override
    @PostMapping
    @Transactional
    @PreAuthorize("@menuPermissionService.has('customers')")
    @CacheEvict(cacheNames = "masterCustomers", allEntries = true)
    public ApiResponse<Customer> create(@RequestBody Customer entity) {
        normalizeInvoiceSettings(entity);
        List<CustomerContact> contacts = entity.contacts;
        entity.id = null;
        Customer saved = customerRepository.save(entity);
        syncContacts(saved, contacts);
        fillContacts(saved);
        return ApiResponse.ok(saved);
    }

    @Override
    @PutMapping("/{id}")
    @Transactional
    @PreAuthorize("@menuPermissionService.has('customers')")
    @CacheEvict(cacheNames = "masterCustomers", allEntries = true)
    public ApiResponse<Customer> update(@PathVariable Long id, @RequestBody Customer entity) {
        Customer existing = customerRepository.findById(id).orElseThrow(() -> new BusinessException("数据不存在"));
        normalizeInvoiceSettings(entity);
        if (!entity.requiresInvoice() && entity.unifiedSocialCreditCode == null
                && Customer.hasCreditCode(existing.unifiedSocialCreditCode)) {
            entity.unifiedSocialCreditCode = existing.unifiedSocialCreditCode.trim();
        }
        List<CustomerContact> contacts = entity.contacts;
        copyEditableFields(entity, existing);
        Customer saved = customerRepository.save(existing);
        syncContacts(saved, contacts);
        fillContacts(saved);
        return ApiResponse.ok(saved);
    }

    private void normalizeInvoiceSettings(Customer customer) {
        boolean invoiceRequired = customer.requiresInvoice();
        String code = customer.unifiedSocialCreditCode;
        code = Customer.hasCreditCode(code) ? code.trim() : null;
        if (invoiceRequired && code == null) {
            throw new BusinessException("选择开票后，请填写统一社会信用代码");
        }
        if (code != null && code.length() > 100) {
            throw new BusinessException("统一社会信用代码不能超过100字");
        }
        customer.invoiceRequired = invoiceRequired;
        // Preserve an existing code when invoicing is switched off; only remove legacy placeholder text.
        customer.unifiedSocialCreditCode = code;
    }

    private void copyEditableFields(Customer source, Customer target) {
        target.name = source.name;
        target.contactPerson = source.contactPerson;
        target.phone = source.phone;
        target.email = source.email;
        target.address = source.address;
        target.legalRepresentative = source.legalRepresentative;
        target.unifiedSocialCreditCode = source.unifiedSocialCreditCode;
        target.invoiceRequired = source.invoiceRequired;
        target.invoiceTitle = source.invoiceTitle;
        target.taxNo = source.taxNo;
        target.publicBankAccount = source.publicBankAccount;
        target.publicBankName = source.publicBankName;
        target.publicBankNo = source.publicBankNo;
    }

    @Override
    @DeleteMapping("/{id}")
    @PreAuthorize("@menuPermissionService.has('customers')")
    @CacheEvict(cacheNames = "masterCustomers", allEntries = true)
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Customer entity = customerRepository.findById(id).orElseThrow(() -> new BusinessException("数据不存在"));
        contactRepository.deleteAll(contactRepository.findByCustomerId(id));
        customerRepository.delete(entity);
        return ApiResponse.ok();
    }

    private void fillContacts(Customer customer) {
        fillContacts(customer, contactRepository.findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(customer.id));
    }

    private void fillContacts(Customer customer, List<CustomerContact> contacts) {
        if (contacts.isEmpty() && customer.contactPerson != null && !customer.contactPerson.isBlank()) {
            CustomerContact legacyContact = new CustomerContact();
            legacyContact.customerId = customer.id;
            legacyContact.name = customer.contactPerson;
            legacyContact.phone = customer.phone;
            legacyContact.email = customer.email;
            legacyContact.primaryContact = true;
            legacyContact.enabled = true;
            customer.contacts = List.of(legacyContact);
        } else {
            customer.contacts = contacts;
        }
        customer.contactNames = customer.contacts.stream()
                .map(contact -> contact.name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .collect(Collectors.joining("、"));
        if (customer.contactNames.isBlank() && customer.contactPerson != null) {
            customer.contactNames = customer.contactPerson;
        }
    }

    private void syncContacts(Customer customer, List<CustomerContact> contacts) {
        if (contacts == null) return;
        contactRepository.deleteAll(contactRepository.findByCustomerId(customer.id));
        boolean primaryAssigned = false;
        for (CustomerContact contact : contacts) {
            if (contact == null || contact.name == null || contact.name.isBlank()) continue;
            contact.id = null;
            contact.customerId = customer.id;
            contact.name = contact.name.trim();
            contact.enabled = contact.enabled == null ? Boolean.TRUE : contact.enabled;
            contact.primaryContact = !primaryAssigned && Boolean.TRUE.equals(contact.primaryContact);
            if (Boolean.TRUE.equals(contact.primaryContact)) primaryAssigned = true;
            contactRepository.save(contact);
        }
        List<CustomerContact> savedContacts = contactRepository.findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(customer.id);
        if (!primaryAssigned && !savedContacts.isEmpty()) {
            CustomerContact first = savedContacts.get(0);
            first.primaryContact = true;
            contactRepository.save(first);
        }
        CustomerContact primary = contactRepository.findByCustomerIdAndDeletedFalseOrderByPrimaryContactDescIdAsc(customer.id).stream().findFirst().orElse(null);
        if (primary != null) {
            customer.contactPerson = primary.name;
            customer.phone = primary.phone;
            customer.email = primary.email;
            customerRepository.save(customer);
        }
    }

    @PostMapping("/recognize-business-license")
    @PreAuthorize("@menuPermissionService.has('customers')")
    public ApiResponse<BusinessLicenseRecognitionService.BusinessLicenseInfo> recognizeBusinessLicense(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(recognitionService.recognize(file));
    }

    @PostMapping("/recognize-invoice-info")
    @PreAuthorize("@menuPermissionService.has('customers')")
    public ApiResponse<BusinessLicenseRecognitionService.InvoiceInfo> recognizeInvoiceInfo(@RequestBody BusinessLicenseRecognitionService.InvoiceTextRequest request) {
        return ApiResponse.ok(recognitionService.recognizeInvoiceInfo(request));
    }
}

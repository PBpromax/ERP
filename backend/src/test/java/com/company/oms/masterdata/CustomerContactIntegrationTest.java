package com.company.oms.masterdata;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerContactIntegrationTest {
    @Autowired CustomerController controller;
    @Autowired CustomerRepository customerRepository;
    @Autowired EntityManager entityManager;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new com.company.oms.security.AccountPrincipal(
                                1L, "test-admin", "test-session", java.util.Set.of("customers")),
                        "ignored",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void contactsAreSavedAndReturnedWhenCustomerIsCreatedAndEdited() {
        Customer customer = new Customer();
        customer.name = "联系人保存测试客户";
        customer.defaultPaymentTerm = "月结30天";
        customer.creditLimit = new BigDecimal("50000.00");
        customer.contacts = List.of(contact("张女士", "13800000001", true));

        Customer created = controller.create(customer).data;
        assertEquals("张女士", created.contactPerson);
        assertEquals("张女士", created.contactNames);
        assertEquals(1, created.contacts.size());

        Customer changes = new Customer();
        changes.name = created.name;
        changes.address = "更新后的地址";
        changes.contacts = List.of(
                contact("张女士", "13800000001", false),
                contact("李先生", "13800000002", true));

        controller.update(created.id, changes);
        entityManager.flush();
        entityManager.clear();

        Customer reloaded = controller.get(created.id).data;
        assertEquals("李先生", reloaded.contactPerson);
        assertEquals("李先生、张女士", reloaded.contactNames);
        assertEquals(2, reloaded.contacts.size());
        assertEquals("更新后的地址", reloaded.address);
        assertEquals("月结30天", reloaded.defaultPaymentTerm);
        assertEquals(0, new BigDecimal("50000.00").compareTo(reloaded.creditLimit));
    }

    @Test
    void deletingTheLastContactDoesNotRecreateLegacyContact() {
        Customer customer = new Customer();
        customer.name = "最后联系人删除测试客户";
        customer.contactPerson = "历史联系人";
        Customer saved = customerRepository.save(customer);

        Customer detail = controller.get(saved.id).data;
        assertEquals(1, detail.contacts.size());

        Customer changes = new Customer();
        changes.name = saved.name;
        changes.contacts = List.of();
        controller.update(saved.id, changes);
        entityManager.flush();
        entityManager.clear();

        Customer afterDelete = controller.get(saved.id).data;
        assertEquals(0, afterDelete.contacts.size());
        assertEquals("", afterDelete.contactNames);
    }

    @Test
    void invoicingRequiresCreditCodeBeforeAnyCustomerIsWritten() {
        for (String code : new String[]{null, "  ", "不开票"}) {
            Customer customer = new Customer();
            customer.name = "开票必填校验";
            customer.invoiceRequired = true;
            customer.unifiedSocialCreditCode = code;
            long count = customerRepository.count();
            var error = assertThrows(com.company.oms.common.BusinessException.class, () -> controller.create(customer));
            assertTrue(error.getMessage().contains("统一社会信用代码"));
            assertEquals(count, customerRepository.count());
        }
    }

    @Test
    void choiceAndCodePersistAndTurningOffInvoicingRetainsTheRealCode() throws Exception {
        // HTTP binding must accept false as well as true despite computed legacy defaults.
        Customer customer = mapper.readValue("{\"name\":\"开票选项测试\",\"invoiceRequired\":true,\"unifiedSocialCreditCode\":\" 91360924MA369GUQ4L \"}", Customer.class);
        Customer saved = controller.create(customer).data;
        Long id = saved.id;
        entityManager.flush(); entityManager.clear();
        Customer detail = controller.get(id).data;
        assertTrue(detail.invoiceRequired);
        assertEquals("91360924MA369GUQ4L", detail.unifiedSocialCreditCode);
        assertTrue(mapper.readTree(mapper.writeValueAsString(detail)).path("invoiceRequired").asBoolean());

        Customer changes = mapper.readValue(mapper.writeValueAsString(detail), Customer.class);
        changes.invoiceRequired = false;
        changes.unifiedSocialCreditCode = null; // HTTP input is detached from the persisted entity.
        controller.update(id, changes);
        entityManager.flush(); entityManager.clear();
        Customer reloaded = controller.get(id).data;
        assertFalse(reloaded.invoiceRequired);
        assertEquals("91360924MA369GUQ4L", reloaded.unifiedSocialCreditCode);
        assertFalse(mapper.readTree(mapper.writeValueAsString(reloaded)).path("invoiceRequired").asBoolean());

        Customer withoutCode = mapper.readValue("{\"name\":\"无需开票客户\",\"invoiceRequired\":false}", Customer.class);
        assertFalse(controller.create(withoutCode).data.invoiceRequired);
    }

    @Test
    void legacyDefaultsAreReturnedWithoutRewritingOriginalRows() throws Exception {
        for (String code : new String[]{"不开票", "91360924MA369GUQ4L", null}) {
            Customer customer = new Customer(); customer.name = "历史开票资料";
            customer.unifiedSocialCreditCode = code;
            Long id = customerRepository.saveAndFlush(customer).id;
            entityManager.clear();
            Customer detail = controller.get(id).data;
            var json = mapper.readTree(mapper.writeValueAsString(detail));
            assertEquals(code != null && !code.equals("不开票"), json.path("invoiceRequired").asBoolean());
            if ("不开票".equals(code)) assertTrue(json.path("unifiedSocialCreditCode").isNull());
            entityManager.flush(); entityManager.clear();
            Customer original = customerRepository.findById(id).orElseThrow();
            assertNull(original.invoiceRequired);
            assertEquals(code, original.unifiedSocialCreditCode);
        }
    }

    @Test
    void updatingAnInvoicedCustomerCannotBypassTheRequiredCode() {
        Customer customer = new Customer(); customer.name = "修改开票校验";
        customer.invoiceRequired = false;
        Long id = controller.create(customer).data.id;
        Customer changes = new Customer(); changes.name = customer.name; changes.invoiceRequired = true;
        changes.unifiedSocialCreditCode = "不开票";
        assertThrows(com.company.oms.common.BusinessException.class, () -> controller.update(id, changes));
        assertFalse(customerRepository.findById(id).orElseThrow().invoiceRequired);
    }

    private CustomerContact contact(String name, String phone, boolean primary) {
        CustomerContact contact = new CustomerContact();
        contact.name = name;
        contact.phone = phone;
        contact.primaryContact = primary;
        contact.enabled = true;
        return contact;
    }
}

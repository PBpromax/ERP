package com.company.oms.sales;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PaginationDefaultsWebTest {
    @Autowired SalesService salesService;
    @Autowired ObjectMapper mapper;

    @Test
    void defaultOrderAndDeliveryReadsUseOneHundredRowsAndCapOversizedPages() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(
            new SalesOrderController(null, salesService, null),
            new DeliveryNoteController(null, salesService, null, null)).build();
        for (String path : new String[]{"/sales/orders/page", "/sales/deliveries/invoice-board/page"}) {
            String body = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(100, mapper.readTree(body).path("data").path("size").asInt());
            body = mvc.perform(get(path).param("size", "10000").param("page", "-1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(100, mapper.readTree(body).path("data").path("size").asInt());
            assertEquals(0, mapper.readTree(body).path("data").path("page").asInt());
        }
    }
}

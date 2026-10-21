package com.company.oms.sales;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sales/order-items")
public class SalesOrderItemController extends CrudController<SalesOrderItem> {
    public SalesOrderItemController(SalesOrderItemRepository repository) {
        super(repository);
    }
}

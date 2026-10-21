package com.company.oms.sales;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sales/quotation-items")
public class QuotationItemController extends CrudController<QuotationItem> {
    public QuotationItemController(QuotationItemRepository repository) {
        super(repository);
    }
}

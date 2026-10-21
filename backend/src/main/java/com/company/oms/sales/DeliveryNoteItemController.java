package com.company.oms.sales;

import com.company.oms.common.CrudController;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sales/delivery-items")
public class DeliveryNoteItemController extends CrudController<DeliveryNoteItem> {
    public DeliveryNoteItemController(DeliveryNoteItemRepository repository) {
        super(repository);
    }
}

package com.company.oms.production;

import com.company.oms.common.CrudController;
import com.company.oms.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/production/semi-finished-stocks")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS','PRODUCTION')")
public class SemiFinishedStockController extends CrudController<SemiFinishedStock> {
    private final ProductionStockService productionStockService;

    public SemiFinishedStockController(SemiFinishedStockRepository repository,
                                       ProductionStockService productionStockService) {
        super(repository);
        this.productionStockService = productionStockService;
    }

    @Override
    @GetMapping
    public ApiResponse<List<SemiFinishedStock>> list() {
        return ApiResponse.ok(productionStockService.listSyncedFromProducts());
    }
}

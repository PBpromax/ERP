package com.company.oms.purchase;

import com.company.oms.common.*;
import com.company.oms.masterdata.Material;
import com.company.oms.masterdata.MaterialRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;

@RestController
@RequestMapping("/purchase/requests")
@PreAuthorize("hasAnyRole('ADMIN','HR','CLERK','LOGISTICS','PRODUCTION')")
public class PurchaseRequestController extends CrudController<PurchaseRequest> {
    private final PurchaseRequestRepository purchaseRequestRepository;
    private final MaterialRepository materialRepository;
    private final DocumentNumberService documentNumberService;

    public PurchaseRequestController(PurchaseRequestRepository repository, MaterialRepository materialRepository, DocumentNumberService documentNumberService) {
        super(repository);
        this.purchaseRequestRepository = repository;
        this.materialRepository = materialRepository;
        this.documentNumberService = documentNumberService;
    }

    @AuditLogAction(module = "采购申请", action = "从预警生成")
    @PostMapping("/from-warning")
    public ApiResponse<PurchaseRequest> fromWarning(@RequestBody PurchaseRequest req) {
        Material material = materialRepository.findById(req.materialId).orElseThrow(() -> new BusinessException("材料不存在"));
        req.id = null;
        req.requestNo = documentNumberService.nextPurchaseRequest(LocalDate.now(), "采购申请单号生成失败，请稍后重试");
        req.materialName = material.name;
        req.defaultSupplier = material.defaultSupplier;
        req.status = "待采购";
        if (req.expectedArrivalDate == null) {
            req.expectedArrivalDate = LocalDate.now().plusDays(5);
        }
        return ApiResponse.ok(purchaseRequestRepository.save(req));
    }
}

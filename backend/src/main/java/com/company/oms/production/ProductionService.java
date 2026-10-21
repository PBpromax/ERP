package com.company.oms.production;

import com.company.oms.common.*;
import com.company.oms.masterdata.ProductRepository;
import com.company.oms.sales.SalesOrder;
import com.company.oms.sales.SalesOrderItem;
import com.company.oms.sales.SalesOrderItemRepository;
import com.company.oms.sales.SalesOrderRepository;
import com.company.oms.sales.SalesService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ProductionService {
    private final ProductionPlanRepository planRepository;
    private final ProductRepository productRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;
    private final ProductionWorkerRepository workerRepository;
    private final WorkerProductionRecordRepository workerRecordRepository;
    private final SemiFinishedStockRepository semiFinishedStockRepository;
    private final ProductionPlanAssignmentRepository assignmentRepository;
    private final WorkerProductionAllocationRepository allocationRepository;
    private final DocumentNumberService documentNumberService;

    public ProductionService(ProductionPlanRepository planRepository,
                             ProductRepository productRepository,
                             SalesOrderRepository salesOrderRepository,
                             SalesOrderItemRepository salesOrderItemRepository,
                             ProductionWorkerRepository workerRepository,
                             WorkerProductionRecordRepository workerRecordRepository,
                             SemiFinishedStockRepository semiFinishedStockRepository,
                             ProductionPlanAssignmentRepository assignmentRepository,
                             WorkerProductionAllocationRepository allocationRepository,
                             DocumentNumberService documentNumberService) {
        this.planRepository = planRepository;
        this.productRepository = productRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
        this.workerRepository = workerRepository;
        this.workerRecordRepository = workerRecordRepository;
        this.semiFinishedStockRepository = semiFinishedStockRepository;
        this.assignmentRepository = assignmentRepository;
        this.allocationRepository = allocationRepository;
        this.documentNumberService = documentNumberService;
    }

    @Transactional
    public ProductionPlan createPlanForShortage(SalesOrder order, Long productId, String productName, BigDecimal shortageQty) {
        ProductionPlan plan = new ProductionPlan();
        plan.planNo = nextPlanNo();
        plan.sourceOrderId = order.id;
        plan.sourceOrderNo = order.orderNo;
        plan.productId = productId;
        plan.productName = productName;
        plan.planQty = shortageQty;
        plan.assignedQty = BigDecimal.ZERO;
        plan.status = "待生产";
        plan.progress = 0;
        plan.expectedFinishDate = LocalDate.now().plusDays(7);
        plan.remark = "订单审核缺货自动生成";
        return planRepository.save(plan);
    }

    @Transactional
    public void syncPlansForOrder(SalesOrder order) {
        if (order == null || order.id == null) return;
        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        Set<Long> activeItemIds = items.stream()
                .map(item -> item.id)
                .collect(Collectors.toSet());
        List<ProductionPlan> oldPlans = planRepository.findBySourceOrderIdAndDeletedFalse(order.id);
        List<ProductionPlan> stalePlans = oldPlans.stream()
                .filter(oldPlan -> oldPlan.orderItemId != null && !activeItemIds.contains(oldPlan.orderItemId))
                .toList();
        if (!stalePlans.isEmpty()) planRepository.deleteAll(stalePlans);
        Map<Long, ProductionPlan> existingByItemId = oldPlans.stream()
                .filter(oldPlan -> oldPlan.orderItemId != null && activeItemIds.contains(oldPlan.orderItemId))
                .collect(Collectors.toMap(oldPlan -> oldPlan.orderItemId, oldPlan -> oldPlan, (first, ignored) -> first));
        List<ProductionPlan> plansToUpdate = new java.util.ArrayList<>();
        for (SalesOrderItem item : items) {
            ProductionPlan existing = existingByItemId.get(item.id);
            if (existing != null) {
                existing.sourceOrderNo = order.orderNo;
                existing.productId = item.productId;
                existing.productCode = item.productCode;
                existing.productName = item.productName;
                existing.productSpec = item.spec;
                existing.planQty = MoneyUtil.nvl(item.qty);
                existing.expectedFinishDate = item.dueDate == null ? order.deliveryDate : item.dueDate;
                plansToUpdate.add(existing);
                continue;
            }
            ProductionPlan plan = new ProductionPlan();
            plan.planNo = nextPlanNo();
            plan.sourceOrderId = order.id;
            plan.sourceOrderNo = order.orderNo;
            plan.orderItemId = item.id;
            plan.productId = item.productId;
            plan.productCode = item.productCode;
            plan.productName = item.productName;
            plan.productSpec = item.spec;
            plan.planQty = MoneyUtil.nvl(item.qty);
            plan.assignedQty = BigDecimal.ZERO;
            plan.status = "未分配";
            plan.progress = 0;
            plan.expectedFinishDate = item.dueDate == null ? order.deliveryDate : item.dueDate;
            plan.remark = "销售订单生成后自动生成";
            // Save new plans immediately so the next document number cannot be reused.
            planRepository.save(plan);
        }
        if (!plansToUpdate.isEmpty()) planRepository.saveAll(plansToUpdate);
    }

    public List<ProductionPlan> activePlans() {
        backfillPlanSpecs();
        migrateLegacyAssignments();
        return planRepository.findAll().stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .peek(plan -> {
                    applyAssignmentSummary(plan);
                    plan.assignmentSummary = assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id).stream()
                            .map(assignment -> assignment.workerName + "：" + stripZeros(assignment.qty))
                            .collect(Collectors.joining("、"));
                    plan.unassignedQty = unassignedQty(plan);
                    plan.completedQty = completedQty(plan);
                    planRepository.save(plan);
                })
                .sorted(Comparator
                        .comparing((ProductionPlan plan) -> plan.createdAt == null ? LocalDateTime.MIN : plan.createdAt)
                        .reversed()
                        .thenComparing(plan -> plan.id == null ? 0L : plan.id, Comparator.reverseOrder()))
                .toList();
    }

    @Transactional
    public void backfillPlanSpecs() {
        planRepository.findAll().stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .filter(plan -> plan.productCode != null && !plan.productCode.isBlank())
                .filter(plan -> plan.productSpec == null || plan.productSpec.isBlank())
                .forEach(plan -> {
                    semiFinishedStockRepository.findByCodeAndDeletedFalse(plan.productCode)
                            .ifPresent(stock -> {
                                plan.productSpec = stock.spec;
                                if (plan.productName == null || plan.productName.isBlank()) plan.productName = stock.name;
                                planRepository.save(plan);
                            });
                });
    }

    @Transactional
    public ProductionPlan savePlan(ProductionPlan plan) {
        if (plan.productCode != null) plan.productCode = plan.productCode.trim();
        if (plan.productName != null) plan.productName = plan.productName.trim();
        if (plan.productSpec != null) plan.productSpec = plan.productSpec.trim();
        if (plan.productId == null && plan.productCode != null && !plan.productCode.isBlank()) {
            productRepository.findByCodeAndDeletedFalse(plan.productCode).ifPresent(product -> {
                plan.productId = product.id;
                if (plan.productName == null || plan.productName.isBlank()) plan.productName = product.name;
                if (plan.productSpec == null || plan.productSpec.isBlank()) plan.productSpec = product.spec;
            });
        }
        if (plan.productId == null && plan.productCode != null && !plan.productCode.isBlank()) {
            semiFinishedStockRepository.findByCodeAndDeletedFalse(plan.productCode).ifPresent(stock -> {
                if (plan.productName == null || plan.productName.isBlank()) plan.productName = stock.name;
                if (plan.productSpec == null || plan.productSpec.isBlank()) plan.productSpec = stock.spec;
                productRepository.findByCodeAndDeletedFalse(stock.code).ifPresent(product -> plan.productId = product.id);
            });
        }
        if (plan.planNo == null || plan.planNo.isBlank()) plan.planNo = nextPlanNo();
        if (plan.status == null || plan.status.isBlank()) plan.status = "未分配";
        if (plan.progress == null) plan.progress = 0;
        if (plan.assignedQty == null) plan.assignedQty = BigDecimal.ZERO;
        if (plan.planQty == null) plan.planQty = BigDecimal.ZERO;
        return planRepository.save(plan);
    }

    @Transactional
    public ProductionPlan assignPlan(Long id, AssignPlanRequest request) {
        ProductionPlan plan = planRepository.findById(id)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("生产计划不存在"));
        ProductionWorker worker = workerRepository.findById(request.workerId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("工人不存在"));
        BigDecimal qty = MoneyUtil.nvl(request.qty);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("生产数量必须大于 0");
        BigDecimal unassignedQty = unassignedQty(plan);
        if (qty.compareTo(unassignedQty) > 0) throw new BusinessException("生产数量不能大于未分配数量");
        ProductionPlanAssignment assignment = assignmentRepository.findByPlanIdAndWorkerIdAndDeletedFalse(plan.id, worker.id)
                .orElseGet(ProductionPlanAssignment::new);
        assignment.planId = plan.id;
        assignment.workerId = worker.id;
        assignment.workerName = worker.name;
        assignment.qty = MoneyUtil.nvl(assignment.qty).add(qty);
        assignmentRepository.save(assignment);
        applyAssignmentSummary(plan);
        plan.progress = plan.progress == null ? 0 : plan.progress;
        return planRepository.save(plan);
    }

    public List<Map<String, Object>> planAssignments(Long planId) {
        ProductionPlan plan = planRepository.findById(planId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("生产计划不存在"));
        return assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id).stream()
                .map(this::assignmentRow)
                .toList();
    }

    @Transactional
    public void deletePlanAssignment(Long assignmentId) {
        ProductionPlanAssignment assignment = assignmentRepository.findById(assignmentId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("分配任务不存在"));
        ProductionPlan plan = planRepository.findById(assignment.planId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElse(null);
        allocationRepository.deleteByAssignmentId(assignment.id);
        assignmentRepository.delete(assignment);
        if (plan != null) {
            applyAssignmentSummary(plan);
            planRepository.save(plan);
        }
    }

    @Transactional
    public void deletePlansForOrder(Long orderId) {
        planRepository.deleteAll(planRepository.findBySourceOrderIdAndDeletedFalse(orderId));
    }

    @Transactional
    @CacheEvict(cacheNames = "salesOrderPages", allEntries = true)
    public ProductionPlan finishPlan(Long id) {
        ProductionPlan plan = planRepository.findById(id).orElseThrow(() -> new BusinessException("生产计划不存在"));
        if ("已完工".equals(plan.status)) {
            return plan;
        }
        plan.status = "已完工";
        plan.progress = 100;
        plan.finishDate = LocalDate.now();
        ProductionPlan saved = planRepository.save(plan);

        SalesOrder order = salesOrderRepository.findById(plan.sourceOrderId).orElse(null);
        if (order != null && !SalesService.DELIVERY_STATUS_DELIVERED.equals(order.status)) {
            order.status = SalesService.DELIVERY_STATUS_UNSHIPPED;
            salesOrderRepository.save(order);
        }
        return saved;
    }

    public List<WorkerProductionRecord> workerRecords(Long workerId) {
        List<WorkerProductionRecord> records = workerRecordRepository.findByWorkerIdAndDeletedFalseOrderByProductionDateDescCreatedAtDesc(workerId);
        applyRecordAllocationSummary(records);
        return records;
    }

    public List<Map<String, Object>> workerTasks(Long workerId) {
        ProductionWorker worker = workerRepository.findById(workerId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("工人不存在"));
        List<ProductionPlanAssignment> assignments = assignmentRepository.findByWorkerIdAndDeletedFalseOrderByCreatedAtDesc(worker.id);
        List<Long> planIds = assignments.stream()
                .map(assignment -> assignment.planId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (planIds.isEmpty()) return List.of();
        Map<Long, ProductionPlan> planById = planRepository.findAllById(planIds).stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .collect(Collectors.toMap(plan -> plan.id, plan -> plan, (first, second) -> first));
        return assignments.stream()
                .filter(assignment -> planById.containsKey(assignment.planId))
                .map(assignment -> workerTaskRow(planById.get(assignment.planId), assignment))
                .sorted(Comparator
                        .comparing((Map<String, Object> row) -> Boolean.TRUE.equals(row.get("completed")))
                        .thenComparing(row -> String.valueOf(row.get("planNo") == null ? "" : row.get("planNo"))))
                .toList();
    }

    @Transactional
    public ProductionWorker saveWorker(ProductionWorker worker) {
        if (worker.accessToken == null || worker.accessToken.isBlank()) {
            worker.accessToken = newAccessToken();
        }
        return workerRepository.save(worker);
    }

    public ProductionWorker workerByToken(String token) {
        return workerRepository.findByAccessTokenAndDeletedFalse(token)
                .orElseThrow(() -> new BusinessException("工人入口不存在或已失效"));
    }

    public List<SemiFinishedStock> activeSemiFinishedStocks() {
        return semiFinishedStockRepository.findAll().stream()
                .filter(stock -> stock.deleted == null || !stock.deleted)
                .toList();
    }

    public List<Map<String, Object>> workerAssignedProducts(Long workerId) {
        ProductionWorker worker = workerRepository.findById(workerId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("工人不存在"));
        List<ProductionPlanAssignment> assignments = assignmentRepository.findByWorkerIdAndDeletedFalseOrderByCreatedAtDesc(worker.id);
        List<Long> planIds = assignments.stream()
                .map(assignment -> assignment.planId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (planIds.isEmpty()) return List.of();
        Map<Long, ProductionPlan> planById = planRepository.findAllById(planIds).stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .filter(plan -> plan.productCode != null && !plan.productCode.isBlank())
                .collect(Collectors.toMap(plan -> plan.id, plan -> plan, (first, second) -> first));
        Map<String, List<String>> aliasesByCode = productRepository.findByDeletedFalseOrderByCreatedAtDescIdDesc().stream()
                .filter(product -> product.code != null && !product.code.isBlank())
                .collect(Collectors.toMap(product -> product.code.trim(), product -> product.aliases, (first, second) -> first));
        return assignments.stream()
                .filter(assignment -> planById.containsKey(assignment.planId))
                .collect(Collectors.toMap(
                        assignment -> assignedProductKey(planById.get(assignment.planId)),
                        assignment -> assignedProductRow(planById.get(assignment.planId), assignment, aliasesByCode),
                        this::mergeAssignedProductRows,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
    }

    @Transactional
    public WorkerProductionRecord addWorkerRecord(Long workerId, ProductionRecordController.RecordRequest request) {
        ProductionWorker worker = workerRepository.findById(workerId)
                .filter(w -> w.deleted == null || !w.deleted)
                .orElseThrow(() -> new BusinessException("工人不存在"));
        SemiFinishedStock stock = resolveSemiFinishedStock(worker.id, request);
        BigDecimal qty = MoneyUtil.nvl(request.qty);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("数量必须大于 0");
        BigDecimal laborPrice = MoneyUtil.nvl(stock.laborPrice);

        WorkerProductionRecord record = new WorkerProductionRecord();
        record.workerId = worker.id;
        record.workerName = worker.name;
        record.productionDate = request.productionDate == null ? LocalDate.now() : request.productionDate;
        record.semiFinishedStockId = stock.id;
        record.semiFinishedCode = stock.code;
        record.qty = qty;
        record.qualifiedQty = BigDecimal.ZERO;
        record.audited = false;
        record.laborPrice = laborPrice;
        record.amount = BigDecimal.ZERO;
        return workerRecordRepository.save(record);
    }

    @Transactional
    public WorkerProductionRecord addWorkerRecordByToken(String token, ProductionRecordController.RecordRequest request) {
        ProductionWorker worker = workerByToken(token);
        return addWorkerRecord(worker.id, request);
    }

    @Transactional
    public void deleteWorkerRecord(Long recordId) {
        WorkerProductionRecord record = workerRecordRepository.findById(recordId)
                .filter(r -> r.deleted == null || !r.deleted)
                .orElseThrow(() -> new BusinessException("生产记录不存在"));
        BigDecimal allocatedQty = allocatedQty(record.id);
        if (allocatedQty.compareTo(BigDecimal.ZERO) > 0) {
            throw new BusinessException("该生产记录已分配，不能删除");
        }
        SemiFinishedStock stock = semiFinishedStockRepository.findById(record.semiFinishedStockId)
                .filter(s -> s.deleted == null || !s.deleted)
                .orElseThrow(() -> new BusinessException("半成品不存在"));
        BigDecimal nextQty = MoneyUtil.nvl(stock.stockQty).subtract(effectiveQualifiedQty(record));
        if (nextQty.compareTo(BigDecimal.ZERO) < 0) nextQty = BigDecimal.ZERO;
        stock.stockQty = nextQty;
        semiFinishedStockRepository.save(stock);
        workerRecordRepository.delete(record);
    }

    @Transactional
    public WorkerProductionRecord auditWorkerRecord(Long recordId, BigDecimal qualifiedQty) {
        WorkerProductionRecord record = workerRecordRepository.findById(recordId)
                .filter(r -> r.deleted == null || !r.deleted)
                .orElseThrow(() -> new BusinessException("生产记录不存在"));
        BigDecimal nextQualifiedQty = MoneyUtil.nvl(qualifiedQty);
        if (nextQualifiedQty.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException("合格数量不能小于 0");
        if (nextQualifiedQty.compareTo(MoneyUtil.nvl(record.qty)) > 0) throw new BusinessException("合格数量不能大于生产数量");
        BigDecimal allocatedQty = allocatedQty(record.id);
        if (nextQualifiedQty.compareTo(allocatedQty) < 0) throw new BusinessException("合格数量不能小于已分配数量");

        SemiFinishedStock stock = semiFinishedStockRepository.findById(record.semiFinishedStockId)
                .filter(s -> s.deleted == null || !s.deleted)
                .orElseThrow(() -> new BusinessException("半成品不存在"));
        BigDecimal previousQualifiedQty = effectiveQualifiedQty(record);
        BigDecimal delta = nextQualifiedQty.subtract(previousQualifiedQty);
        record.qualifiedQty = nextQualifiedQty;
        record.audited = true;
        record.amount = MoneyUtil.nvl(record.laborPrice).multiply(nextQualifiedQty);

        BigDecimal nextStockQty = MoneyUtil.nvl(stock.stockQty).add(delta);
        if (nextStockQty.compareTo(BigDecimal.ZERO) < 0) nextStockQty = BigDecimal.ZERO;
        stock.stockQty = nextStockQty;
        semiFinishedStockRepository.save(stock);
        return workerRecordRepository.save(record);
    }

    public List<Map<String, Object>> allocationCandidates(Long recordId) {
        WorkerProductionRecord record = workerRecordRepository.findById(recordId)
                .filter(r -> r.deleted == null || !r.deleted)
                .orElseThrow(() -> new BusinessException("生产记录不存在"));
        List<ProductionPlanAssignment> assignments = assignmentRepository.findByWorkerIdAndDeletedFalseOrderByCreatedAtDesc(record.workerId);
        List<Long> planIds = assignments.stream()
                .map(assignment -> assignment.planId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (planIds.isEmpty()) return List.of();
        Map<Long, ProductionPlan> planById = planRepository.findAllById(planIds).stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .filter(plan -> Objects.equals(plan.productCode, record.semiFinishedCode))
                .collect(Collectors.toMap(plan -> plan.id, plan -> plan, (first, second) -> first));
        return assignments.stream()
                .filter(assignment -> planById.containsKey(assignment.planId))
                .map(assignment -> allocationCandidateRow(planById.get(assignment.planId), assignment))
                .filter(row -> MoneyUtil.nvl((BigDecimal) row.get("remainingQty")).compareTo(BigDecimal.ZERO) > 0)
                .sorted(Comparator
                        .comparing((Map<String, Object> row) -> String.valueOf(row.get("sourceOrderNo") == null ? "" : row.get("sourceOrderNo")))
                        .thenComparing(row -> String.valueOf(row.get("planNo") == null ? "" : row.get("planNo"))))
                .toList();
    }

    public List<Map<String, Object>> recordAllocations(Long recordId) {
        WorkerProductionRecord record = workerRecordRepository.findById(recordId)
                .filter(r -> r.deleted == null || !r.deleted)
                .orElseThrow(() -> new BusinessException("生产记录不存在"));
        return allocationRepository.findByRecordIdAndDeletedFalseOrderByCreatedAtAsc(record.id).stream()
                .map(this::allocationRow)
                .toList();
    }

    @Transactional
    public WorkerProductionRecord allocateWorkerRecord(Long recordId, ProductionRecordController.AllocationRequest request) {
        WorkerProductionRecord record = workerRecordRepository.findById(recordId)
                .filter(r -> r.deleted == null || !r.deleted)
                .orElseThrow(() -> new BusinessException("生产记录不存在"));
        if (!Boolean.TRUE.equals(record.audited)) throw new BusinessException("请先审核生产记录");
        BigDecimal qty = MoneyUtil.nvl(request.qty);
        if (qty.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("分配数量必须大于 0");
        ProductionPlanAssignment assignment = assignmentRepository.findById(request.assignmentId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("生产任务不存在"));
        if (!Objects.equals(assignment.workerId, record.workerId)) throw new BusinessException("只能分配给该工人的生产任务");
        ProductionPlan plan = planRepository.findById(assignment.planId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("生产计划不存在"));
        if (!Objects.equals(plan.productCode, record.semiFinishedCode)) throw new BusinessException("生产产品与任务产品不一致");
        BigDecimal recordRemainingQty = effectiveQualifiedQty(record).subtract(allocatedQty(record.id));
        if (qty.compareTo(recordRemainingQty) > 0) throw new BusinessException("分配数量不能大于该记录剩余合格数量");
        BigDecimal taskRemainingQty = MoneyUtil.nvl(assignment.qty).subtract(allocatedQtyByAssignment(assignment.id));
        if (qty.compareTo(taskRemainingQty) > 0) throw new BusinessException("分配数量不能大于该任务剩余数量");

        WorkerProductionAllocation allocation = new WorkerProductionAllocation();
        allocation.recordId = record.id;
        allocation.workerId = record.workerId;
        allocation.planId = plan.id;
        allocation.assignmentId = assignment.id;
        allocation.planNo = plan.planNo;
        allocation.sourceOrderNo = plan.sourceOrderNo;
        allocation.productCode = plan.productCode;
        allocation.qty = qty;
        allocationRepository.save(allocation);
        applyRecordAllocationSummary(List.of(record));
        return record;
    }

    private SemiFinishedStock resolveSemiFinishedStock(Long workerId, ProductionRecordController.RecordRequest request) {
        if (request.semiFinishedCode != null && !request.semiFinishedCode.isBlank()) {
            String code = request.semiFinishedCode.trim();
            List<ProductionPlanAssignment> assignments = assignmentRepository.findByWorkerIdAndDeletedFalseOrderByCreatedAtDesc(workerId);
            List<Long> planIds = assignments.stream().map(assignment -> assignment.planId).filter(Objects::nonNull).distinct().toList();
            boolean assigned = !planIds.isEmpty() && planRepository.findAllById(planIds).stream()
                    .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                    .anyMatch(plan -> code.equals(plan.productCode));
            if (!assigned) throw new BusinessException("请选择已分配给该工人的生产任务产品");
            return semiFinishedStockRepository.findByCodeAndDeletedFalse(code)
                    .orElseThrow(() -> new BusinessException("半成品编号不存在"));
        }
        throw new BusinessException("请选择生产产品");
    }

    private Map<String, Object> assignedProductRow(ProductionPlan plan, ProductionPlanAssignment assignment,
                                                    Map<String, List<String>> aliasesByCode) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", plan.productCode);
        row.put("name", plan.productName);
        row.put("spec", plan.productSpec);
        row.put("aliases", aliasesByCode.getOrDefault(plan.productCode, List.of()));
        row.put("planNo", plan.planNo);
        row.put("sourceOrderNo", plan.sourceOrderNo);
        row.put("assignmentId", assignment == null ? null : assignment.id);
        row.put("assignedQty", assignment == null ? BigDecimal.ZERO : assignment.qty);
        row.put("planQty", plan.planQty);
        return row;
    }

    private String assignedProductKey(ProductionPlan plan) {
        return String.join("|",
                plan.productCode == null ? "" : plan.productCode,
                plan.productName == null ? "" : plan.productName,
                plan.productSpec == null ? "" : plan.productSpec);
    }

    private Map<String, Object> mergeAssignedProductRows(Map<String, Object> first, Map<String, Object> second) {
        first.put("assignedQty", MoneyUtil.nvl((BigDecimal) first.get("assignedQty")).add(MoneyUtil.nvl((BigDecimal) second.get("assignedQty"))));
        first.put("planQty", MoneyUtil.nvl((BigDecimal) first.get("planQty")).add(MoneyUtil.nvl((BigDecimal) second.get("planQty"))));
        first.put("planNo", null);
        first.put("sourceOrderNo", null);
        first.put("assignmentId", null);
        return first;
    }

    private Map<String, Object> workerTaskRow(ProductionPlan plan, ProductionPlanAssignment assignment) {
        BigDecimal completedQty = allocatedQtyByAssignment(assignment.id);
        BigDecimal targetQty = MoneyUtil.nvl(assignment.qty).compareTo(BigDecimal.ZERO) > 0
                ? MoneyUtil.nvl(assignment.qty)
                : MoneyUtil.nvl(plan.planQty);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", plan.id);
        row.put("assignmentId", assignment.id);
        row.put("planNo", plan.planNo);
        row.put("sourceOrderNo", plan.sourceOrderNo);
        row.put("productCode", plan.productCode);
        row.put("productName", plan.productName);
        row.put("productSpec", plan.productSpec);
        row.put("assignedQty", plan.assignedQty);
        row.put("planQty", plan.planQty);
        row.put("completedQty", completedQty);
        row.put("targetQty", targetQty);
        row.put("completionText", stripZeros(completedQty) + "/" + stripZeros(targetQty));
        boolean completed = completedQty.compareTo(targetQty) >= 0 && targetQty.compareTo(BigDecimal.ZERO) > 0;
        row.put("completed", completed);
        row.put("status", completed ? "已完成" : plan.status);
        row.put("expectedFinishDate", plan.expectedFinishDate);
        return row;
    }

    private Map<String, Object> allocationCandidateRow(ProductionPlan plan, ProductionPlanAssignment assignment) {
        BigDecimal assignedQty = MoneyUtil.nvl(assignment.qty);
        BigDecimal allocatedQty = allocatedQtyByAssignment(assignment.id);
        BigDecimal remainingQty = assignedQty.subtract(allocatedQty);
        if (remainingQty.compareTo(BigDecimal.ZERO) < 0) remainingQty = BigDecimal.ZERO;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", plan.id);
        row.put("assignmentId", assignment.id);
        row.put("planNo", plan.planNo);
        row.put("sourceOrderNo", plan.sourceOrderNo);
        row.put("productCode", plan.productCode);
        row.put("productName", plan.productName);
        row.put("productSpec", plan.productSpec);
        row.put("assignedQty", assignedQty);
        row.put("allocatedQty", allocatedQty);
        row.put("remainingQty", remainingQty);
        row.put("completionText", stripZeros(allocatedQty) + "/" + stripZeros(assignedQty));
        return row;
    }

    private Map<String, Object> allocationRow(WorkerProductionAllocation allocation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", allocation.id);
        row.put("recordId", allocation.recordId);
        row.put("planId", allocation.planId);
        row.put("assignmentId", allocation.assignmentId);
        row.put("planNo", allocation.planNo);
        row.put("sourceOrderNo", allocation.sourceOrderNo);
        row.put("productCode", allocation.productCode);
        row.put("qty", allocation.qty);
        return row;
    }

    private Map<String, Object> assignmentRow(ProductionPlanAssignment assignment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", assignment.id);
        row.put("workerId", assignment.workerId);
        row.put("workerName", assignment.workerName);
        row.put("qty", assignment.qty);
        return row;
    }

    private void applyAssignmentSummary(ProductionPlan plan) {
        List<ProductionPlanAssignment> assignments = assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id);
        BigDecimal assignedQty = assignments.stream()
                .map(assignment -> MoneyUtil.nvl(assignment.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        plan.assignedQty = assignedQty;
        plan.assignedWorkerId = assignments.size() == 1 ? assignments.get(0).workerId : null;
        plan.assignedWorkerName = assignments.isEmpty()
                ? null
                : assignments.stream().map(assignment -> assignment.workerName).filter(Objects::nonNull).distinct().collect(Collectors.joining("、"));
        if ("已完工".equals(plan.status)) return;
        BigDecimal planQty = MoneyUtil.nvl(plan.planQty);
        if (assignedQty.compareTo(BigDecimal.ZERO) <= 0) {
            plan.status = "未分配";
        } else if (assignedQty.compareTo(planQty) >= 0) {
            plan.status = "已分配";
        } else {
            plan.status = "部分分配";
        }
    }

    private BigDecimal unassignedQty(ProductionPlan plan) {
        BigDecimal assignedQty = assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id).stream()
                .map(assignment -> MoneyUtil.nvl(assignment.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal value = MoneyUtil.nvl(plan.planQty).subtract(assignedQty);
        return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private BigDecimal completedQty(ProductionPlan plan) {
        List<Long> assignmentIds = assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id).stream()
                .map(assignment -> assignment.id)
                .filter(Objects::nonNull)
                .toList();
        if (assignmentIds.isEmpty()) return BigDecimal.ZERO;
        return allocationRepository.findByAssignmentIdInAndDeletedFalse(assignmentIds).stream()
                .map(allocation -> MoneyUtil.nvl(allocation.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void applyRecordAllocationSummary(List<WorkerProductionRecord> records) {
        List<Long> recordIds = records.stream()
                .map(record -> record.id)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, BigDecimal> allocatedByRecord = recordIds.isEmpty()
                ? Map.of()
                : allocationRepository.findByRecordIdInAndDeletedFalse(recordIds).stream()
                        .collect(Collectors.groupingBy(
                                allocation -> allocation.recordId,
                                Collectors.mapping(allocation -> MoneyUtil.nvl(allocation.qty), Collectors.reducing(BigDecimal.ZERO, BigDecimal::add))
                        ));
        for (WorkerProductionRecord record : records) {
            BigDecimal allocatedQty = allocatedByRecord.getOrDefault(record.id, BigDecimal.ZERO);
            BigDecimal qualifiedQty = effectiveQualifiedQty(record);
            record.allocatedQty = allocatedQty;
            record.fullyAllocated = qualifiedQty.compareTo(BigDecimal.ZERO) > 0 && allocatedQty.compareTo(qualifiedQty) >= 0;
        }
    }

    private BigDecimal allocatedQty(Long recordId) {
        if (recordId == null) return BigDecimal.ZERO;
        return allocationRepository.findByRecordIdAndDeletedFalseOrderByCreatedAtAsc(recordId).stream()
                .map(allocation -> MoneyUtil.nvl(allocation.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal allocatedQtyByAssignment(Long assignmentId) {
        if (assignmentId == null) return BigDecimal.ZERO;
        return allocationRepository.findByAssignmentIdAndDeletedFalse(assignmentId).stream()
                .map(allocation -> MoneyUtil.nvl(allocation.qty))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Transactional
    public void migrateLegacyAssignments() {
        planRepository.findAll().stream()
                .filter(plan -> !Boolean.TRUE.equals(plan.deleted))
                .filter(plan -> plan.assignedWorkerId != null)
                .filter(plan -> assignmentRepository.findByPlanIdAndDeletedFalseOrderByCreatedAtAsc(plan.id).isEmpty())
                .forEach(plan -> {
                    ProductionPlanAssignment assignment = new ProductionPlanAssignment();
                    assignment.planId = plan.id;
                    assignment.workerId = plan.assignedWorkerId;
                    assignment.workerName = plan.assignedWorkerName;
                    assignment.qty = MoneyUtil.nvl(plan.assignedQty);
                    if (assignment.qty.compareTo(BigDecimal.ZERO) > 0) assignmentRepository.save(assignment);
                    applyAssignmentSummary(plan);
                    planRepository.save(plan);
                });
    }

    private String stripZeros(BigDecimal value) {
        return MoneyUtil.nvl(value).stripTrailingZeros().toPlainString();
    }

    private BigDecimal effectiveQualifiedQty(WorkerProductionRecord record) {
        if (record.qualifiedQty != null) return MoneyUtil.nvl(record.qualifiedQty);
        return Boolean.TRUE.equals(record.audited) || record.audited == null ? MoneyUtil.nvl(record.qty) : BigDecimal.ZERO;
    }

    private String nextPlanNo() {
        return documentNumberService.nextProductionPlan(LocalDate.now(), "生产计划单号生成失败，请稍后重试");
    }

    private String newAccessToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static class AssignPlanRequest {
        public Long workerId;
        public BigDecimal qty;
    }
}

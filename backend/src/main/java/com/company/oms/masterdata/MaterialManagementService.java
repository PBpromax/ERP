package com.company.oms.masterdata;

import com.company.oms.common.BusinessException;
import com.company.oms.inventory.*;
import com.company.oms.system.CurrentUser;
import com.company.oms.system.MenuPermissionService;
import com.company.oms.system.SysUserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MaterialManagementService {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MAX_QTY = new BigDecimal("999999999999999.999");
    private final MaterialRepository materials;
    private final MaterialStockRepository stocks;
    private final MaterialDailyRecordRepository daily;
    private final InventoryTxnRepository transactions;
    private final MaterialScanEntryRepository scanEntries;
    private final MenuPermissionService permissions;
    private final SysUserRepository users;

    public MaterialManagementService(MaterialRepository materials, MaterialStockRepository stocks,
                                     MaterialDailyRecordRepository daily, InventoryTxnRepository transactions,
                                     MaterialScanEntryRepository scanEntries, MenuPermissionService permissions,
                                     SysUserRepository users) {
        this.materials = materials;
        this.stocks = stocks;
        this.daily = daily;
        this.transactions = transactions;
        this.scanEntries = scanEntries;
        this.permissions = permissions;
        this.users = users;
    }

    public LocalDate today() { return LocalDate.now(ZONE); }

    public record ScanCode(String token) {}
    public record ScanMaterial(String name, String unit) {}
    public record EntryMaterial(Long id, String name, String category, String unit, boolean ready, boolean enabled) {}
    public record ScanResult(Long id, String direction, BigDecimal qty, String unit, LocalDate businessDate,
                             String operator, String operatorUsername) {}
    public record ScanRequest(String requestId, String direction, BigDecimal qty) {}

    @Transactional
    public ScanCode scanCode(Long id) {
        Material m = locked(id);
        if (blank(m.scanToken)) {
            m.scanToken = UUID.randomUUID().toString().replace("-", "");
            materials.saveAndFlush(m);
        }
        return new ScanCode(m.scanToken);
    }

    @PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
    public List<EntryMaterial> entryMaterials() {
        Map<Long, MaterialStock> stockMap = stocks.findAll().stream().filter(s -> !Boolean.TRUE.equals(s.deleted))
                .collect(Collectors.toMap(s -> s.materialId, s -> s));
        return materials.findAll().stream().filter(m -> !Boolean.TRUE.equals(m.deleted))
                .sorted(Comparator.comparing(m -> m.id)).map(m -> {
                    MaterialStock s = stockMap.get(m.id);
                    boolean ready = !blank(m.unit) && m.safetyStock != null && s != null && s.currentQty != null;
                    return new EntryMaterial(m.id, m.name, m.category, m.unit, ready, !Boolean.FALSE.equals(m.enabled));
                }).toList();
    }

    @PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
    public ScanMaterial entryMaterial(Long id) {
        Material m = material(id);
        requireReady(m);
        return new ScanMaterial(m.name, m.unit);
    }

    @PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
    public ScanMaterial scanMaterial(String token) {
        Material m = materials.findByScanTokenAndDeletedFalse(token)
                .orElseThrow(() -> new BusinessException("二维码已失效，请联系管理员获取新二维码"));
        requireReady(m);
        return new ScanMaterial(m.name, m.unit);
    }

    @Transactional
    @PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
    public ScanResult registerScan(String token, ScanRequest request) {
        Material m = materials.lockByScanToken(token)
                .orElseThrow(() -> new BusinessException("二维码已失效，请联系管理员获取新二维码"));
        return registerEntry(m, request);
    }

    @Transactional
    @PreAuthorize("@menuPermissionService.has('material-entry-in', 'material-entry-out')")
    public ScanResult registerMaterial(Long id, ScanRequest request) {
        return registerEntry(locked(id), request);
    }

    private ScanResult registerEntry(Material m, ScanRequest request) {
        var principal = permissions.principal();
        var employee = users.findById(principal.userId())
                .filter(u -> !Boolean.TRUE.equals(u.deleted) && Boolean.TRUE.equals(u.enabled))
                .orElseThrow(() -> new AccessDeniedException("员工账号已停用，请重新登录"));
        String username = employee.username;
        String actor = blank(employee.realName) ? username : employee.realName;
        if (request == null || request.requestId() == null || !request.requestId().matches("[a-fA-F0-9-]{36}"))
            throw new BusinessException("登记标识无效，请重新打开登记页面");
        if (!Set.of("IN", "OUT").contains(request.direction() == null ? "" : request.direction()))
            throw new BusinessException("请选择入库或出库");
        if ("IN".equals(request.direction()) && !permissions.has("material-entry-in"))
            throw new AccessDeniedException("当前账号没有原料入库登记权限");
        if ("OUT".equals(request.direction()) && !permissions.has("material-entry-out"))
            throw new AccessDeniedException("当前账号没有原料出库登记权限");
        quantity(request.qty(), "数量");
        if (request.qty().signum() <= 0) throw new BusinessException("数量必须大于0");
        MaterialScanEntry previous = scanEntries.findByMaterialIdAndRequestId(m.id, request.requestId()).orElse(null);
        if (previous != null) {
            if (!Objects.equals(previous.operatorUserId, employee.id))
                throw new BusinessException("该登记标识不属于当前员工，请重新登记");
            if (!previous.direction.equals(request.direction()) || !equal(previous.qty, request.qty()))
                throw new BusinessException("该登记已提交，请先确认登记结果");
            return new ScanResult(previous.id, previous.direction, previous.qty, m.unit, previous.businessDate,
                    previous.operatorName, previous.createdBy);
        }
        MaterialStock s = requireReady(m);
        BigDecimal delta = "IN".equals(request.direction()) ? request.qty() : request.qty().negate();
        BigDecimal before = s.currentQty, after = before.add(delta);
        if (after.compareTo(nvl(s.lockedQty)) < 0) throw new BusinessException("可用库存不足，请核对出库数量");
        quantity(after, "变动后库存");
        LocalDate date = today();
        MaterialDailyRecord r = daily.findByMaterialIdAndBusinessDate(m.id, date).orElse(null);
        if (r == null) {
            r = new MaterialDailyRecord(); r.materialId = m.id; r.businessDate = date;
            r.purchaseQty = BigDecimal.ZERO; r.productionQty = BigDecimal.ZERO; r.lossQty = BigDecimal.ZERO;
            r.createdBy = username;
        }
        if (delta.signum() > 0) r.purchaseQty = r.purchaseQty.add(request.qty());
        else r.productionQty = r.productionQty.add(request.qty());
        quantity(r.purchaseQty, "当日入库量"); quantity(r.productionQty, "当日出库量");
        r.updatedBy = actor.equals(username) ? username : actor + "（" + username + "）"; daily.saveAndFlush(r);
        s.currentQty = after; s.updatedBy = username; stocks.saveAndFlush(s);
        InventoryTxn txn = movement(m, s, delta.signum() > 0 ? "PURCHASE" : "PRODUCTION", delta, before, r.id, date,
                "原料" + (delta.signum() > 0 ? "入库" : "出库") + "登记", false, actor);
        txn.operatorUserId = employee.id; txn.operatorUsername = username; txn.createdBy = username;
        MaterialScanEntry entry = new MaterialScanEntry();
        entry.materialId = m.id; entry.requestId = request.requestId(); entry.direction = request.direction();
        entry.qty = request.qty(); entry.afterQty = after; entry.businessDate = date;
        entry.operatorUserId = employee.id; entry.operatorName = actor;
        entry.createdBy = username; entry.updatedBy = username; scanEntries.saveAndFlush(entry);
        return new ScanResult(entry.id, entry.direction, entry.qty, m.unit, date, actor, username);
    }

    public record MaterialView(Long id, String name, String category, String unit,
            BigDecimal safetyStock, String remark, Boolean enabled, Long versionNo,
            BigDecimal currentQty, Long stockVersion, boolean ready, boolean unitLocked,
            MaterialDailyRecord today, boolean warning) {}
    public record PageResult<T>(List<T> items, long total, int page, int size, int totalPages) {}
    public record TrendPoint(LocalDate date, BigDecimal productionQty, BigDecimal lossQty, BigDecimal totalQty) {}
    public static class MaterialRequest {
        public String name, category, unit, remark;
        public BigDecimal safetyStock, initialQty;
        public Boolean enabled;
        public Long versionNo, stockVersion;
    }
    public static class DailyRequest {
        public BigDecimal purchaseQty, productionQty, lossQty;
        public String remark;
        public Long versionNo, stockVersion;
    }
    public static class AdjustmentRequest {
        public BigDecimal targetQty;
        public String reason;
        public Long stockVersion;
    }

    public List<MaterialView> list() {
        Map<Long, MaterialStock> stockMap = stocks.findAll().stream().filter(s -> !Boolean.TRUE.equals(s.deleted))
                .collect(Collectors.toMap(s -> s.materialId, s -> s));
        Map<Long, MaterialDailyRecord> todayMap = daily.findByBusinessDate(today()).stream()
                .collect(Collectors.toMap(r -> r.materialId, r -> r));
        return materials.findAll().stream().filter(m -> !Boolean.TRUE.equals(m.deleted))
                .sorted(Comparator.comparing(m -> m.id))
                .map(m -> view(m, stockMap.get(m.id), todayMap.get(m.id))).toList();
    }

    public MaterialView get(Long id) {
        Material m = material(id);
        return view(m, stock(id), daily.findByMaterialIdAndBusinessDate(id, today()).orElse(null));
    }

    private MaterialView view(Material m, MaterialStock s, MaterialDailyRecord r) {
        boolean ready = !blank(m.unit) && m.safetyStock != null && s != null && s.currentQty != null;
        return new MaterialView(m.id, m.name, m.category, m.unit, m.safetyStock, m.remark,
                !Boolean.FALSE.equals(m.enabled), m.versionNo, s == null ? null : s.currentQty,
                s == null ? null : s.versionNo, ready,
                transactions.existsByItemTypeAndItemId("MATERIAL", m.id) || daily.existsByMaterialId(m.id)
                        || (s != null && nvl(s.currentQty).signum() != 0),
                r, ready && !Boolean.FALSE.equals(m.enabled) && s.currentQty.compareTo(m.safetyStock) <= 0);
    }

    @Transactional
    public MaterialView create(MaterialRequest request) {
        Material m = new Material();
        applyFields(m, request);
        quantity(request.initialQty, "初始数量");
        m.createdBy = CurrentUser.username();
        materials.saveAndFlush(m);
        initializeStock(m, request.initialQty);
        return get(m.id);
    }

    @Transactional
    public MaterialView update(Long id, MaterialRequest request) {
        Material m = locked(id);
        version(m.versionNo, request.versionNo);
        MaterialStock s = stock(id);
        if (s == null) quantity(request.initialQty, "初始数量");
        else if (request.initialQty != null) throw new BusinessException("库存已建立，请通过库存调整修改数量");
        if (s != null) version(s.versionNo, request.stockVersion);
        applyFields(m, request);
        m.updatedBy = CurrentUser.username();
        materials.saveAndFlush(m);
        if (s == null) initializeStock(m, request.initialQty);
        else {
            s.materialCode = m.code;
            s.materialName = m.name;
            s.safetyStock = m.safetyStock;
            stocks.saveAndFlush(s);
        }
        return get(id);
    }

    @Transactional
    public void delete(Long id) {
        Material m = locked(id);
        MaterialStock s = stock(id);
        if (transactions.existsByItemTypeAndItemId("MATERIAL", id) || daily.existsByMaterialId(id)
                || (s != null && (nvl(s.currentQty).signum() != 0 || nvl(s.lockedQty).signum() != 0))) {
            throw new BusinessException("该材料已有数量记录，请编辑资料将其停用，历史记录会继续保留");
        }
        m.deleted = true;
        if (s != null) { s.deleted = true; stocks.save(s); }
        materials.save(m);
    }

    private void applyFields(Material m, MaterialRequest r) {
        String name = required(r.name, "材料名称", 200);
        String category = required(r.category, "分类", 30);
        String unit = required(r.unit, "单位", 30);
        String remark = optional(r.remark, "备注", 1000);
        quantity(r.safetyStock, "预警值");
        boolean duplicate = materials.findAll().stream().anyMatch(other -> !Boolean.TRUE.equals(other.deleted)
                && !Objects.equals(other.id, m.id)
                && name.equals(other.name) && category.equals(other.category));
        if (duplicate) throw new BusinessException("同分类下的材料名称已存在");
        m.name = name; m.category = category; m.unit = unit;
        m.safetyStock = r.safetyStock; m.remark = remark; m.enabled = !Boolean.FALSE.equals(r.enabled);
    }

    private void initializeStock(Material m, BigDecimal initialQty) {
        MaterialStock s = new MaterialStock();
        s.materialId = m.id; s.materialCode = m.code; s.materialName = m.name;
        s.currentQty = initialQty; s.lockedQty = BigDecimal.ZERO; s.safetyStock = m.safetyStock;
        s.stockAmount = BigDecimal.ZERO;
        stocks.saveAndFlush(s);
        // Even a zero opening balance establishes the unit used for the inventory ledger.
        movement(m, s, "INITIAL", initialQty, BigDecimal.ZERO, null, today(), "初始数量", false);
    }

    public MaterialDailyRecord dailyRecord(Long id, LocalDate date) {
        material(id);
        return daily.findByMaterialIdAndBusinessDate(id, date).orElse(null);
    }

    @Transactional
    public MaterialView saveDaily(Long id, LocalDate date, DailyRequest request) {
        Material m = locked(id);
        MaterialStock s = requireReady(m);
        if (date == null || date.isAfter(today())) throw new BusinessException("登记日期不能晚于今天");
        quantity(request.purchaseQty, "采购量");
        quantity(request.productionQty, "生产使用量");
        quantity(request.lossQty, "损耗量");
        String remark = optional(request.remark, "备注", 1000);
        MaterialDailyRecord r = daily.findByMaterialIdAndBusinessDate(id, date).orElse(null);
        if (r != null && equal(r.purchaseQty, request.purchaseQty) && equal(r.productionQty, request.productionQty)
                && equal(r.lossQty, request.lossQty) && Objects.equals(r.remark, remark)) return get(id);
        version(s.versionNo, request.stockVersion);
        version(r == null ? -1L : r.versionNo, request.versionNo);
        boolean correction = r != null;
        BigDecimal purchaseDelta = request.purchaseQty.subtract(r == null ? BigDecimal.ZERO : r.purchaseQty);
        BigDecimal productionDelta = (r == null ? BigDecimal.ZERO : r.productionQty).subtract(request.productionQty);
        BigDecimal lossDelta = (r == null ? BigDecimal.ZERO : r.lossQty).subtract(request.lossQty);
        BigDecimal after = s.currentQty.add(purchaseDelta).add(productionDelta).add(lossDelta);
        quantity(after, "变动后库存");
        if (after.compareTo(nvl(s.lockedQty)) < 0) throw new BusinessException("消耗后库存不能低于锁定库存，请检查数量");
        if (r == null) {
            r = new MaterialDailyRecord(); r.materialId = id; r.businessDate = date;
            r.createdBy = CurrentUser.username();
        }
        r.purchaseQty = request.purchaseQty; r.productionQty = request.productionQty;
        r.lossQty = request.lossQty; r.remark = remark; r.updatedBy = CurrentUser.username();
        daily.saveAndFlush(r);
        // Credits precede debits so corrections never create an artificial negative intermediate balance.
        List<Map.Entry<String, BigDecimal>> deltas = new ArrayList<>(List.of(
                Map.entry("PURCHASE", purchaseDelta), Map.entry("PRODUCTION", productionDelta), Map.entry("LOSS", lossDelta)));
        deltas.sort(Comparator.comparingInt(e -> -e.getValue().signum()));
        for (var delta : deltas) {
            if (delta.getValue().signum() == 0) continue;
            BigDecimal before = s.currentQty;
            s.currentQty = before.add(delta.getValue());
            movement(m, s, delta.getKey(), delta.getValue(), before, r.id, date, remark, correction);
        }
        // A zero day or remark-only correction also needs a durable audit trail.
        if (deltas.stream().allMatch(e -> e.getValue().signum() == 0)) {
            movement(m, s, "DAILY_NOTE", BigDecimal.ZERO, s.currentQty, r.id, date, remark, correction);
        }
        s.updatedBy = CurrentUser.username();
        stocks.saveAndFlush(s);
        return get(id);
    }

    @Transactional
    public MaterialView adjust(Long id, AdjustmentRequest request) {
        Material m = locked(id);
        MaterialStock s = requireReady(m);
        quantity(request.targetQty, "调整后数量");
        String reason = required(request.reason, "调整原因", 1000);
        version(s.versionNo, request.stockVersion);
        if (request.targetQty.compareTo(nvl(s.lockedQty)) < 0) throw new BusinessException("调整后库存不能低于锁定库存");
        BigDecimal before = s.currentQty;
        s.currentQty = request.targetQty;
        movement(m, s, "ADJUST", s.currentQty.subtract(before), before, null, today(), reason, false);
        s.updatedBy = CurrentUser.username();
        stocks.saveAndFlush(s);
        return get(id);
    }

    private InventoryTxn movement(Material m, MaterialStock s, String type, BigDecimal delta, BigDecimal before,
                          Long dailyId, LocalDate date, String remark, boolean correction) {
        return movement(m, s, type, delta, before, dailyId, date, remark, correction, CurrentUser.username());
    }

    private InventoryTxn movement(Material m, MaterialStock s, String type, BigDecimal delta, BigDecimal before,
                          Long dailyId, LocalDate date, String remark, boolean correction, String actor) {
        InventoryTxn t = new InventoryTxn();
        t.itemType = "MATERIAL"; t.itemId = m.id; t.itemCode = m.code; t.itemName = m.name;
        t.txnType = "INITIAL".equals(type) || "ADJUST".equals(type) || "DAILY_NOTE".equals(type)
                ? "ADJUST" : (delta.signum() >= 0 ? "IN" : "OUT");
        t.qty = delta; t.beforeQty = before; t.afterQty = s.currentQty;
        t.materialMovementType = type; t.businessDate = date; t.dailyRecordId = dailyId;
        t.sourceType = correction ? "MATERIAL_DAILY_CORRECTION" : "MATERIAL_" + type;
        t.operator = actor; t.createdBy = t.operator; t.remark = remark;
        t.createdAt = LocalDateTime.now(ZONE);
        return transactions.save(t);
    }

    public PageResult<MaterialDailyRecord> records(Long id, LocalDate start, LocalDate end, int page, int size) {
        material(id); dateRange(start, end);
        Specification<MaterialDailyRecord> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("materialId"), id));
            if (start != null) predicates.add(cb.greaterThanOrEqualTo(root.get("businessDate"), start));
            if (end != null) predicates.add(cb.lessThanOrEqualTo(root.get("businessDate"), end));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return page(daily.findAll(spec, paging(page, size, Sort.by(Sort.Direction.DESC, "businessDate", "id"))));
    }

    public PageResult<InventoryTxn> ledger(Long id, LocalDate start, LocalDate end, int page, int size) {
        material(id); dateRange(start, end);
        Specification<InventoryTxn> spec = (root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("itemType"), "MATERIAL"));
            predicates.add(cb.equal(root.get("itemId"), id));
            predicates.add(cb.or(cb.isFalse(root.get("deleted")), cb.isNull(root.get("deleted"))));
            var effectiveDate = cb.<LocalDate>coalesce(root.get("businessDate"), root.get("createdAt").as(LocalDate.class));
            if (start != null) predicates.add(cb.greaterThanOrEqualTo(effectiveDate, start));
            if (end != null) predicates.add(cb.lessThanOrEqualTo(effectiveDate, end));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<InventoryTxn> result = transactions.findAll(spec, paging(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        // Do not mutate legacy entities just to display a fallback business date.
        return page(result);
    }

    public List<TrendPoint> trend(Long id) {
        material(id);
        LocalDate end = today(), start = end.minusDays(29);
        Map<LocalDate, MaterialDailyRecord> rows = daily.findByMaterialIdAndBusinessDateBetweenOrderByBusinessDateAsc(id, start, end)
                .stream().collect(Collectors.toMap(r -> r.businessDate, r -> r));
        return start.datesUntil(end.plusDays(1)).map(date -> {
            MaterialDailyRecord r = rows.get(date);
            BigDecimal production = r == null ? BigDecimal.ZERO : r.productionQty;
            BigDecimal loss = r == null ? BigDecimal.ZERO : r.lossQty;
            return new TrendPoint(date, production, loss, production.add(loss));
        }).toList();
    }

    public List<MaterialView> warnings() {
        Map<Long, MaterialStock> stockMap = stocks.findAll().stream().filter(s -> !Boolean.TRUE.equals(s.deleted))
                .collect(Collectors.toMap(s -> s.materialId, s -> s));
        return materials.findAll().stream().filter(m -> !Boolean.TRUE.equals(m.deleted) && !Boolean.FALSE.equals(m.enabled)
                && !blank(m.unit) && m.safetyStock != null)
                .filter(m -> stockMap.containsKey(m.id) && stockMap.get(m.id).currentQty != null
                        && stockMap.get(m.id).currentQty.compareTo(m.safetyStock) <= 0)
                .map(m -> {
                    MaterialStock s = stockMap.get(m.id);
                    return new MaterialView(m.id, m.name, m.category, m.unit, m.safetyStock,
                            m.remark, true, m.versionNo, s.currentQty, s.versionNo, true, false, null, true);
                })
                .sorted(Comparator.comparing(MaterialView::currentQty).thenComparing(MaterialView::id)).toList();
    }

    private Material material(Long id) {
        return materials.findById(id).filter(m -> !Boolean.TRUE.equals(m.deleted))
                .orElseThrow(() -> new BusinessException("材料不存在"));
    }
    private Material locked(Long id) {
        return materials.lockById(id).orElseThrow(() -> new BusinessException("材料不存在"));
    }
    private MaterialStock stock(Long id) { return stocks.findByMaterialIdAndDeletedFalse(id).orElse(null); }
    private MaterialStock requireReady(Material m) {
        if (Boolean.FALSE.equals(m.enabled)) throw new BusinessException("该材料已停用，不能登记数量");
        MaterialStock s = stock(m.id);
        if (blank(m.unit) || m.safetyStock == null || s == null || s.currentQty == null)
            throw new BusinessException("请先完善材料单位、初始数量和预警值");
        return s;
    }
    private void version(Long actual, Long expected) {
        if (expected == null || !Objects.equals(actual, expected))
            throw new OptimisticLockingFailureException("数据已被其他人修改，请刷新后重试");
    }
    private void quantity(BigDecimal value, String label) {
        if (value == null) throw new BusinessException("请填写" + label);
        if (value.signum() < 0) throw new BusinessException(label + "不能为负数（消耗不能超过库存）");
        if (value.stripTrailingZeros().scale() > 3 || value.compareTo(MAX_QTY) > 0)
            throw new BusinessException(label + "最多三位小数且不能超过999999999999999.999");
    }
    private static BigDecimal nvl(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static boolean equal(BigDecimal a, BigDecimal b) { return a.compareTo(b) == 0; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String trim(String value) { return blank(value) ? null : value.trim(); }
    private String required(String value, String label, int max) {
        String result = optional(value, label, max);
        if (result == null) throw new BusinessException("请填写" + label);
        return result;
    }
    private String optional(String value, String label, int max) {
        String result = trim(value);
        if (result != null && result.length() > max) throw new BusinessException(label + "不能超过" + max + "字");
        return result;
    }
    private void dateRange(LocalDate start, LocalDate end) {
        if (start != null && end != null && start.isAfter(end)) throw new BusinessException("开始日期不能晚于结束日期");
    }
    private PageRequest paging(int page, int size, Sort sort) {
        if (page < 1 || size < 1 || size > 100) throw new BusinessException("页码应大于零，每页最多100条");
        return PageRequest.of(page - 1, size, sort);
    }
    private <T> PageResult<T> page(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements(), page.getNumber() + 1, page.getSize(), page.getTotalPages());
    }
}

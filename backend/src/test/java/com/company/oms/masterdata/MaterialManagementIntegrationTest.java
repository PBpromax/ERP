package com.company.oms.masterdata;

import com.company.oms.common.BusinessException;
import com.company.oms.inventory.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:material-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class MaterialManagementIntegrationTest {
    @Autowired MaterialManagementService service;
    @Autowired MaterialRepository materials;
    @Autowired MaterialDailyRecordRepository daily;
    @Autowired InventoryTxnRepository transactions;
    @Autowired MaterialStockRepository stocks;
    @Autowired MaterialCatalogInitializer initializer;

    MaterialManagementService.MaterialRequest fields() {
        var r = new MaterialManagementService.MaterialRequest();
        r.name = "原料测试-" + UUID.randomUUID(); r.category = "颗粒"; r.unit = "公斤";
        r.initialQty = new BigDecimal("100"); r.safetyStock = new BigDecimal("97"); r.enabled = true;
        return r;
    }
    MaterialManagementService.DailyRequest quantities(MaterialManagementService.MaterialView view, LocalDate date,
            String purchase, String production, String loss) {
        var r = new MaterialManagementService.DailyRequest();
        r.purchaseQty = new BigDecimal(purchase); r.productionQty = new BigDecimal(production); r.lossQty = new BigDecimal(loss);
        var old = service.dailyRecord(view.id(), date); r.versionNo = old == null ? -1L : old.versionNo;
        r.stockVersion = view.stockVersion(); return r;
    }
    void qty(String expected, BigDecimal actual) { assertEquals(0, new BigDecimal(expected).compareTo(actual)); }

    @Test void dailyTotalsCorrectionsAndRepeatRequestsDoNotDoubleCount() {
        var m = service.create(fields()); LocalDate today = service.today();
        var request = quantities(m, today, "20", "15", "5");
        var first = service.saveDaily(m.id(), today, request); qty("100", first.currentQty());
        long count = service.ledger(m.id(), null, null, 1, 100).total();
        service.saveDaily(m.id(), today, request); assertEquals(count, service.ledger(m.id(), null, null, 1, 100).total());
        var corrected = service.saveDaily(m.id(), today, quantities(first, today, "20", "18", "5"));
        qty("97", corrected.currentQty()); assertTrue(corrected.warning());
        assertEquals(count + 1, service.ledger(m.id(), null, null, 1, 100).total());
        var latest = service.ledger(m.id(), null, null, 1, 25).items().get(0);
        assertEquals("PRODUCTION", latest.materialMovementType); assertEquals("MATERIAL_DAILY_CORRECTION", latest.sourceType);
        qty("-3", latest.qty); qty("100", latest.beforeQty); qty("97", latest.afterQty);
        var point = service.trend(m.id()).get(29); qty("18", point.productionQty()); qty("5", point.lossQty()); qty("23", point.totalQty());
        assertEquals(1, service.records(m.id(), null, null, 1, 25).total());
    }

    @Test void invalidInputsRollBackAllChangesAndKeepThePreviousDailyRecord() {
        var m = service.create(fields()); var date = service.today();
        m = service.saveDaily(m.id(), date, quantities(m, date, "1", "1", "0"));
        long count = service.ledger(m.id(), null, null, 1, 100).total();
        final var before = m;
        for (var r : List.of(quantities(before, date, "0", "101", "0"), quantities(before, date, "-1", "0", "0"), quantities(before, date, "0.0001", "0", "0"))) {
            assertThrows(BusinessException.class, () -> service.saveDaily(before.id(), date, r));
            qty("100", service.get(before.id()).currentQty());
            qty("1", service.dailyRecord(before.id(), date).purchaseQty);
            assertEquals(count, service.ledger(before.id(), null, null, 1, 100).total());
        }
        assertThrows(BusinessException.class, () -> service.saveDaily(before.id(), date.plusDays(1), quantities(before, date, "0", "0", "0")));
    }

    @Test void backfillThirtyDayBoundariesAndLegacyLedgerDateFallback() {
        var m = service.create(fields()); var today = service.today();
        assertEquals(LocalDate.now(MaterialManagementService.ZONE), today);
        assertEquals(LocalDate.of(2026, 10, 15), Instant.parse("2026-10-14T16:00:00Z").atZone(MaterialManagementService.ZONE).toLocalDate());
        for (long ago : List.of(30L, 29L, 1L, 0L)) {
            var date = today.minusDays(ago); m = service.saveDaily(m.id(), date, quantities(m, date, "0", "1.125", "0.125"));
        }
        var trend = service.trend(m.id()); assertEquals(30, trend.size()); assertEquals(today.minusDays(29), trend.get(0).date());
        qty("1.25", trend.get(0).totalQty()); qty("0", trend.get(1).totalQty()); qty("95", service.get(m.id()).currentQty());
        assertEquals(4, service.records(m.id(), null, null, 1, 25).total());
        assertEquals(1, service.records(m.id(), today.minusDays(30), today.minusDays(30), 1, 25).total());
        InventoryTxn legacy = new InventoryTxn(); legacy.itemType = "MATERIAL"; legacy.itemId = m.id();
        legacy.createdAt = today.minusDays(50).atTime(12, 0); legacy.txnType = "ADJUST";
        legacy.qty = BigDecimal.ZERO; legacy.beforeQty = m.currentQty(); legacy.afterQty = m.currentQty(); transactions.saveAndFlush(legacy);
        assertEquals(1, service.ledger(m.id(), today.minusDays(50), today.minusDays(50), 1, 25).total());
    }

    @Test void staleAndSimultaneousUpdatesCannotOverwriteAnotherEmployee() throws Exception {
        var m = service.create(fields()); var date = service.today();
        var a = quantities(m, date, "0", "3", "0"); var b = quantities(m, date, "0", "5", "0");
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = List.of(pool.submit(() -> attempt(m.id(), date, a, start)), pool.submit(() -> attempt(m.id(), date, b, start)));
            start.countDown(); int success = 0;
            for (var f : futures) if (f.get(10, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertTrue(List.of(new BigDecimal("97"), new BigDecimal("95")).stream().anyMatch(q -> q.compareTo(service.get(m.id()).currentQty()) == 0));
            assertEquals(1, service.records(m.id(), null, null, 1, 25).total());
        } finally { pool.shutdownNow(); }
    }
    boolean attempt(Long id, LocalDate date, MaterialManagementService.DailyRequest r, CountDownLatch start) throws Exception {
        start.await(); try { service.saveDaily(id, date, r); return true; } catch (OptimisticLockingFailureException ex) { return false; }
    }

    @Test void warningsAdjustmentsUnitsAndDisabledMaterialsKeepTheirHistory() {
        var r = fields(); r.safetyStock = new BigDecimal("100"); var m = service.create(r);
        assertTrue(service.warnings().stream().anyMatch(w -> w.id().equals(m.id())));
        var adjustment = new MaterialManagementService.AdjustmentRequest(); adjustment.targetQty = new BigDecimal("101.001");
        adjustment.reason = "盘点"; adjustment.stockVersion = m.stockVersion(); var changed = service.adjust(m.id(), adjustment);
        assertFalse(changed.warning()); assertTrue(service.warnings().stream().noneMatch(w -> w.id().equals(m.id())));
        assertThrows(OptimisticLockingFailureException.class, () -> service.adjust(m.id(), adjustment));
        r.initialQty = null; r.versionNo = changed.versionNo(); r.stockVersion = changed.stockVersion(); r.unit = "米";
        var corrected = service.update(m.id(), r);
        assertEquals("米", corrected.unit()); qty("101.001", corrected.currentQty()); qty("100", corrected.safetyStock());
        assertThrows(OptimisticLockingFailureException.class, () -> service.update(m.id(), r));
        r.versionNo = corrected.versionNo(); r.stockVersion = corrected.stockVersion();
        r.enabled = false; var disabled = service.update(m.id(), r);
        assertFalse(disabled.warning()); assertThrows(BusinessException.class, () -> service.saveDaily(m.id(), service.today(), quantities(disabled, service.today(), "0", "0", "0")));
        assertThrows(BusinessException.class, () -> service.delete(m.id())); assertEquals(2, service.ledger(m.id(), null, null, 1, 25).total());
    }

    @Test void correctingUnitPreservesDailyQuantitiesAndLedgerAndStillValidatesRequiredUnit() {
        var r = fields(); var m = service.create(r); var date = service.today();
        m = service.saveDaily(m.id(), date, quantities(m, date, "20", "3.125", "1"));
        var beforeDay = service.dailyRecord(m.id(), date);
        var beforeLedger = service.ledger(m.id(), null, null, 1, 100).items();
        r.initialQty = null; r.unit = "袋"; r.versionNo = m.versionNo(); r.stockVersion = m.stockVersion();
        var corrected = service.update(m.id(), r);
        assertEquals("袋", service.get(m.id()).unit()); qty("115.875", corrected.currentQty()); qty("97", corrected.safetyStock());
        var afterDay = service.dailyRecord(m.id(), date);
        assertEquals(beforeDay.id, afterDay.id); assertEquals(beforeDay.versionNo, afterDay.versionNo);
        qty("20", afterDay.purchaseQty); qty("3.125", afterDay.productionQty); qty("1", afterDay.lossQty);
        var afterLedger = service.ledger(m.id(), null, null, 1, 100).items();
        assertEquals(beforeLedger.size(), afterLedger.size());
        for (int i = 0; i < beforeLedger.size(); i++) {
            assertEquals(beforeLedger.get(i).id, afterLedger.get(i).id);
            assertEquals(beforeLedger.get(i).qty, afterLedger.get(i).qty);
            assertEquals(beforeLedger.get(i).beforeQty, afterLedger.get(i).beforeQty);
            assertEquals(beforeLedger.get(i).afterQty, afterLedger.get(i).afterQty);
        }
        r.unit = " "; r.versionNo = corrected.versionNo(); r.stockVersion = corrected.stockVersion();
        assertThrows(BusinessException.class, () -> service.update(corrected.id(), r));
        assertEquals("袋", service.get(m.id()).unit()); qty("115.875", service.get(m.id()).currentQty());
    }

    @Test void syntheticCatalogInitializationDoesNotReplaceUserData() throws Exception {
        var catalog = initializer.catalog(); assertEquals(2, catalog.size());
        assertEquals(Map.of("测试类别", 2L), catalog.stream().collect(java.util.stream.Collectors.groupingBy(MaterialCatalogInitializer.Entry::category, java.util.stream.Collectors.counting())));
        assertEquals(2, catalog.stream().map(MaterialCatalogInitializer.Entry::code).distinct().count());
        var seed = materials.findAll().stream().filter(m -> "MAT-BS-001".equals(m.code)).findFirst().orElseThrow();
        var incomplete = service.get(seed.id); assertFalse(incomplete.ready()); assertNull(incomplete.unit()); assertNull(incomplete.currentQty()); assertNull(incomplete.safetyStock());
        assertThrows(BusinessException.class, () -> service.saveDaily(seed.id, service.today(), quantities(incomplete, service.today(), "0", "0", "0")));
        var r = fields(); r.name = seed.name; r.category = seed.category; r.versionNo = incomplete.versionNo();
        var configured = service.update(seed.id, r);
        long materialCount = materials.count(); initializer.run(); initializer.run();
        assertEquals(materialCount, materials.count()); qty("100", service.get(seed.id).currentQty()); assertEquals("公斤", service.get(seed.id).unit());
        qty(configured.safetyStock().toPlainString(), service.get(seed.id).safetyStock());
    }

    @Test void zeroDaysAndRemarkOnlyCorrectionsHaveAnAuditTrailButNoConsumption() {
        var m = service.create(fields()); var date = service.today();
        m = service.saveDaily(m.id(), date, quantities(m, date, "0", "0", "0"));
        var r = quantities(m, date, "0", "0", "0"); r.remark = "休息日";
        m = service.saveDaily(m.id(), date, r); qty("100", m.currentQty());
        assertEquals(3, service.ledger(m.id(), null, null, 1, 25).total());
        assertEquals("DAILY_NOTE", service.ledger(m.id(), null, null, 1, 25).items().get(0).materialMovementType);
        qty("0", service.trend(m.id()).get(29).totalQty());
    }

    @Test void reductionsAcrossTypesCreditStockBeforeReversingPurchases() {
        var r = fields(); r.initialQty = BigDecimal.ZERO; var m = service.create(r); var date = service.today();
        m = service.saveDaily(m.id(), date, quantities(m, date, "100", "100", "0"));
        m = service.saveDaily(m.id(), date, quantities(m, date, "0", "0", "0")); qty("0", m.currentQty());
        assertTrue(service.ledger(m.id(), null, null, 1, 25).items().stream().allMatch(t -> t.afterQty.signum() >= 0));
    }
}

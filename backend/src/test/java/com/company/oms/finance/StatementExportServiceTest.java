package com.company.oms.finance;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatementExportServiceTest {
    @Autowired StatementExportService statementExportService;
    @Autowired StatementRepository statementRepository;
    @Autowired StatementItemRepository statementItemRepository;

    @Test
    void exportKeepsReturnValuesNumericRedAndIncludedInExpandedTotal() throws Exception {
        Statement statement = new Statement();
        statement.statementNo = "RD20300701001";
        statement.customerName = "Excel退货测试客户";
        statement.month = "2030-07";
        statement.generatedAt = LocalDate.of(2030, 7, 31).atStartOfDay();
        statement.receivableAmount = new BigDecimal("90.00");
        statement = statementRepository.save(statement);

        // Insert the return first to verify export ordering does not depend on insertion order.
        statementItemRepository.save(item(statement.id, 11, new BigDecimal("-2.000"),
                new BigDecimal("10.00"), new BigDecimal("-20.00"), FinanceService.ADJUSTMENT_RETURN));
        for (int index = 0; index < 11; index++) {
            statementItemRepository.save(item(statement.id, index, BigDecimal.ONE,
                    new BigDecimal("10.00"), new BigDecimal("10.00"), null));
        }

        byte[] bytes = statementExportService.export(statement.id);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            int populatedRows = 0;
            Row returnRow = null;
            for (int rowIndex = 8; rowIndex <= 19; rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null) continue;
                Cell product = row.getCell(2);
                if (product != null && product.getCellType() == CellType.STRING && !product.getStringCellValue().isBlank()) {
                    populatedRows++;
                }
                Cell qty = row.getCell(5);
                if (qty != null && qty.getCellType() == CellType.NUMERIC && qty.getNumericCellValue() < 0) {
                    returnRow = row;
                }
            }

            assertEquals(12, populatedRows);
            assertNotNull(returnRow);
            assertEquals(19, returnRow.getRowNum());
            assertEquals("退库", returnRow.getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, returnRow.getCell(5).getCellType());
            assertEquals(-2.0, returnRow.getCell(5).getNumericCellValue(), 0.0001);
            assertEquals(CellType.NUMERIC, returnRow.getCell(7).getCellType());
            assertEquals(-20.0, returnRow.getCell(7).getNumericCellValue(), 0.0001);
            Font returnFont = workbook.getFontAt(returnRow.getCell(7).getCellStyle().getFontIndex());
            assertEquals(IndexedColors.RED.getIndex(), returnFont.getColor());

            Cell total = sheet.getRow(20).getCell(7);
            assertEquals(CellType.FORMULA, total.getCellType());
            assertEquals("SUM(H9:H20)", total.getCellFormula());
            assertEquals(90.0, workbook.getCreationHelper().createFormulaEvaluator()
                    .evaluate(total).getNumberValue(), 0.0001);
            assertTrue(sheet.getMergedRegions().stream()
                    .anyMatch(region -> "A21:E21".equals(region.formatAsString())));
            assertEquals(9.0, workbook.getCreationHelper().createFormulaEvaluator()
                    .evaluate(sheet.getRow(20).getCell(5)).getNumberValue(), 0.0001);
            assertTrue(sheet.getMergedRegions().stream()
                    .anyMatch(region -> "H21:I21".equals(region.formatAsString())));
            assertTrue(sheet.getMergedRegions().stream()
                    .noneMatch(region -> "A21:I21".equals(region.formatAsString())));
            assertTrue(sheet.getFitToPage());
            assertEquals(1, sheet.getPrintSetup().getFitWidth());
            assertEquals(0, sheet.getPrintSetup().getFitHeight());
            assertTrue(sheet.getRow(21).getCell(0).getStringCellValue().contains("金额为90元"));
        }
    }

    @Test
    void shortStatementKeepsReturnAtBottomAndPreservesAuthoritativeAmounts() throws Exception {
        Statement statement = new Statement(); statement.statementNo = "RETURN-SHORT";
        statement.customerName = "小数单价验证"; statement.month = "2030-07";
        statement.receivableAmount = new BigDecimal("0.35"); statement = statementRepository.save(statement);
        statementItemRepository.save(item(statement.id, 0, new BigDecimal("-1"),
                new BigDecimal("0.115"), new BigDecimal("-0.12"), FinanceService.ADJUSTMENT_RETURN));
        statementItemRepository.save(item(statement.id, 1, new BigDecimal("1"),
                new BigDecimal("0.465"), new BigDecimal("0.47"), null));
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(statementExportService.export(statement.id)))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(CellType.BLANK, sheet.getRow(9).getCell(0).getCellType());
            assertEquals(CellType.BLANK, sheet.getRow(17).getCell(1).getCellType());
            Row returned = sheet.getRow(18);
            assertEquals("退库", returned.getCell(1).getStringCellValue());
            assertEquals(CellType.NUMERIC, returned.getCell(7).getCellType());
            assertEquals(-0.12, returned.getCell(7).getNumericCellValue(), 0.000001);
            assertEquals(0.115, returned.getCell(6).getNumericCellValue(), 0.000001);
            assertEquals("#,##0.##############################", returned.getCell(6).getCellStyle().getDataFormatString());
            assertEquals(CellType.NUMERIC, returned.getCell(0).getCellType());
            assertEquals(0.35, workbook.getCreationHelper().createFormulaEvaluator()
                    .evaluate(sheet.getRow(19).getCell(7)).getNumberValue(), 0.000001);
        }
    }

    @Test
    void summaryOnlyHistoryExportsSourceTotalWithoutInventedProductOrQuantity() throws Exception {
        Statement statement = new Statement(); statement.statementNo = "SOURCE-SUMMARY";
        statement.customerName = "历史汇总测试"; statement.month = "2022-03";
        statement.sourceAmountOnly = true; statement.receivableAmount = new BigDecimal("35272.00");
        statement = statementRepository.save(statement);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(statementExportService.export(statement.id)))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(35272.0, sheet.getRow(19).getCell(7).getNumericCellValue(), 0.001);
            assertEquals(CellType.BLANK, sheet.getRow(19).getCell(5).getCellType());
            assertEquals(CellType.BLANK, sheet.getRow(8).getCell(2).getCellType());
        }
    }

    @Test
    void undatedHistoryExportsWithoutInventingAStatementMonth() throws Exception {
        Statement statement = new Statement(); statement.statementNo = "RD-UNDATED-TEST";
        statement.customerName = "日期待确认"; statement.sourceDateText = "2.2";
        statement.sourceAmountOnly = true; statement.receivableAmount = new BigDecimal("176200");
        statement = statementRepository.save(statement);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(statementExportService.export(statement.id)))) {
            var sheet = workbook.getSheetAt(0);
            assertTrue(sheet.getRow(0).getCell(0).getStringCellValue().contains("日期待确认"));
            assertTrue(sheet.getRow(6).getCell(0).getStringCellValue().contains("2.2"));
            assertEquals(176200.0, sheet.getRow(19).getCell(7).getNumericCellValue(), 0.001);
        }
    }

    private StatementItem item(Long statementId, int index, BigDecimal qty, BigDecimal unitPrice,
                               BigDecimal amount, String adjustmentType) {
        StatementItem item = new StatementItem();
        item.statementId = statementId;
        item.deliveryDate = LocalDate.of(2030, 7, Math.min(index + 1, 28));
        item.orderNo = adjustmentType == null ? "RB20300701" + String.format("%03d", index) : "RETURN-001";
        item.productName = adjustmentType == null ? "产品" + index : "退货产品";
        item.spec = "SPEC-" + index;
        item.unit = "件";
        item.qty = qty;
        item.unitPrice = unitPrice;
        item.amount = amount;
        item.adjustmentType = adjustmentType;
        return item;
    }
}

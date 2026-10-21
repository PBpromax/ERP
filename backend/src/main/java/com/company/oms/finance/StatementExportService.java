package com.company.oms.finance;

import com.company.oms.common.BusinessException;
import com.company.oms.common.MoneyUtil;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

@Service
public class StatementExportService {
    private final StatementRepository statementRepository;
    private final StatementItemRepository statementItemRepository;

    public StatementExportService(StatementRepository statementRepository,
                                  StatementItemRepository statementItemRepository) {
        this.statementRepository = statementRepository;
        this.statementItemRepository = statementItemRepository;
    }

    public byte[] export(Long statementId) {
        Statement statement = statementRepository.findById(statementId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("对账单不存在"));
        List<StatementItem> items = statementItemRepository.findByStatementIdAndDeletedFalse(statement.id).stream()
                .sorted(Comparator.comparing((StatementItem item) -> FinanceService.ADJUSTMENT_RETURN.equals(item.adjustmentType))
                        .thenComparing(item -> item.deliveryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(item -> item.id)).toList();
        try (InputStream input = new ClassPathResource("templates/statement-template.xlsx").getInputStream();
             Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            fillHeader(sheet, statement);
            fillItems(workbook, sheet, items);
            if (Boolean.TRUE.equals(statement.sourceAmountOnly) && items.isEmpty()) {
                setCell(sheet, "A9", "原表未提供商品明细");
                setCell(sheet, "A20", "原表账款合计：");
                sheet.getRow(19).getCell(7).removeFormula();
                sheet.getRow(19).getCell(7).setCellValue(MoneyUtil.money(statement.receivableAmount).doubleValue());
                sheet.getRow(19).getCell(5).setBlank();
            }
            configurePrintLayout(sheet);
            com.company.oms.common.ExcelDecimalFormats.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("生成对账单失败：" + e.getMessage());
        }
    }

    private void fillHeader(Sheet sheet, Statement statement) {
        if (statement.month == null || statement.month.isBlank()) {
            setCell(sheet, "A1", "示例供货公司 对账单（日期待确认）");
            setCell(sheet, "A3", "TO：   " + nvl(statement.customerName));
            setCell(sheet, "A7", "原表日期待确认：" + nvl(statement.sourceDateText));
            setCell(sheet, "A21", "原表账款金额为" + MoneyUtil.display(statement.receivableAmount) + "元");
            setCell(sheet, "F29", "    " + dateText(LocalDate.now()));
            return;
        }
        YearMonth month = YearMonth.parse(statement.month);
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        LocalDate today = LocalDate.now();
        String monthText = month.getYear() + "年" + month.getMonthValue() + "月份";
        setCell(sheet, "A1", "示例供货公司" + monthText + "对账单");
        setCell(sheet, "A3", "TO：   " + nvl(statement.customerName));
        setCell(sheet, "A7", "起止日期：（" + dateText(start) + " - " + dateText(end) + "）");
        setCell(sheet, "A21", "截止" + dateText(end) + "累计对帐需开票金额为" + MoneyUtil.display(statement.receivableAmount) + "元");
        setCell(sheet, "F29", "    " + dateText(today));
    }

    private void fillItems(Workbook workbook, Sheet sheet, List<StatementItem> items) {
        int firstRow = 8;
        int templateRows = 11;
        int neededRows = Math.max(templateRows, items.size());
        for (int i = templateRows; i < neededRows; i++) {
            copyRow(sheet, firstRow + templateRows - 1, firstRow + i);
        }

        Map<Short, CellStyle> returnStyles = new HashMap<>();
        Map<String, CellStyle> detailStyles = new HashMap<>();
        int normalCount = (int) items.stream().filter(item -> !FinanceService.ADJUSTMENT_RETURN.equals(item.adjustmentType)).count();
        int returnStart = neededRows - (items.size() - normalCount);
        for (int i = 0; i < neededRows; i++) {
            Row row = getRow(sheet, firstRow + i);
            for (int col = 0; col <= 8; col++) {
                Cell cell = row.getCell(col);
                if (cell == null) cell = row.createCell(col);
                formatDetailCell(workbook, cell, col, detailStyles);
            }
            StatementItem item = i < normalCount ? items.get(i)
                    : i >= returnStart ? items.get(normalCount + i - returnStart) : null;
            BigDecimal amount = item == null ? BigDecimal.ZERO : MoneyUtil.money(item.amount);
            if (item == null) {
                setCell(row, 0, "");
                setCell(row, 1, "");
                setCell(row, 2, "");
                setCell(row, 3, "");
                setCell(row, 4, "");
                setCell(row, 5, null);
                setCell(row, 6, null);
                setCell(row, 7, null);
                setCell(row, 8, "");
                continue;
            }
            setCell(row, 0, item.deliveryDate);
            setCell(row, 1, FinanceService.ADJUSTMENT_RETURN.equals(item.adjustmentType) ? "退库" : item.orderNo);
            setCell(row, 2, item.productName);
            setCell(row, 3, item.spec);
            setCell(row, 4, item.unit);
            setCell(row, 5, item.qty);
            setCell(row, 6, item.unitPrice);
            setCell(row, 7, amount);
            setCell(row, 8, "");
            if (FinanceService.ADJUSTMENT_RETURN.equals(item.adjustmentType)) {
                applyReturnStyle(workbook, row, returnStyles);
            }
        }

        int totalRow = 19 + Math.max(0, neededRows - templateRows);
        splitTotalRowMerge(sheet, totalRow);
        setCell(getRow(sheet, totalRow), 0, "本月送货合计：");
        Cell totalCell = getRow(sheet, totalRow).getCell(7);
        if (totalCell == null) totalCell = getRow(sheet, totalRow).createCell(7);
        totalCell.setCellFormula("SUM(H" + (firstRow + 1) + ":H" + (firstRow + neededRows) + ")");
        workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(totalCell);
        Cell qtyCell = getRow(sheet, totalRow).getCell(5);
        if (qtyCell == null) qtyCell = getRow(sheet, totalRow).createCell(5);
        formatDetailCell(workbook, qtyCell, 5, detailStyles);
        qtyCell.setCellFormula("SUM(F" + (firstRow + 1) + ":F" + (firstRow + neededRows) + ")");
        workbook.getCreationHelper().createFormulaEvaluator().evaluateFormulaCell(qtyCell);
        workbook.setForceFormulaRecalculation(true);
    }

    private void formatDetailCell(Workbook workbook, Cell cell, int col, Map<String, CellStyle> styles) {
        CellStyle base = cell.getCellStyle();
        String format = switch (col) {
            case 0 -> "m\"月\"d\"日\"";
            case 5 -> "0.###";
            case 6 -> "0.#####";
            case 7 -> "0.00";
            default -> base.getDataFormatString();
        };
        String key = base.getIndex() + ":" + format;
        cell.setCellStyle(styles.computeIfAbsent(key, ignored -> {
            CellStyle style = workbook.createCellStyle();
            style.cloneStyleFrom(base);
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            style.setBorderTop(BorderStyle.THIN); style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN); style.setBorderRight(BorderStyle.THIN);
            style.setWrapText(true);
            return style;
        }));
    }

    private void configurePrintLayout(Sheet sheet) {
        sheet.setFitToPage(true);
        sheet.setAutobreaks(true);
        PrintSetup printSetup = sheet.getPrintSetup();
        printSetup.setFitWidth((short) 1);
        printSetup.setFitHeight((short) 0);
    }

    private void splitTotalRowMerge(Sheet sheet, int totalRow) {
        for (int index = sheet.getNumMergedRegions() - 1; index >= 0; index--) {
            CellRangeAddress region = sheet.getMergedRegion(index);
            if (region.getFirstRow() == totalRow && region.getLastRow() == totalRow
                    && region.getFirstColumn() == 0 && region.getLastColumn() == 8) {
                sheet.removeMergedRegion(index);
            }
        }
        sheet.addMergedRegion(new CellRangeAddress(totalRow, totalRow, 0, 4));
        sheet.addMergedRegion(new CellRangeAddress(totalRow, totalRow, 7, 8));
    }

    private void applyReturnStyle(Workbook workbook, Row row, Map<Short, CellStyle> returnStyles) {
        for (int col = 0; col <= 8; col++) {
            Cell cell = row.getCell(col);
            if (cell == null) cell = row.createCell(col);
            CellStyle baseStyle = cell.getCellStyle();
            CellStyle redStyle = returnStyles.computeIfAbsent(baseStyle.getIndex(), ignored -> {
                CellStyle style = workbook.createCellStyle();
                style.cloneStyleFrom(baseStyle);
                Font baseFont = workbook.getFontAt(baseStyle.getFontIndex());
                Font redFont = workbook.createFont();
                redFont.setFontName(baseFont.getFontName());
                redFont.setFontHeight(baseFont.getFontHeight());
                redFont.setBold(baseFont.getBold());
                redFont.setItalic(baseFont.getItalic());
                redFont.setUnderline(baseFont.getUnderline());
                redFont.setStrikeout(baseFont.getStrikeout());
                redFont.setTypeOffset(baseFont.getTypeOffset());
                redFont.setColor(IndexedColors.RED.getIndex());
                style.setFont(redFont);
                return style;
            });
            cell.setCellStyle(redStyle);
        }
    }

    private void copyRow(Sheet sheet, int sourceIndex, int targetIndex) {
        sheet.shiftRows(targetIndex, sheet.getLastRowNum(), 1, true, false);
        Row source = sheet.getRow(sourceIndex);
        Row target = sheet.createRow(targetIndex);
        target.setHeight(source.getHeight());
        for (int i = source.getFirstCellNum(); i < source.getLastCellNum(); i++) {
            Cell sourceCell = source.getCell(i);
            if (sourceCell == null) continue;
            Cell targetCell = target.createCell(i);
            targetCell.setCellStyle(sourceCell.getCellStyle());
        }
        for (CellRangeAddress merged : sheet.getMergedRegions()) {
            if (merged.getFirstRow() == sourceIndex && merged.getLastRow() == sourceIndex) {
                sheet.addMergedRegion(new CellRangeAddress(targetIndex, targetIndex, merged.getFirstColumn(), merged.getLastColumn()));
            }
        }
    }

    private Row getRow(Sheet sheet, int index) {
        Row row = sheet.getRow(index);
        return row == null ? sheet.createRow(index) : row;
    }

    private void setCell(Sheet sheet, String ref, Object value) {
        CellReferenceRef cellRef = CellReferenceRef.of(ref);
        Row row = getRow(sheet, cellRef.row());
        setCell(row, cellRef.col(), value);
    }

    private void setCell(Row row, int col, Object value) {
        Cell cell = row.getCell(col);
        if (cell == null) cell = row.createCell(col);
        // Clear template formulas as well as their cached values before writing authoritative amounts.
        cell.setBlank();
        if (value == null || "".equals(value)) {
            cell.setBlank();
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(java.sql.Date.valueOf(date));
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private String dateText(LocalDate date) {
        return date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private record CellReferenceRef(int row, int col) {
        static CellReferenceRef of(String ref) {
            int col = 0;
            int i = 0;
            while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
                col = col * 26 + Character.toUpperCase(ref.charAt(i)) - 'A' + 1;
                i++;
            }
            int row = Integer.parseInt(ref.substring(i)) - 1;
            return new CellReferenceRef(row, col - 1);
        }
    }
}

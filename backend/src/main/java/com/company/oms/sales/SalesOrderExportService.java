package com.company.oms.sales;

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
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class SalesOrderExportService {
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;

    public SalesOrderExportService(SalesOrderRepository salesOrderRepository,
                                   SalesOrderItemRepository salesOrderItemRepository) {
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
    }

    public byte[] export(Long orderId) {
        return export(orderId, true);
    }

    public byte[] export(Long orderId, boolean includePrices) {
        SalesOrder order = salesOrderRepository.findById(orderId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("销售订单不存在"));
        List<SalesOrderItem> items = salesOrderItemRepository.findByOrderIdAndDeletedFalse(order.id);
        try (InputStream input = new ClassPathResource("templates/sales-order-template.xlsx").getInputStream();
             Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            fillHeader(sheet, order);
            fillItems(sheet, items);
            if (!includePrices) {
                // Remove values and formulas; hiding columns alone would retain prices in the file.
                for (Row row : sheet) {
                    if (row.getRowNum() < 10) continue;
                    for (int column : new int[]{5, 6}) {
                        Cell cell = row.getCell(column);
                        if (cell != null) cell.setBlank();
                    }
                }
                setCell(sheet, "F1", "销售订单（无价格）");
            }
            com.company.oms.common.ExcelDecimalFormats.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("生成销售订单失败：" + e.getMessage());
        }
    }

    private void fillHeader(Sheet sheet, SalesOrder order) {
        DateTimeFormatter dotFormatter = DateTimeFormatter.ofPattern("yyyy.M.d");
        setCell(sheet, "H2", order.orderDate == null ? "" : order.orderDate.format(dotFormatter));
        setCell(sheet, "H3", order.orderNo);
        setCell(sheet, "D6", "公司名称：" + nvl(order.customerName));
        setCell(sheet, "D7", "公司地址/邮编：" + nvl(order.customerAddress));
        setCell(sheet, "D8", "电话：" + nvl(order.customerPhone));
        setCell(sheet, "D9", "邮箱：" + nvl(order.customerEmail));
        setCell(sheet, "D10", "联系人：" + nvl(order.contactPerson));
        setCell(sheet, "A32", "                                                                                                            日期："
                + dateText(order.orderDate == null ? LocalDate.now() : order.orderDate));
    }

    private void fillItems(Sheet sheet, List<SalesOrderItem> items) {
        int firstRow = 11;
        int neededRows = Math.max(1, items.size());
        for (int i = 1; i < neededRows; i++) {
            copyRow(sheet, firstRow, firstRow + i);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < neededRows; i++) {
            Row row = getRow(sheet, firstRow + i);
            SalesOrderItem item = i < items.size() ? items.get(i) : null;
            if (item == null) {
                setCell(row, 0, "");
                setCell(row, 2, "");
                setCell(row, 3, "");
                setCell(row, 4, null);
                setCell(row, 5, null);
                setCell(row, 6, null);
                setCell(row, 7, "");
                continue;
            }
            BigDecimal amount = MoneyUtil.money(item.amount);
            setCell(row, 0, item.productCode);
            setCell(row, 2, item.productName);
            setCell(row, 3, item.unit);
            setCell(row, 4, item.qty);
            setCell(row, 5, item.unitPrice);
            setCell(row, 6, amount);
            setCell(row, 7, "");
            total = total.add(amount);
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
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    private String nvl(String value) {
        return value == null ? "" : value;
    }

    private String dateText(LocalDate date) {
        return date.getYear() + "年" + String.format("%02d", date.getMonthValue()) + "月" + String.format("%02d", date.getDayOfMonth()) + "日";
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

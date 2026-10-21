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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeliveryNoteExportService {
    private final DeliveryNoteRepository deliveryNoteRepository;
    private final DeliveryNoteItemRepository deliveryNoteItemRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderItemRepository salesOrderItemRepository;

    public DeliveryNoteExportService(DeliveryNoteRepository deliveryNoteRepository,
                                     DeliveryNoteItemRepository deliveryNoteItemRepository,
                                     SalesOrderRepository salesOrderRepository,
                                     SalesOrderItemRepository salesOrderItemRepository) {
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.deliveryNoteItemRepository = deliveryNoteItemRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.salesOrderItemRepository = salesOrderItemRepository;
    }

    public byte[] export(Long deliveryId) {
        DeliveryNote delivery = deliveryNoteRepository.findById(deliveryId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseThrow(() -> new BusinessException("发货信息不存在"));
        List<DeliveryNoteItem> items = deliveryNoteItemRepository.findByDeliveryIdAndDeletedFalse(delivery.id);
        Map<Long, SalesOrderItem> orderItems = salesOrderItemRepository.findAllById(items.stream()
                        .map(item -> item.orderItemId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(item -> item.id, Function.identity()));
        List<SalesOrder> orders = salesOrderRepository.findAllById(orderItems.values().stream()
                        .map(item -> item.orderId).filter(Objects::nonNull).distinct().toList()).stream()
                .filter(item -> !Boolean.TRUE.equals(item.deleted)).toList();
        SalesOrder order = salesOrderRepository.findById(delivery.orderId)
                .filter(item -> !Boolean.TRUE.equals(item.deleted))
                .orElseGet(() -> orders.stream().findFirst().orElse(null));
        Set<String> orderNos = new LinkedHashSet<>();
        for (DeliveryNoteItem item : items) {
            SalesOrderItem orderItem = orderItems.get(item.orderItemId);
            if (orderItem == null) continue;
            orders.stream().filter(candidate -> Objects.equals(candidate.id, orderItem.orderId)).findFirst()
                    .map(candidate -> candidate.orderNo).filter(value -> value != null && !value.isBlank()).ifPresent(orderNos::add);
        }
        try (InputStream input = new ClassPathResource("templates/delivery-note-template.xlsx").getInputStream();
             Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            fillHeader(sheet, delivery, order, orderNos);
            fillItems(sheet, items);
            com.company.oms.common.ExcelDecimalFormats.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("生成送货单失败：" + e.getMessage());
        }
    }

    private void fillHeader(Sheet sheet, DeliveryNote delivery, SalesOrder order, Set<String> orderNos) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.M.d");
        setCell(sheet, "A3", "客户名称：" + nvl(delivery.customerName));
        setCell(sheet, "A4", "客户地址：" + nvl(order == null ? null : order.customerAddress));
        setCell(sheet, "F3", orderNos.isEmpty() ? nvl(delivery.orderNo) : String.join("/", orderNos));
        setCell(sheet, "F4", delivery.deliveryDate == null ? "" : delivery.deliveryDate.format(formatter));
        setCell(sheet, "F5", nvl(delivery.deliveryNo));
    }

    private void fillItems(Sheet sheet, List<DeliveryNoteItem> items) {
        int firstRow = 7;
        int neededRows = Math.max(1, items.size());
        for (int i = 1; i < neededRows; i++) {
            copyRow(sheet, firstRow, firstRow + i);
        }
        for (int i = 0; i < neededRows; i++) {
            Row row = getRow(sheet, firstRow + i);
            DeliveryNoteItem item = i < items.size() ? items.get(i) : null;
            SalesOrderItem orderItem = item == null || item.orderItemId == null
                    ? null
                    : salesOrderItemRepository.findById(item.orderItemId).orElse(null);
            setCell(row, 0, orderItem == null ? "" : orderItem.productCode);
            setCell(row, 1, item == null ? "" : item.productName);
            setCell(row, 2, specText(item, orderItem));
            setCell(row, 3, item == null ? "" : item.unit);
            setCell(row, 4, item == null ? null : MoneyUtil.nvl(item.qty));
            setCell(row, 5, "");
        }
    }

    private String specText(DeliveryNoteItem item, SalesOrderItem orderItem) {
        if (item != null && item.spec != null && !item.spec.isBlank()) return item.spec;
        if (orderItem != null && orderItem.spec != null && !orderItem.spec.isBlank()) return orderItem.spec;
        return "";
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
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date);
        } else {
            cell.setCellValue(String.valueOf(value));
        }
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

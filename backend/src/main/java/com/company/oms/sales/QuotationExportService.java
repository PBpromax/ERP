package com.company.oms.sales;

import com.company.oms.common.BusinessException;
import com.company.oms.common.MoneyUtil;
import com.company.oms.masterdata.Customer;
import com.company.oms.masterdata.CustomerRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class QuotationExportService {
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final CustomerRepository customerRepository;

    public QuotationExportService(QuotationRepository quotationRepository,
                                  QuotationItemRepository quotationItemRepository,
                                  CustomerRepository customerRepository) {
        this.quotationRepository = quotationRepository;
        this.quotationItemRepository = quotationItemRepository;
        this.customerRepository = customerRepository;
    }

    public byte[] export(Long quotationId) {
        Quotation quotation = quotationRepository.findById(quotationId)
                .filter(q -> !Boolean.TRUE.equals(q.deleted))
                .orElseThrow(() -> new BusinessException("报价单不存在"));
        List<QuotationItem> items = quotationItemRepository.findByQuotationIdAndDeletedFalse(quotationId);
        try (InputStream input = new ClassPathResource("templates/quotation-template.xlsx").getInputStream();
             Workbook workbook = new XSSFWorkbook(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.getSheetAt(0);
            fillHeader(sheet, quotation);
            fillItems(sheet, items);
            com.company.oms.common.ExcelDecimalFormats.apply(workbook);
            workbook.write(output);
            return output.toByteArray();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("生成报价单失败：" + e.getMessage());
        }
    }

    private void fillHeader(Sheet sheet, Quotation quotation) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.M.d");
        setCell(sheet, "H2", quotation.quoteDate == null ? "" : quotation.quoteDate.format(formatter));
        setCell(sheet, "H3", quotation.quoteNo);
        setCell(sheet, "E6", quotation.customerName);
        setCell(sheet, "E7", quotation.customerAddress);
        setCell(sheet, "E8", quotation.customerPhone);
        setCell(sheet, "E9", quotation.customerEmail);
        setCell(sheet, "E10", contactPerson(quotation));
        setCell(sheet, "A33", "                                                                                                                      日期："
                + (quotation.quoteDate == null ? "" : quotation.quoteDate.format(formatter)));
    }

    private String contactPerson(Quotation quotation) {
        if (quotation.contactPerson != null && !quotation.contactPerson.isBlank()) {
            return quotation.contactPerson;
        }
        if (quotation.customerId == null) return "";
        return customerRepository.findById(quotation.customerId)
                .map(customer -> customer.contactPerson)
                .filter(contact -> contact != null && !contact.isBlank())
                .orElse("");
    }

    private void fillItems(Sheet sheet, List<QuotationItem> items) {
        int firstRow = 11;
        int neededRows = Math.max(1, items.size());
        for (int i = 1; i < neededRows; i++) {
            copyRow(sheet, firstRow, firstRow + i);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < neededRows; i++) {
            Row row = getRow(sheet, firstRow + i);
            QuotationItem item = i < items.size() ? items.get(i) : null;
            setCell(row, 0, i + 1);
            setCell(row, 1, item == null ? "" : item.productName);
            setCell(row, 2, item == null || item.spec == null || item.spec.isBlank() ? "/" : item.spec);
            setCell(row, 3, item == null ? "" : item.unit);
            setCell(row, 4, item == null ? null : item.qty);
            setCell(row, 5, item == null ? null : item.unitPrice);
            BigDecimal amount = item == null ? BigDecimal.ZERO : MoneyUtil.money(item.amount);
            setCell(row, 6, item == null ? null : amount);
            setCell(row, 7, "");
            total = total.add(amount);
        }
        int totalRow = 14 + Math.max(0, neededRows - 1);
        setCell(sheet, "E" + (totalRow + 1), total);
        setCell(sheet, "G" + (totalRow + 1), total);
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
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
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

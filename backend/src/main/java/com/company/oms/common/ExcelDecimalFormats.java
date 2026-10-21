package com.company.oms.common;

import org.apache.poi.ss.usermodel.*;
import java.util.HashMap;
import java.util.Map;

/** Preserve stored numeric values and show significant decimal places instead of fixed cent padding. */
public final class ExcelDecimalFormats {
    private ExcelDecimalFormats() {}
    public static void apply(Workbook workbook) {
        Map<Short, CellStyle> styles = new HashMap<>();
        short format = workbook.createDataFormat().getFormat("#,##0.##############################");
        for (Sheet sheet : workbook) for (Row row : sheet) for (Cell cell : row) {
            if (cell.getCellType() != CellType.NUMERIC && cell.getCellType() != CellType.FORMULA) continue;
            CellStyle old = cell.getCellStyle();
            if (DateUtil.isADateFormat(old.getDataFormat(), old.getDataFormatString())
                    || old.getDataFormatString().contains("%")) continue;
            CellStyle style = styles.computeIfAbsent(old.getIndex(), ignored -> {
                CellStyle result = workbook.createCellStyle(); result.cloneStyleFrom(old);
                result.setDataFormat(format); return result;
            });
            cell.setCellStyle(style);
        }
    }
}

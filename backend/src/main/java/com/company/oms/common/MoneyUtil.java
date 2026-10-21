package com.company.oms.common;

import java.math.BigDecimal;

public final class MoneyUtil {
    private MoneyUtil() {}

    public static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static String display(BigDecimal value) {
        String[] parts = nvl(value).stripTrailingZeros().toPlainString().split("\\.", -1);
        String integer = parts[0].replaceAll("(\\d)(?=(\\d{3})+$)", "$1,");
        return integer + (parts.length > 1 ? "." + parts[1] : "");
    }

    public static BigDecimal money(BigDecimal value) {
        return nvl(value);
    }
}

package com.company.oms.masterdata;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProductSeedRunner implements CommandLineRunner {
    private final ProductRepository productRepository;

    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("A", "PP编织绳"), Map.entry("B", "PP编织绳+提管"), Map.entry("C", "PP扁提绳"),
            Map.entry("D", "PE4股扭花提绳-双头扣"), Map.entry("E", "PE4股扭花提绳+提手柄"),
            Map.entry("F", "PE锁扣"), Map.entry("G", "PP提绳管"), Map.entry("H", "塑料封底"),
            Map.entry("I", "塑料护套"), Map.entry("J", "滚轮"), Map.entry("K", "地插"),
            Map.entry("L", "连体饵料球"), Map.entry("M", "分离饵料球"), Map.entry("N", "塑料桶"),
            Map.entry("P", "橡胶圈"), Map.entry("Q", "EVA")
    );

    // Enterprise catalog records are intentionally excluded from this repository.
    private static final Map<String, String> PRODUCT_NAME_OVERRIDES = Map.of();
    private static final String[][] SEEDS = new String[0][];

    public ProductSeedRunner(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public void run(String... args) {
        Map<String, Product> existingByCode = new HashMap<>();
        productRepository.findAll().stream()
                .filter(product -> product.code != null && !product.code.isBlank())
                .forEach(product -> existingByCode.put(product.code, product));
        List<Product> changedProducts = new ArrayList<>();
        for (String[] row : SEEDS) {
            Product existing = existingByCode.get(row[2]);
            if (existing != null) {
                if (fillSeedMetadata(existing, seedName(row))) changedProducts.add(existing);
                continue;
            }
            Product product = new Product();
            product.categoryCode = row[0];
            product.categoryName = CATEGORY_NAMES.get(row[0]);
            product.name = seedName(row);
            product.code = row[2];
            product.spec = inferSpec(row[0], row[2]);
            product.unit = "条";
            product.defaultPrice = BigDecimal.ZERO;
            product.costPrice = BigDecimal.ZERO;
            product.safetyStock = BigDecimal.ZERO;
            product.enabled = true;
            product.status = "enabled";
            product.tagValues = inferTagValues(row[0], row[2]);
            changedProducts.add(product);
        }
        if (!changedProducts.isEmpty()) productRepository.saveAll(changedProducts);
    }

    private String seedName(String[] row) {
        return PRODUCT_NAME_OVERRIDES.getOrDefault(row[2], row[1]);
    }

    private boolean fillSeedMetadata(Product product, String seedName) {
        boolean changed = false;
        if (!isBlank(seedName) && !seedName.equals(product.name)) {
            product.name = seedName;
            changed = true;
        }
        if (isBlank(product.categoryName) && !isBlank(product.categoryCode)) {
            product.categoryName = CATEGORY_NAMES.get(product.categoryCode);
            changed = true;
        }
        if (isBlank(product.spec) && !isBlank(product.categoryCode) && !isBlank(product.code)) {
            product.spec = inferSpec(product.categoryCode, product.code);
            changed = true;
        }
        if (isBlank(product.tagValues) || "{}".equals(product.tagValues)) {
            product.tagValues = inferTagValues(product.categoryCode, product.code);
            product.spec = inferSpec(product.categoryCode, product.code);
            changed = true;
        }
        if (isBlank(product.status)) {
            product.status = Boolean.FALSE.equals(product.enabled) ? "disabled" : "enabled";
            changed = true;
        }
        return changed;
    }

    private String inferSpec(String categoryCode, String code) {
        String[] parts = split(code);
        if (parts.length == 0) return "";
        return switch (categoryCode) {
            case "A", "Q" -> part(parts, parts.length - 1);
            case "B" -> join(parts, 2);
            case "C" -> join(parts, 2);
            case "D" -> join(parts, 2);
            case "E" -> join(parts, 2);
            case "F" -> join(parts, 2);
            case "G", "K", "L", "M", "P" -> part(parts, 2);
            case "H", "I" -> part(parts, 3) + "管";
            case "J", "N" -> part(parts, 3);
            default -> join(parts, 2);
        };
    }

    private String inferTagValues(String categoryCode, String code) {
        Map<String, Object> values = new LinkedHashMap<>();
        String[] parts = split(code);
        switch (categoryCode) {
            case "A" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "length_code", part(parts, 2));
            }
            case "B" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "length_code", part(parts, 2));
                put(values, "combine_code", part(parts, 3));
                put(values, "tube_spec", join(parts, 4));
            }
            case "C" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "length_code", part(parts, 2));
                put(values, "width_code", part(parts, 3));
                put(values, "head_code", part(parts, 4));
                values.put("has_handle", contains(parts, "T"));
                int yIndex = indexOf(parts, "Y");
                values.put("is_print", yIndex >= 0);
                if (yIndex >= 0) put(values, "print_label", part(parts, yIndex + 1));
                String colorNo = trailingColorNo(parts, yIndex);
                if (!isBlank(colorNo)) put(values, "color_no", colorNo);
            }
            case "D" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "length_code", part(parts, 2));
                put(values, "diameter", part(parts, 3));
                put(values, "head_code", part(parts, 4));
            }
            case "E" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "length_code", part(parts, 2));
                put(values, "diameter", part(parts, 3));
                put(values, "handle_code", part(parts, 4));
                put(values, "color_no", part(parts, 5));
            }
            case "F" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "size_code", part(parts, 2));
                put(values, "lock_spec", part(parts, 3));
            }
            case "G", "L", "M" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
            }
            case "H", "I" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
                put(values, "pipe_spec", part(parts, 3));
            }
            case "J" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
                put(values, "roller_spec", part(parts, 3));
            }
            case "K", "P" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
            }
            case "N" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
                put(values, "bucket_spec", part(parts, 3));
            }
            case "Q" -> {
                put(values, "color_code", part(parts, 1));
                put(values, "item_code", part(parts, 2));
                put(values, "model", join(parts, 3));
                values.put("legacy_code_mode", code != null && code.startsWith("RP-"));
            }
            default -> {
            }
        }
        return toJson(values);
    }

    private String[] split(String code) {
        String[] parts = code == null ? new String[0] : code.split("-");
        if (parts.length > 0 && parts[0].matches("R[A-Z]")) {
            parts[0] = parts[0].substring(1);
        }
        return parts;
    }

    private String join(String[] parts, int startInclusive) {
        if (parts.length <= startInclusive) return "";
        return String.join("-", java.util.Arrays.copyOfRange(parts, startInclusive, parts.length));
    }

    private String part(String[] parts, int index) {
        return index >= 0 && index < parts.length ? parts[index] : "";
    }

    private void put(Map<String, Object> values, String key, String value) {
        if (!isBlank(value)) values.put(key, value);
    }

    private boolean contains(String[] parts, String value) {
        return indexOf(parts, value) >= 0;
    }

    private int indexOf(String[] parts, String value) {
        for (int i = 0; i < parts.length; i++) {
            if (value.equals(parts[i])) return i;
        }
        return -1;
    }

    private String trailingColorNo(String[] parts, int yIndex) {
        if (parts.length <= 5) return "";
        String last = part(parts, parts.length - 1);
        if ("T".equals(last)) return "";
        if (yIndex >= 0 && parts.length - 1 <= yIndex + 1) return "";
        return last;
    }

    private String toJson(Map<String, Object> values) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(escape(entry.getKey())).append("\":");
            if (entry.getValue() instanceof Boolean) {
                json.append(entry.getValue());
            } else {
                json.append("\"").append(escape(String.valueOf(entry.getValue()))).append("\"");
            }
        }
        json.append("}");
        return json.toString();
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

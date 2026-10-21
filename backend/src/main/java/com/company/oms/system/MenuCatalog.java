package com.company.oms.system;

import org.springframework.stereotype.Component;
import java.util.*;

/** Register each new sidebar page here; ALL_BUSINESS accounts automatically receive it. */
@Component
public class MenuCatalog {
    public record Entry(String code, String group, String label, String path, boolean business, String parentCode) {
        public Entry(String code, String group, String label, String path, boolean business) {
            this(code, group, label, path, business, null);
        }
    }
    private final List<Entry> entries = List.of(
        new Entry("dashboard", "", "首页", "/dashboard", true),
        new Entry("customers", "基础资料", "客户档案", "/customers", true),
        new Entry("products", "基础资料", "产品资料", "/products", true),
        new Entry("materials", "基础资料", "原料库存", "/materials", true),
        new Entry("material-entry", "库存操作", "原料出入库登记", "/worker/material-entry", true),
        new Entry("material-entry-out", "库存操作", "出库登记", "", true, "material-entry"),
        new Entry("material-entry-in", "库存操作", "入库登记", "", true, "material-entry"),
        new Entry("quotations", "销售管理", "报价管理", "/quotations", true),
        new Entry("orders", "销售管理", "销售订单", "/orders", true),
        new Entry("deliveries", "销售管理", "发货管理", "/deliveries", true),
        new Entry("statements", "财务管理", "对账单", "/statements", true),
        new Entry("invoices", "财务管理", "对账已开票", "/invoices", true),
        new Entry("receipts", "财务管理", "收款登记", "/receipts", true),
        new Entry("users", "", "用户管理", "/users", false)
    );
    public List<Entry> entries() { return entries; }
    public Set<String> effective(SysUser user) { return effective(user, entries); }
    /** Legacy combined grants retain both directions until an administrator edits them. */
    public Set<String> editableCodes(Set<String> codes) {
        Set<String> result = new LinkedHashSet<>(codes);
        if (result.remove("material-entry")) {
            result.add("material-entry-out");
            result.add("material-entry-in");
        }
        return result;
    }
    public Set<String> effective(SysUser user, List<Entry> catalog) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> assigned = editableCodes(user.menuCodes);
        for (Entry entry : catalog) {
            if (entry.code().equals("material-entry")) continue;
            if (Boolean.TRUE.equals(user.owner) || assigned.contains(entry.code())
                    || (entry.business() && "ALL_BUSINESS".equals(user.permissionMode))) result.add(entry.code());
        }
        if (result.contains("material-entry-out") || result.contains("material-entry-in")) result.add("material-entry");
        return result;
    }
    public void validate(Set<String> codes) {
        Set<String> known = new HashSet<>();
        entries.forEach(entry -> known.add(entry.code()));
        if (!known.containsAll(codes)) throw new com.company.oms.common.BusinessException("包含未知菜单权限");
    }
}

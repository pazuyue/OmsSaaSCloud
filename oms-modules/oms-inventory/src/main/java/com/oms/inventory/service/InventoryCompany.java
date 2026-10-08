package com.oms.inventory.service;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;

/** Inventory UI scope comes from the authenticated session, never from a row submitted by the browser. */
public final class InventoryCompany {
    private InventoryCompany() { }

    public static String current() {
        LoginUser user = SecurityUtils.getLoginUser();
        String code = user == null ? null : user.getCompanyCode();
        if ((code == null || code.trim().isEmpty()) && user != null && user.getSysUser() != null) {
            code = user.getSysUser().getLoginCompanyCode();
        }
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("请先选择登录公司");
        // Legacy local data contains both QM and qm. Codes are canonicalized on all new writes.
        return code.trim().toUpperCase(java.util.Locale.ROOT);
    }
}

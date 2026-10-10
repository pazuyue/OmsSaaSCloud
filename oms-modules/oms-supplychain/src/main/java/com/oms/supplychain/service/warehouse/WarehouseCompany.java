package com.oms.supplychain.service.warehouse;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import java.util.Locale;

public final class WarehouseCompany {
    private WarehouseCompany() {}
    public static String current() {
        LoginUser user=SecurityUtils.getLoginUser();
        String company=user==null?null:user.getCompanyCode();
        if((company==null || company.trim().isEmpty()) && user!=null && user.getSysUser()!=null) company=user.getSysUser().getLoginCompanyCode();
        if(company==null || company.trim().isEmpty()) throw new IllegalArgumentException("请先选择登录公司");
        return company.trim().toUpperCase(Locale.ROOT);
    }
    public static String check(String supplied) {
        String company=current();
        if(supplied!=null && !company.equalsIgnoreCase(supplied.trim())) throw new IllegalArgumentException("不能操作其他公司的仓库资料");
        return company;
    }
}

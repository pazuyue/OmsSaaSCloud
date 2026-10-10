package com.oms.goods.service.goods;

import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import java.util.Locale;

/** The browser cannot choose the owner of a master record or import batch. */
public final class GoodsCompany {
    private GoodsCompany() { }
    public static String current() {
        LoginUser user = SecurityUtils.getLoginUser();
        String code = user == null ? null : user.getCompanyCode();
        if ((code == null || code.trim().isEmpty()) && user != null && user.getSysUser() != null)
            code = user.getSysUser().getLoginCompanyCode();
        if (code == null || code.trim().isEmpty()) throw new IllegalArgumentException("请先选择登录公司");
        return code.trim().toUpperCase(Locale.ROOT);
    }
    public static String check(String supplied) {
        String current = current();
        if (supplied != null && !supplied.trim().isEmpty() && !current.equalsIgnoreCase(supplied.trim()))
            throw new IllegalArgumentException("不能操作其他公司的商品资料");
        return current;
    }
}

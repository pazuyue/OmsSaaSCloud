package com.oms.goods.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum GoodsPluginEnum {

    HmOrderTransfer(Type.HM, "HM商品处理"),
    CommonOrderTransfer(Type.Common, "通用商品处理");

    public String orderPluginType;
    public String remark;

    public static class Type {
        public static final String HM = "hm";
        public static final String Common = "common";
    }
}

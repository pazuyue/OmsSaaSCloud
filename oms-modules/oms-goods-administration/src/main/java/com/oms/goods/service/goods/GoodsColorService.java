package com.oms.goods.service.goods;

public interface GoodsColorService {

    /**
     * 根据颜色名称返回颜色CODE，如果不存在，新增
     * @param color_name
     * @return
     */
    public Integer selectOrSaveByColorName(String color_name,String company_code);
}

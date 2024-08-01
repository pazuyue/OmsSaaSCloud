package com.oms.goods.service.plugin;

import com.oms.goods.model.vo.export.GoodsVO;

import java.util.List;

public interface GoodsPluginService {
    boolean execute(); //插件实现

    /**
     * 导入
     * @param list
     * @return
     */
    String export(List<GoodsVO> list);
    /**
     * 重新导入
     * @param list
     * @param importBatch
     * @return
     */
    String export(List<GoodsVO> list,String importBatch);
}

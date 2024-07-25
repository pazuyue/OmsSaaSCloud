package com.oms.goods.service.plugin;

import com.oms.goods.model.vo.export.GoodsVO;

import java.util.List;

public interface GoodsPluginService {
    boolean execute(); //插件实现
    boolean export(List<GoodsVO> list);
}

package com.oms.goods.service.goods.impl;

import com.oms.goods.model.enums.GoodsPluginEnum;
import com.oms.goods.model.vo.export.GoodsVO;
import com.oms.goods.service.goods.GoodsSkuSnInfoTmpService;
import com.oms.goods.service.plugin.GoodsPluginService;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

@Service(GoodsPluginEnum.Type.Common)
public class GoodsPluginServiceImpl implements GoodsPluginService {

    @Resource
    private GoodsSkuSnInfoTmpService goodsSkuSnInfoTmpService;
    @Override
    public boolean execute() {
        return false;
    }

    @Override
    public boolean export(List<GoodsVO> list) {
        return  goodsSkuSnInfoTmpService.export(list);
    }
}

package com.oms.goods.controller.api.goods;

import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 产品信息-远程服务接口Controller
 *
 * @author ruoyi
 * @date 2024-08-01
 */
@RestController
@RequestMapping("/api/goods")
public class RemoteGoodsSkuSnInfoController extends BaseController {

    @Resource
    private GoodsSkuSnInfoService goodsSkuSnInfoService;
    @PostMapping("/getOne")
    public AjaxResult getOne(GoodsSkuSnInfo goodsSkuSnInfo)
    {
        return success(goodsSkuSnInfoService.selectGoodsSkuSnInfo(goodsSkuSnInfo));
    }
}

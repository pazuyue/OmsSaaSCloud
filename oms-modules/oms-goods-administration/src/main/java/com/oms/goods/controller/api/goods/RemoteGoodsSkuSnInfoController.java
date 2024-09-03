package com.oms.goods.controller.api.goods;

import com.oms.goods.model.entity.goods.GoodsSkuSnInfo;
import com.oms.goods.service.goods.GoodsSkuSnInfoService;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * 产品信息-远程服务接口Controller
 *
 * @author ruoyi
 * @date 2024-08-01
 */
@Slf4j
@RestController
@RequestMapping("/api/goods")
public class RemoteGoodsSkuSnInfoController extends BaseController {

    @Resource
    private GoodsSkuSnInfoService goodsSkuSnInfoService;
    @PostMapping("/getOne")
    public AjaxResult getOne(@RequestBody GoodsSkuSnInfo goodsSkuSnInfo)
    {
        log.debug("getOne"+goodsSkuSnInfo.toString());
        return success(goodsSkuSnInfoService.selectGoodsSkuSnInfo(goodsSkuSnInfo));
    }
}

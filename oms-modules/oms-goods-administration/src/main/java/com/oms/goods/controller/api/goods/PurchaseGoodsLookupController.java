package com.oms.goods.controller.api.goods;

import com.oms.goods.service.goods.impl.GoodsWorkspaceService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;

@RestController
public class PurchaseGoodsLookupController {
    @Resource private GoodsWorkspaceService workspace;
    @InnerAuth
    @PostMapping("/api/goods/purchaseLookup")
    public R<Map<String,Object>> lookup(@RequestBody Map<String,Object> filter,@RequestParam("company_code") String company){
        String sku=Objects.toString(filter.get("skuSn"),"").trim();
        if(company.trim().isEmpty()||sku.isEmpty())return R.fail("公司及 SKU 不能为空");
        List<Map<String,Object>> rows=workspace.goodsExport(company.trim().toUpperCase(Locale.ROOT),Collections.singletonMap("skuSn",sku));
        return R.ok(rows.isEmpty()?null:rows.get(0));
    }
}

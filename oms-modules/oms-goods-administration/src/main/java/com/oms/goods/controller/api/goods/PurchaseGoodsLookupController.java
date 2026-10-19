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
    @Resource private com.oms.goods.service.goods.impl.GoodsBundleService bundles;
    @InnerAuth
    @PostMapping("/api/goods/purchaseLookup")
    public R<Map<String,Object>> lookup(@RequestBody Map<String,Object> filter,@RequestParam("company_code") String company){
        String sku=Objects.toString(filter.get("skuSn"),"").trim();
        if(company.trim().isEmpty()||sku.isEmpty())return R.fail("公司及 SKU 不能为空");
        List<Map<String,Object>> rows=workspace.goodsExport(company.trim().toUpperCase(Locale.ROOT),Collections.singletonMap("skuSn",sku));
        if(!rows.isEmpty()&&Integer.valueOf(1).equals(rows.get(0).get("isPackage"))){Map<String,Object> kit=bundles.detail(company.trim().toUpperCase(Locale.ROOT),Long.parseLong(rows.get(0).get("id").toString()));if(!"STOCKED".equals(kit.get("inventory_mode"))||!Integer.valueOf(1).equals(kit.get("enabled")))return R.fail("请选择已启用的实物套装或实际组件SKU");}
        return R.ok(rows.isEmpty()?null:rows.get(0));
    }
}

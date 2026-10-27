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
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @InnerAuth @PostMapping("/api/goods/purchaseLookupBatch")
    public R<List<Map<String,Object>>> batch(@RequestBody List<String> skus,@RequestParam("company_code") String company){
        if(company.trim().isEmpty()||skus==null||skus.isEmpty()||skus.size()>200)return R.fail("一次须查询1至200个采购SKU");
        String c=company.trim().toUpperCase(Locale.ROOT);for(String sku:skus)if(sku==null||sku.isEmpty()||sku.length()>100)return R.fail("采购SKU无效");
        List<Object> args=new ArrayList<>();args.add(c);args.addAll(skus);
        List<Map<String,Object>> found=jdbc.queryForList("SELECT id,sku_sn,goods_name,goods_sn,barcode_sn,is_package FROM goods_sku_sn_info WHERE UPPER(company_code)=? AND sku_sn IN ("+String.join(",",Collections.nCopies(skus.size(),"?"))+")",args.toArray());
        bundles.attach(c,found);
        for(Map<String,Object> row:found){boolean valid=true;if(row.get("is_package")!=null&&Integer.parseInt(row.get("is_package").toString())==1){Object raw=row.get("bundle");Map<?,?> kit=raw instanceof Map?(Map<?,?>)raw:Collections.emptyMap();valid="STOCKED".equals(kit.get("inventory_mode"))&&"1".equals(String.valueOf(kit.get("enabled")));}row.put("stock_unit",row.get("is_package")!=null&&"1".equals(row.get("is_package").toString())?"套":"件");row.put("purchase_valid",valid);row.remove("bundle");}
        return R.ok(found);
    }
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

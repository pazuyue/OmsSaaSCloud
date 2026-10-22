package com.oms.goods.controller;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@RestController @RequestMapping("/orderCatalog/internal")
public class OrderGoodsCatalogController {
    @Resource private JdbcTemplate jdbc;
    @Resource private com.oms.goods.service.goods.impl.GoodsBundleService bundles;
    @Resource private com.oms.goods.service.goods.impl.GoodsLuckyBagService luckyBags;
    @Resource private com.oms.goods.service.goods.impl.GoodsWorkspaceService workspace;
    @Resource private com.oms.goods.service.goods.impl.GoodsGiftProtection giftProtection;
    @InnerAuth @PostMapping("/search") public R<Map<String,Object>> search(@RequestParam("company_code") String company,@RequestBody Map<String,Object> query){
        String field=String.valueOf(query.getOrDefault("field","skuSn")),term=String.valueOf(query.getOrDefault("keyword","")).trim();
        if(!Arrays.asList("skuSn","goodsSn","barcodeSn","goodsName").contains(field)||term.length()>128)throw new IllegalArgumentException("商品搜索条件无效");
        Map<String,Object> result=new LinkedHashMap<>();if(term.isEmpty()){result.put("rows",Collections.emptyList());result.put("total",0);return R.ok(result);}
        int page=Math.max(1,Math.min(10000,Integer.parseInt(String.valueOf(query.getOrDefault("pageNum",1))))),size=Math.max(1,Math.min(20,Integer.parseInt(String.valueOf(query.getOrDefault("pageSize",10)))));
        String use=String.valueOf(query.getOrDefault("use","PURCHASE"));if(!Arrays.asList("PURCHASE","GIFT").contains(use))throw new IllegalArgumentException("商品用途无效");Map<String,Object> filter=new HashMap<>();filter.put(field,term);if("GIFT".equals(use))filter.put("giftEligible",true);
        com.ruoyi.common.core.web.page.TableDataInfo found=workspace.goodsPage(company.trim().toUpperCase(Locale.ROOT),filter,page,size);result.put("rows",found.getRows());result.put("total",found.getTotal());return R.ok(result);
    }
    @InnerAuth @PostMapping("/gift-protection") public R<Boolean> protect(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){return R.ok(giftProtection.protect(company.trim().toUpperCase(Locale.ROOT),input));}
    @InnerAuth @PostMapping("/gift-protection/refresh") public R<Boolean> refresh(@RequestParam("company_code") String company,@RequestBody Map<String,Object> input){return R.ok(giftProtection.refresh(company.trim().toUpperCase(Locale.ROOT),Long.parseLong(String.valueOf(input.get("group_id")))));}
    @InnerAuth @GetMapping("/lucky-skus") public R<List<String>> luckySkus(@RequestParam("company_code") String company,@RequestParam("after") String after){if(after.length()>50)throw new IllegalArgumentException("SKU游标无效");return R.ok(jdbc.queryForList("SELECT sku_sn FROM goods_lucky_bag WHERE company_code=? AND revision>0 AND sku_sn>? ORDER BY sku_sn LIMIT 500",String.class,company.trim().toUpperCase(Locale.ROOT),after));}
    @InnerAuth @PostMapping("/goods")
    public R<List<Map<String,Object>>> goods(@RequestParam("company_code") String company,@RequestBody List<String> skus) {
        if(skus==null||skus.size()>500)throw new IllegalArgumentException("单次商品查询最多500个SKU");
        if(skus.isEmpty())return R.ok(Collections.emptyList());
        List<Object> args=new ArrayList<>();args.add(company);args.addAll(skus);
        List<Map<String,Object>> found=jdbc.queryForList("SELECT g.id,g.sku_sn,g.goods_sn,g.barcode_sn,g.goods_name,g.is_fd,g.is_package,g.is_gift,c.color_name,z.size_name FROM goods_sku_sn_info g LEFT JOIN goods_color c ON c.id=g.color_code AND UPPER(c.company_code)=UPPER(g.company_code) LEFT JOIN goods_size z ON z.id=g.size_code AND UPPER(z.company_code)=UPPER(g.company_code) WHERE UPPER(g.company_code)=UPPER(?) AND g.sku_sn IN ("+String.join(",",Collections.nCopies(skus.size(),"?"))+")",args.toArray());bundles.attach(company.trim().toUpperCase(Locale.ROOT),found);luckyBags.attach(company.trim().toUpperCase(Locale.ROOT),found);return R.ok(found);
    }
}

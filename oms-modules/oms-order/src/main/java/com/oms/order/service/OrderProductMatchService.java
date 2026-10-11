package com.oms.order.service;

import com.oms.common.api.OrderStockClient;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Per-order correction only. The shop lease excludes workers; no remote call holds a DB transaction. */
@Service
public class OrderProductMatchService {
    @Resource private OrderRepository repository;
    @Resource private OrderCatalog catalog;
    @Resource private OrderStockClient inventory;
    public static final Set<String> MATCHABLE=new HashSet<>(Arrays.asList("OUTER_SKU_MISSING","SKU_NOT_FOUND","SKU_AMBIGUOUS"));
    public void match(String company,long id,Map<String,Object> input,String actor){
        String reason=text(input,"reason").trim(),expected=text(input,"processing_hash");
        require(!reason.isEmpty()&&reason.length()<=200,"请填写200字以内的匹配原因");
        require(expected.matches("[a-f0-9]{64}"),"订单快照无效，请刷新详情");
        Object supplied=input.get("lines");require(supplied instanceof List,"请选择需要匹配的异常明细");
        List<?> lines=(List<?>)supplied;require(!lines.isEmpty()&&lines.size()<=500,"请选择1至500条异常明细");
        Map<String,String> selections=new LinkedHashMap<>();
        for(Object value:lines){require(value instanceof Map,"匹配明细格式无效");Map<String,Object> line=(Map<String,Object>)value;String oid=text(line,"platform_oid"),sku=text(line,"sku_sn");require(oid.matches("[0-9]{1,64}")&&!sku.trim().isEmpty()&&sku.equals(sku.trim())&&sku.length()<=128,"请选择有效的OMS SKU");require(selections.put(oid,sku)==null,"不可重复提交同一条明细");}
        Map<String,Object> original=repository.raw(company,id);long channel=number(original,"channel_id");String lease=token();
        require(repository.claim(company,channel,lease),"店铺正在处理，请稍后重新提交");
        try{
            Map<String,Object> order=repository.raw(company,id);validateOrder(order,expected);
            Map<String,List<Map<String,Object>>> goods=catalog.goods(company,new ArrayList<>(new LinkedHashSet<>(selections.values())));
            for(String sku:selections.values()){List<Map<String,Object>> found=goods.getOrDefault(sku,Collections.emptyList());require(found.size()==1,"所选OMS SKU不存在或不唯一："+sku);require(number(found.get(0),"is_fd")==0&&number(found.get(0),"is_package")==0,"一期不能匹配套装或福袋："+sku);}
            Set<String> registered=new HashSet<>();for(Map<String,Object> demand:data(inventory.demands(company,channel,text(order,"platform_tid"),SecurityConstants.INNER)))registered.add(text(demand,"platform_oid"));
            for(String oid:selections.keySet())require(!registered.contains(oid),"子订单 "+oid+" 已登记库存需求，不能直接换SKU");
            TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
            tx.execute(status->{
                require(repository.db().queryForList("SELECT id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? AND lease_token=? AND lease_until>NOW() FOR UPDATE",company,channel,lease).size()==1,"处理租约已失效，请刷新后重试");
                Map<String,Object> current=repository.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);validateOrder(current,expected);
                Map<String,String> eligible=eligibleErrors(current);
                Map<String,Map<String,Object>> byOid=new HashMap<>();for(Map<String,Object> line:repository.db().query("SELECT * FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? FOR UPDATE",ROW,company,id))byOid.put(text(line,"platform_oid"),line);
                for(Map.Entry<String,String> entry:selections.entrySet()){
                    String oid=entry.getKey(),sku=entry.getValue();Map<String,Object> line=byOid.get(oid);
                    require(line!=null&&eligible.containsKey(oid),"仅允许处理商家编码缺失、SKU不存在或不唯一的异常明细");
                    require(number(line,"demand_prepared")==0&&text(line,"sku_sn").isEmpty(),"子订单 "+oid+" 已开始登记需求，不能直接换SKU");
                    repository.db().update("UPDATE oms_platform_order_line SET manual_sku_sn=?,manual_match_by=?,manual_match_at=NOW(),manual_match_reason=? WHERE company_code=? AND platform_order_id=? AND platform_oid=?",sku,actor,reason,company,id,oid);
                    repository.log(company,id,"MATCH_PRODUCT","SUCCESS","子订单 "+oid+"；平台编码："+text(line,"outer_sku_id")+"；原人工SKU："+text(line,"manual_sku_sn")+"；选择OMS SKU："+sku+"；原因："+reason,actor);
                }
                repository.db().update("UPDATE oms_platform_order SET conversion_status='PENDING',force_convert=1,retry_count=retry_count+1,system_failures=0,next_retry_at=NOW() WHERE company_code=? AND id=?",company,id);
                repository.log(company,id,"RETRY","PENDING","人工匹配已保存，已提交原订单转单重试",actor);return null;
            });
        }finally{repository.release(company,channel,lease);}
    }
    private void validateOrder(Map<String,Object> order,String expected){
        require(order.get("business_order_id")==null&&"EXCEPTION".equals(text(order,"conversion_status")),"仅可匹配尚未转单成功的异常订单，请刷新");
        require(number(order,"change_pending")==0,"平台业务数据已变化，待订单更新处理");
        require(expected.equals(text(order,"processing_hash")),"订单快照已变化，请刷新后重新匹配");
        require("WAIT_SELLER_SEND_GOODS".equals(text(order,"platform_status")),"订单未满足已付款待发货条件");
    }
    public static Map<String,String> eligibleErrors(Map<String,Object> order){
        Map<String,String> result=new HashMap<>();if(!"PRODUCT_INVALID".equals(text(order,"exception_code")))return result;
        try{Object details=order.get("exception_details");List<?> items=details instanceof List?(List<?>)details:JSON.readValue(text(order,"exception_details"),List.class);for(Object value:items){Map<String,Object> item=(Map<String,Object>)value;String code=text(item,"code");if(MATCHABLE.contains(code))result.put(text(item,"platform_oid"),code);}}catch(Exception e){throw new IllegalArgumentException("异常明细无效，请刷新后重试");}return result;
    }
}

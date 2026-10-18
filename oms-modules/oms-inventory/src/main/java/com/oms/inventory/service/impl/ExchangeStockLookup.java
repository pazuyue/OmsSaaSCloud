package com.oms.inventory.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.util.*;

/** Read-only, bounded page lookup. Capacity is an upper bound, never a reservation promise. */
@Service public class ExchangeStockLookup {
    @Resource private JdbcTemplate jdbc;
    private static long n(Map<String,Object> row,String key){Object v=row.get(key);return v==null?0:Long.parseLong(v.toString());}
    private static String marks(Collection<?> list){return String.join(",",Collections.nCopies(list.size(),"?"));}
    @SuppressWarnings("unchecked") @Transactional(readOnly=true,timeout=10,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> lookup(String company,Map<String,Object> command){
        Object raw=command.get("skus"),scope=command.get("stores");if(!(raw instanceof List)||((List<?>)raw).size()>20||!(scope instanceof List)||((List<?>)scope).isEmpty()||((List<?>)scope).size()>100)throw new IllegalArgumentException("库存查询范围无效");
        List<String> skus=new ArrayList<>(),stores=new ArrayList<>();for(Object v:(List<?>)raw){if(!(v instanceof String)||v.toString().isEmpty()||v.toString().length()>128)throw new IllegalArgumentException("SKU无效");if(!skus.contains(v))skus.add((String)v);}for(Object v:(List<?>)scope){if(!(v instanceof String)||v.toString().isEmpty()||v.toString().length()>64)throw new IllegalArgumentException("允许仓无效");if(!stores.contains(v))stores.add((String)v);}if(skus.isEmpty())return Collections.emptyList();
        long channel=n(command,"channel_id");if(channel<=0)throw new IllegalArgumentException("店铺无效");String reference=String.valueOf(command.getOrDefault("reference",""));if(!reference.isEmpty()&&!(reference.startsWith("EXCHANGE:")&&com.oms.common.util.FulfillmentReference.valid(reference)))throw new IllegalArgumentException("本单库存引用无效");
        List<Object> args=new ArrayList<>(Arrays.asList(company));args.addAll(skus);
        Map<String,Long> logical=new HashMap<>(),physical=new HashMap<>(),locked=new HashMap<>(),own=new HashMap<>(),ownWarehouse=new HashMap<>();
        for(Map<String,Object> r:jdbc.queryForList("SELECT sku_sn,available_stock FROM oms_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus)+")",args.toArray()))logical.put(r.get("sku_sn").toString(),n(r,"available_stock"));
        List<Object> scoped=new ArrayList<>(args);scoped.addAll(stores);
        for(Map<String,Object> r:jdbc.queryForList("SELECT sku_sn,SUM(zp_available_number) qty FROM wms_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus)+") AND store_code IN ("+marks(stores)+") GROUP BY sku_sn",scoped.toArray()))physical.put(r.get("sku_sn").toString(),n(r,"qty"));
        List<Object> lockArgs=new ArrayList<>(Arrays.asList(company,channel));lockArgs.addAll(skus);lockArgs.addAll(stores);
        for(Map<String,Object> r:jdbc.queryForList("SELECT s.sku_sn,SUM(GREATEST(0,s.original_quantity-s.occupied_quantity-s.consumed_quantity-s.released_quantity)) qty FROM rule_stock_reservation s JOIN wms_inventory_batch b ON b.id=s.batch_id AND b.company_code=s.company_code AND b.sku_sn=s.sku_sn JOIN rule_stock_result r ON r.company_code=s.company_code AND r.rule_id=s.rule_id AND r.sku_sn=s.sku_sn JOIN rule_stock_info h ON h.company_code=s.company_code AND h.id=s.rule_id WHERE s.company_code=? AND s.channel_id=? AND s.sku_sn IN ("+marks(skus)+") AND b.store_code IN ("+marks(stores)+") AND r.status='SUCCESS' AND r.release_status='NONE' AND h.run_action<>'RELEASE' GROUP BY s.sku_sn",lockArgs.toArray()))locked.put(r.get("sku_sn").toString(),n(r,"qty"));
        if(!reference.isEmpty()){
            List<Object> ownArgs=new ArrayList<>(Arrays.asList(company,channel,reference));ownArgs.addAll(skus);
            for(Map<String,Object> r:jdbc.queryForList("SELECT sku_sn,SUM(GREATEST(0,reserved_quantity-outbound_quantity)) qty FROM oms_order_stock_demand WHERE company_code=? AND channel_id=? AND platform_tid=? AND sku_sn IN ("+marks(skus)+") AND status<>'CANCELLED' GROUP BY sku_sn",ownArgs.toArray()))own.put(r.get("sku_sn").toString(),n(r,"qty"));
            ownArgs.addAll(stores);for(Map<String,Object> r:jdbc.queryForList("SELECT sku_sn,SUM(GREATEST(0,quantity-released_quantity-outbound_quantity)) qty FROM oms_order_warehouse_allocation WHERE company_code=? AND channel_id=? AND platform_tid=? AND sku_sn IN ("+marks(skus)+") AND store_code IN ("+marks(stores)+") AND status='OCCUPIED' GROUP BY sku_sn",ownArgs.toArray()))ownWarehouse.put(r.get("sku_sn").toString(),n(r,"qty"));
        }
        List<Map<String,Object>> result=new ArrayList<>();for(String sku:skus){long free=Math.max(0,logical.getOrDefault(sku,0L)),stock=Math.max(0,physical.getOrDefault(sku,0L)),lock=locked.getOrDefault(sku,0L),held=own.getOrDefault(sku,0L);long logicalCapacity=lock+(n(command,"allow_public_stock")==1?free:0)+held,warehouseCapacity=stock+lock+ownWarehouse.getOrDefault(sku,0L);Map<String,Object> row=new LinkedHashMap<>();row.put("sku_sn",sku);row.put("company_available",free);row.put("allowed_warehouse_available",stock);row.put("channel_locked_available",lock);row.put("own_reserved",held);row.put("logical_capacity",logicalCapacity);row.put("reference_capacity",Math.min(logicalCapacity,warehouseCapacity));row.put("observed_at",java.time.LocalDateTime.now().toString());row.put("status","REFERENCE");row.put("note","数量参考不代表预占成功；跨仓、批次及其他待分仓预占以实际分仓校验为准");result.add(row);}return result;
    }
}

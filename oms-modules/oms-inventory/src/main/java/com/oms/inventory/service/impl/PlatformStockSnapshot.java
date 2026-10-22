package com.oms.inventory.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.util.*;
@Service public class PlatformStockSnapshot {
 @Resource JdbcTemplate db;
 @org.springframework.beans.factory.annotation.Autowired(required=false) LuckyBagInventory luckyBags;
 static long n(Map<String,Object> r,String k){Object x=r.get(k);return x==null?0:Long.parseLong(x.toString());}
 static String s(Map<String,Object> r,String k){return r.get(k)==null?"":r.get(k).toString();}
 static void require(boolean ok,String m){if(!ok)throw new IllegalArgumentException(m);}
 @SuppressWarnings("unchecked") @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=15)
 public Map<String,Object> snapshot(String company,Map<String,Object> input){
   if(!s(input,"lucky_sku").isEmpty()){
    require(luckyBags!=null&&input.get("allowed_warehouses") instanceof List,"福袋库存来源或允许仓缺失");
    List<String> stores=new ArrayList<>();for(Object item:(List<?>)input.get("allowed_warehouses"))stores.add(String.valueOf(item));require(stores.size()<=100,"福袋允许仓过多");
    Map<String,Object> result=luckyBags.snapshot(company,s(input,"lucky_sku"),n(input,"channel_id"),stores);if(n(input,"lucky_enabled")==0)result.put("available",0);return result;
   }
  long channel=n(input,"channel_id");require(channel>0&&input.get("components") instanceof List,"库存查询参数无效");List<Map<String,Object>> components=(List<Map<String,Object>>)input.get("components");require(!components.isEmpty()&&components.size()<=50,"组件范围无效");boolean publicStock=Boolean.TRUE.equals(input.get("allow_public_stock"));components=new ArrayList<>(components);components.sort(Comparator.comparing(x->s(x,"sku_sn")));
  long global=Long.MAX_VALUE;Map<String,Long> sets=new TreeMap<>();boolean first=true;List<Map<String,Object>> facts=new ArrayList<>();Set<String> seen=new HashSet<>();
  for(Map<String,Object> part:components){String sku=s(part,"sku_sn");long coefficient=n(part,"quantity");require(!sku.isEmpty()&&sku.length()<=128&&seen.add(sku)&&coefficient>0,"商品或配比无效");
   require(db.queryForObject("SELECT COUNT(*) FROM oms_channel_inventory WHERE company_code=? AND channel_id=? AND sku_sn=?",Long.class,company,channel,sku)==1,"商品 "+sku+" 尚未分配渠道库存，不能按零库存同步");
   OrderStockService.refresh(db,company,sku,channel);
   Map<String,Object> balance=db.queryForMap("SELECT available_stock,allocated_stock,pending_stock,stock_scope_json,version FROM oms_channel_inventory WHERE company_code=? AND channel_id=? AND sku_sn=?",company,channel,sku);
   List<String> scope;try{scope=new ObjectMapper().readValue(s(balance,"stock_scope_json"),List.class);}catch(Exception e){throw new IllegalArgumentException("渠道库存仓库范围缺失或无效");}require(scope!=null&&!scope.isEmpty()&&scope.size()<=100,"渠道库存尚未配置仓库范围");
   long available=publicStock?n(balance,"available_stock"):Math.min(n(balance,"available_stock"),Math.max(0,n(balance,"allocated_stock")-n(balance,"pending_stock")));
   global=Math.min(global,available/coefficient);Map<String,Long> warehouse=new TreeMap<>();
   if(input.get("allowed_warehouses") instanceof List&&!((List<?>)input.get("allowed_warehouses")).isEmpty())scope.retainAll((List<?>)input.get("allowed_warehouses"));
   require(!scope.isEmpty(),"渠道库存范围与店铺允许仓不相交，停止同步");
   List<Object> args=new ArrayList<>(Arrays.asList(company,sku));args.addAll(scope);String marks=String.join(",",Collections.nCopies(scope.size(),"?"));Map<String,Long> physical=new HashMap<>(),held=new HashMap<>();
   for(Map<String,Object> row:db.queryForList("SELECT store_code,SUM(zp_available_number) quantity FROM wms_inventory_batch WHERE company_code=? AND sku_sn=? AND store_code IN ("+marks+") GROUP BY store_code",args.toArray()))physical.put(s(row,"store_code"),n(row,"quantity"));
   List<Object> lockArgs=new ArrayList<>(Arrays.asList(company,sku,channel));lockArgs.addAll(scope);
   for(Map<String,Object> row:db.queryForList("SELECT b.store_code,SUM(s.original_quantity-s.occupied_quantity-s.consumed_quantity-s.released_quantity) quantity FROM rule_stock_reservation s JOIN rule_stock_result r ON r.company_code=s.company_code AND r.rule_id=s.rule_id AND r.sku_sn=s.sku_sn JOIN rule_stock_info h ON h.company_code=s.company_code AND h.id=s.rule_id JOIN wms_inventory_batch b ON b.company_code=s.company_code AND b.id=s.batch_id WHERE s.company_code=? AND s.sku_sn=? AND s.channel_id=? AND b.store_code IN ("+marks+") AND r.status='SUCCESS' AND r.release_status='NONE' AND h.run_action<>'RELEASE' GROUP BY b.store_code",lockArgs.toArray()))held.put(s(row,"store_code"),n(row,"quantity"));
   long claims=OrderStockService.publicClaims(db,company,sku,scope);
   for(String store:scope){long free=publicStock?Math.max(0,physical.getOrDefault(store,0L)-claims):0;long protectedFree=Math.max(0,held.getOrDefault(store,0L)-n(balance,"pending_stock"));warehouse.put(store,(free+protectedFree)/coefficient);}

   if(first){sets.putAll(warehouse);first=false;}else{for(String store:new ArrayList<>(sets.keySet()))sets.put(store,Math.min(sets.get(store),warehouse.getOrDefault(store,0L)));}
   Map<String,Object> fact=new LinkedHashMap<>();fact.put("sku_sn",sku);fact.put("quantity",coefficient);fact.put("available",available);fact.put("version",balance.get("version"));fact.put("warehouses",warehouse);facts.add(fact);
  }
  long ready=sets.values().stream().mapToLong(Long::longValue).sum();Map<String,Object> result=new LinkedHashMap<>();result.put("available",Math.min(global,ready));result.put("whole_set_available",Math.min(global,ready));result.put("components",facts);result.put("calculated_at",java.time.LocalDateTime.now().toString());return result;
 }
}

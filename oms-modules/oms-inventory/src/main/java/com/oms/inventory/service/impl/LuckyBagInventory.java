package com.oms.inventory.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oms.common.api.OrderGoodsClient;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import javax.annotation.Resource;
import java.sql.Statement;
import java.util.*;
import static com.oms.common.lucky.LuckyBagRules.*;

/** All physical pool and bag quota mutations stay inside the inventory database. */
@Service public class LuckyBagInventory {
    @Resource JdbcTemplate db;
    @Resource InventoryMutationService inventoryMutation;
    @Resource OrderGoodsClient goods;
    private static final ObjectMapper JSON=new ObjectMapper();
    public static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("福袋快照无法保存");}}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object raw){if(raw instanceof Map)return (Map<String,Object>)raw;try{return JSON.readValue(raw.toString(),Map.class);}catch(Exception e){throw new IllegalArgumentException("福袋快照无法读取");}}
    public long insert(String sql,Object... args){GeneratedKeyHolder key=new GeneratedKeyHolder();db.update(c->{java.sql.PreparedStatement p=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return key.getKey().longValue();}
    /** Call before acquiring allocation header/SKU locks. It is not an inventory mutation. */
    public void prepare(String company,List<String> skus){
        if(skus.isEmpty())return;require(skus.size()<=500,"单次最多核对500个商品");R<List<Map<String,Object>>> reply=goods.goods(company,skus,SecurityConstants.INNER);require(reply!=null&&reply.getCode()==200&&reply.getData()!=null,"商品规则服务暂不可用，未执行福袋分货");
        Set<String> seen=new HashSet<>();for(Map<String,Object> g:reply.getData()){String sku=text(g,"sku_sn");require(seen.add(sku),"商品SKU身份不唯一："+sku);if(number(g,"is_fd")!=1){db.update("DELETE FROM oms_lucky_definition WHERE company_code=? AND sku_sn=?",company,sku);continue;}
            Map<String,Object> rule=object(g.get("lucky_bag"));require(number(rule,"revision")>0,"福袋规则尚未发布："+sku);Map<String,Object> clean=normalize(rule);clean.put("revision",rule.get("revision"));clean.put("sku_sn",sku);clean.put("goods_name",g.get("goods_name"));
            db.update("INSERT INTO oms_lucky_definition(company_code,sku_sn,revision,definition_json) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE revision=VALUES(revision),definition_json=VALUES(definition_json),update_time=NOW()",company,sku,rule.get("revision"),encode(clean));if(number(clean,"enabled")==0)db.update("UPDATE oms_channel_inventory SET available_stock=0,version=version+1 WHERE company_code=? AND sku_sn=?",company,sku);
        }
    }
    private final java.util.concurrent.ConcurrentMap<String,Long> discovered=new java.util.concurrent.ConcurrentHashMap<>();
    public void discover(String company){if(System.currentTimeMillis()-discovered.getOrDefault(company,0L)<60000)return;String after="";for(int page=0;page<20;page++){R<List<String>> r=goods.luckySkus(company,after,SecurityConstants.INNER);require(r!=null&&r.getCode()==200&&r.getData()!=null,"福袋商品目录暂不可用");List<String> skus=r.getData();if(skus.isEmpty()){discovered.put(company,System.currentTimeMillis());return;}prepare(company,skus);if(skus.size()<500){discovered.put(company,System.currentTimeMillis());return;}String next=skus.get(skus.size()-1);require(next.compareTo(after)>0,"福袋商品游标未前进");after=next;}throw new IllegalArgumentException("福袋商品超过单次全范围上限，请使用指定SKU分货");}
    public Map<String,Object> definition(String company,String sku){List<Map<String,Object>> found=db.queryForList("SELECT definition_json FROM oms_lucky_definition WHERE company_code=? AND sku_sn=?",company,sku);return found.isEmpty()?Collections.emptyMap():object(found.get(0).get("definition_json"));}
    @Transactional(readOnly=true) public Map<String,Object> trial(String company,Map<String,Object> input){
        Map<String,Object> rule=normalize(object(input.get("definition")));rule.put("enabled",1);require(input.get("stores") instanceof List,"请选择试算仓库");List<String> stores=new ArrayList<>();for(Object item:(List<?>)input.get("stores"))stores.add(String.valueOf(item));require(!stores.isEmpty()&&stores.size()<=20,"试算请选择1至20个仓库");int quantity=Math.toIntExact(number(input,"quantity"));require(quantity>0&&quantity<=5000,"试算袋数须为1至5000");
        require(!"PRESET".equals(text(rule,"mode"))||quantity==number(rule,"preset_quantity"),"预制组合须按每批生成袋数完整试算");
        List<Map<String,Object>> results=new ArrayList<>();
        for(String store:new TreeSet<>(stores)){
            Map<String,Object> row=map("store_code",store,"can_fulfill",false,"checked_at",java.time.LocalDateTime.now().toString());
            try {
                List<Map<String,Object>> stock=facts(company,rule,store,false);
                Map<String,Object> diagnostics=LuckyBagDiagnostics.describe(rule,stock,quantity);row.put("diagnostics",diagnostics);row.put("code",diagnostics.get("code"));row.put("message",diagnostics.get("message"));
                if("STOCK_READY".equals(diagnostics.get("code"))){
                    try {
                        List<Map<String,Object>> units=com.oms.common.lucky.LuckyBagRules.plan(rule,stock,quantity,Objects.hash(store,encode(rule),quantity));row.put("can_fulfill",true);row.put("code","READY");row.put("quantity",units.size());row.put("sample_units",units.subList(0,Math.min(20,units.size())));row.put("min_cost",units.stream().map(u->money(u.get("cost"))).min(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO));row.put("max_cost",units.stream().map(u->money(u.get("cost"))).max(java.math.BigDecimal::compareTo).orElse(java.math.BigDecimal.ZERO));
                    } catch(IllegalArgumentException unresolved){row.put("code","COMBINATION_UNRESOLVED");row.put("message","组件数量满足需求，但本次未找到同时满足固定配额及逐袋成本的完整组合；请调整候选或成本上限后重试，此结果不等同于商品缺货");}
                }
            }catch(IllegalArgumentException invalid){row.put("code","CHECK_FAILED");row.put("message",invalid.getMessage());}
            results.add(row);
        }
        return map("warehouses",results,"quantity",quantity,"pieces",rule.get("pieces"),"notice","试算未占用库存，实际分货时按最新库存重新校验");
    }
    public List<Map<String,Object>> facts(String company,Map<String,Object> rule,String store,boolean locked){
        return stockFacts(company,skus(rule),store,locked);
    }
    private List<Map<String,Object>> stockFacts(String company,List<String> skus,String store,boolean locked){
        List<Object> args=new ArrayList<>(Arrays.asList(company,store));args.addAll(skus);String marks=String.join(",",Collections.nCopies(skus.size(),"?"));
        List<Map<String,Object>> facts=db.queryForList("SELECT id batch_id,sku_sn,zp_available_number available,transaction_price unit_cost FROM wms_inventory_batch WHERE company_code=? AND store_code=? AND sku_sn IN ("+marks+") AND zp_available_number>0 ORDER BY sku_sn,id LIMIT 20001"+(locked?" FOR UPDATE":""),args.toArray());require(facts.size()<=20000,"福袋候选批次过多，请缩小仓库或候选范围");
        Map<String,Long> claims=new HashMap<>();List<Object> claimArgs=new ArrayList<>();claimArgs.add(company);claimArgs.addAll(skus);for(Map<String,Object> row:db.queryForList("SELECT i.sku_sn,i.pending_public_stock+(SELECT COALESCE(SUM(d.reserved_quantity-d.outbound_quantity),0) FROM oms_order_stock_demand d WHERE d.company_code=i.company_code AND d.sku_sn=i.sku_sn)-(SELECT COALESCE(SUM(s.quantity-s.outbound_quantity),0) FROM oms_order_stock_source s WHERE s.company_code=i.company_code AND s.sku_sn=i.sku_sn AND s.source_type IN ('LOCK','LUCKY') AND s.reserved=1)-(SELECT COALESCE(SUM(a.quantity-a.released_quantity-a.outbound_quantity),0) FROM oms_order_warehouse_allocation a WHERE a.company_code=i.company_code AND a.sku_sn=i.sku_sn AND a.source_type='PUBLIC_EXTRA' AND a.status='OCCUPIED') claimed FROM oms_inventory i WHERE i.company_code=? AND i.sku_sn IN ("+marks+")",claimArgs.toArray())){require(number(row,"claimed")>=0,"候选订单占用与来源台账不一致");claims.put(text(row,"sku_sn"),number(row,"claimed"));}
        for(Map<String,Object> f:facts){String sku=text(f,"sku_sn");long claim=claims.getOrDefault(sku,0L),take=Math.min(claim,number(f,"available"));f.put("raw_available",number(f,"available"));f.put("public_reserved_deduction",take);f.put("available",number(f,"available")-take);claims.put(sku,claim-take);f.put("store_code",store);}return facts;
    }
    /** One bounded batch read and one order-claim read per warehouse, not per candidate. */
    @Transactional(readOnly=true) public Map<String,Object> stockCheck(String company,Map<String,Object> input){
        require(input.get("skus") instanceof List&&input.get("stores") instanceof List,"请选择候选商品与检查仓库");
        Set<String> skuSet=new TreeSet<>(),storeSet=new TreeSet<>();
        require(((List<?>)input.get("skus")).size()<=200&&((List<?>)input.get("stores")).size()<=20,"最多检查200种商品、20个仓库");
        for(Object item:(List<?>)input.get("skus")){require(item instanceof String&&!((String)item).trim().isEmpty()&&((String)item).length()<=50,"候选SKU无效");skuSet.add(((String)item).trim());}
        for(Object item:(List<?>)input.get("stores")){require(item instanceof String&&!((String)item).trim().isEmpty()&&((String)item).length()<=64,"仓库编码无效");storeSet.add(((String)item).trim());}
        require(!skuSet.isEmpty()&&!storeSet.isEmpty(),"请选择候选商品与检查仓库");
        List<String> skus=new ArrayList<>(skuSet);List<Object> args=new ArrayList<>();args.add(company);args.addAll(storeSet);args.addAll(skuSet);
        Map<String,Map<String,Object>> physical=new HashMap<>();
        for(Map<String,Object> row:db.queryForList("SELECT store_code,sku_sn,SUM(zp_actual_number) physical_quantity,SUM(zp_lock_number) locked_quantity FROM wms_inventory WHERE company_code=? AND store_code IN ("+String.join(",",Collections.nCopies(storeSet.size(),"?"))+") AND sku_sn IN ("+String.join(",",Collections.nCopies(skuSet.size(),"?"))+") GROUP BY store_code,sku_sn",args.toArray()))physical.put(text(row,"store_code")+"\n"+text(row,"sku_sn"),row);
        List<Map<String,Object>> warehouses=new ArrayList<>();
        for(String store:storeSet){Map<String,Map<String,Object>> bySku=new LinkedHashMap<>();
            for(String sku:skuSet){Map<String,Object> wh=physical.getOrDefault(store+"\n"+sku,Collections.emptyMap());bySku.put(sku,map("sku_sn",sku,"available",0L,"physical_quantity",number(wh,"physical_quantity"),"locked_quantity",number(wh,"locked_quantity"),"public_reserved_deduction",0L));}
            try{for(Map<String,Object> fact:stockFacts(company,skus,store,false)){Map<String,Object> row=bySku.get(text(fact,"sku_sn"));row.put("available",number(row,"available")+number(fact,"available"));row.put("public_reserved_deduction",number(row,"public_reserved_deduction")+number(fact,"public_reserved_deduction"));}warehouses.add(map("store_code",store,"code","CHECKED","components",new ArrayList<>(bySku.values())));}
            catch(IllegalArgumentException invalid){warehouses.add(map("store_code",store,"code","CHECK_FAILED","message",invalid.getMessage()));}
        }
        return map("warehouses",warehouses,"checked_at",java.time.LocalDateTime.now().toString(),"notice","仅用于新增福袋的正品可用量，已扣除仓库锁定及相关订单占用；每仓独立预检，不合并承诺可售。实际分货重新校验并锁库。");
    }
    public long capacity(String company,Map<String,Object> rule,List<String> stores){
        require(number(rule,"enabled")==1,"福袋规则已停用");long total=0;
        for(String store:new TreeSet<>(stores)){Map<String,Long> available=new HashMap<>();for(Map<String,Object> f:facts(company,rule,store,false))available.merge(text(f,"sku_sn"),number(f,"available"),Long::sum);long ready=Long.MAX_VALUE;for(Map<String,Object> group:rows(rule.get("groups"))){long count=0;for(Map<String,Object> c:rows(group.get("candidates")))count+=available.getOrDefault(text(c,"sku_sn"),0L);ready=Math.min(ready,count);}if(ready!=Long.MAX_VALUE)total+=ready;}
        return Math.min(total,Integer.MAX_VALUE);
    }
    /** Read-only feasibility check for fixed quantities/cost caps, bounded to the preview page. */
    public void validatePreview(String company,String bag,List<String> stores,List<Map<String,Object>> targets,boolean daily){Map<String,Object> rule=definition(company,bag);if(!"PRESET".equals(text(rule,"mode")))return;Map<String,List<Map<String,Object>>> facts=new TreeMap<>();for(String store:new TreeSet<>(stores))facts.put(store,facts(company,rule,store,false));long batch=number(rule,"preset_quantity");for(Map<String,Object> t:targets){long channel=number(t,"channelId"),need=daily?Math.max(0,number(t,"target")+LuckyBagStock.waiting(db,company,bag,channel)-rawAvailable(company,bag,channel)):number(t,"target");if(daily&&need>0)need=(need+batch-1)/batch*batch;require(need<=5000&&need%batch==0,"预制分货须按完整批次，单次每渠道最多5000袋");for(Map.Entry<String,List<Map<String,Object>>> store:facts.entrySet()){while(need>0&&storeCapacity(rule,store.getValue())>=batch){List<Map<String,Object>> plan=com.oms.common.lucky.LuckyBagRules.plan(rule,store.getValue(),Math.toIntExact(batch),Objects.hash(company,bag,channel,store.getKey(),need));Map<Long,Long> used=new HashMap<>();for(Map<String,Object> u:plan)for(Map<String,Object> c:rows(u.get("components")))used.merge(number(c,"batch_id"),1L,Long::sum);for(Map<String,Object> f:store.getValue())f.put("available",number(f,"available")-used.getOrDefault(number(f,"batch_id"),0L));need-=batch;}if(need==0)break;}require(need==0,"当前批次库存不足以满足预制固定数量及成本上限");}}
    public void lockSkus(String company,Collection<String> skus){for(String sku:new TreeSet<>(skus)){db.update("INSERT INTO oms_inventory(company_code,sku_sn) VALUES (?,?) ON DUPLICATE KEY UPDATE id=id",company,sku);db.queryForList("SELECT id FROM oms_inventory WHERE company_code=? AND sku_sn=? FOR UPDATE",company,sku);}}
    /** Caller holds allocation header; all pool writes and the allocation result commit together. */
    public Map<String,Object> allocate(String company,long allocationRule,String bag,List<String> stores,List<Map<String,Object>> targets,boolean daily){
        Map<String,Object> rule=definition(company,bag);require(db.queryForObject("SELECT COALESCE(SUM(zp_actual_number+cp_actual_number),0) FROM wms_inventory WHERE company_code=? AND sku_sn=?",Long.class,company,bag)==0,"虚拟福袋父SKU存在实物库存，请先核对商品身份");require(!rule.isEmpty()&&number(rule,"enabled")==1,"福袋规则尚未发布或已停用");List<String> keys=skus(rule);keys.add(bag);lockSkus(company,keys);
        List<Map<String,Object>> results=new ArrayList<>();long total=0;
        for(Map<String,Object> target:targets){long channel=number(target,"channelId"),requested=number(target,"target"),before=available(company,bag,channel);long need=daily?Math.max(0,requested+LuckyBagStock.waiting(db,company,bag,channel)-rawAvailable(company,bag,channel)):requested;require(need<=5000,"单次每渠道福袋保障最多5000袋");
            if(daily&&"PRESET".equals(text(rule,"mode"))&&need>0){long batch=number(rule,"preset_quantity");need=Math.multiplyExact((need+batch-1)/batch,batch);require(need<=5000,"补足所需完整预制批次超过单次5000袋上限");}
            long added=0;for(String store:new TreeSet<>(stores)){if(need==0)break;List<Map<String,Object>> stockFacts=facts(company,rule,store,true);long capacity=storeCapacity(rule,stockFacts);int take=Math.toIntExact(Math.min(need,capacity));
                if("PRESET".equals(text(rule,"mode"))){int batch=Math.toIntExact(number(rule,"preset_quantity"));take=take/batch*batch;if(take>0){createPool(company,allocationRule,channel,bag,store,rule,stockFacts,take);added+=take;need-=take;}}
                else if(take>0){createPool(company,allocationRule,channel,bag,store,rule,stockFacts,take);added+=take;need-=take;}
            }
            require(need==0,"福袋无法在所选仓完整配齐，或预制数量不是完整批次；本SKU已回滚");total+=added;results.add(map("channelId",channel,"channelName",target.get("channelName"),"target",added,"before",before,"after",available(company,bag,channel),"change",available(company,bag,channel)-before));project(company,bag,channel);
        }
        return map("skuSn",bag,"goodsType","LUCKY_BAG","unit","袋","channels",results,"total",total,"revision",rule.get("revision"),"pieces",rule.get("pieces"));
    }
    private long storeCapacity(Map<String,Object> rule,List<Map<String,Object>> facts){Map<String,Long> qty=new HashMap<>();for(Map<String,Object> f:facts)qty.merge(text(f,"sku_sn"),number(f,"available"),Long::sum);long cap=Long.MAX_VALUE;for(Map<String,Object> g:rows(rule.get("groups"))){long count=0;for(Map<String,Object> c:rows(g.get("candidates")))count+=qty.getOrDefault(text(c,"sku_sn"),0L);cap=Math.min(cap,count);}return cap==Long.MAX_VALUE?0:cap;}
    private void createPool(String company,long allocationRule,long channel,String bag,String store,Map<String,Object> rule,List<Map<String,Object>> facts,int quantity){
        String request="LB"+UUID.randomUUID().toString().replace("-","");List<Map<String,Object>> units=new ArrayList<>();int batch="PRESET".equals(text(rule,"mode"))?Math.toIntExact(number(rule,"preset_quantity")):quantity;List<Map<String,Object>> remaining=new ArrayList<>();for(Map<String,Object> f:facts)remaining.add(new LinkedHashMap<>(f));for(int done=0;done<quantity;done+=batch){List<Map<String,Object>> planned=com.oms.common.lucky.LuckyBagRules.plan(rule,remaining,batch,Objects.hash(request,done));Map<Long,Long> used=new HashMap<>();for(Map<String,Object> u:planned){u.put("unit",units.size()+1);units.add(u);for(Map<String,Object> c:rows(u.get("components")))used.merge(number(c,"batch_id"),1L,Long::sum);}for(Map<String,Object> f:remaining)f.put("available",number(f,"available")-used.getOrDefault(number(f,"batch_id"),0L));}
        long pool=insert("INSERT INTO oms_lucky_pool(company_code,bag_sku,revision,rule_id,channel_id,store_code,request_key,definition_json,quantity,available_quantity) VALUES (?,?,?,?,?,?,?,?,?,?)",company,bag,rule.get("revision"),allocationRule,channel,store,request,encode(rule),quantity,quantity);
        Map<Long,Map<String,Object>> byBatch=new TreeMap<>();Map<String,Map<Long,Long>> batches=new TreeMap<>();
        for(Map<String,Object> unit:units)for(Map<String,Object> c:rows(unit.get("components"))){long id=number(c,"batch_id");byBatch.putIfAbsent(id,c);batches.computeIfAbsent(text(c,"sku_sn"),k->new TreeMap<>()).merge(id,1L,Long::sum);}
        Map<Long,Long> sourceIds=new HashMap<>();for(String sku:batches.keySet()){inventoryMutation.luckyLock(company,sku,store,batches.get(sku),request);for(Map.Entry<Long,Long> entry:batches.get(sku).entrySet()){Map<String,Object> fact=byBatch.get(entry.getKey());long source=insert("INSERT INTO oms_lucky_source(company_code,pool_id,group_key,sku_sn,batch_id,quantity,unit_cost) VALUES (?,?,?,?,?,?,?)",company,pool,fact.get("group_key"),sku,entry.getKey(),entry.getValue(),fact.get("unit_cost"));sourceIds.put(entry.getKey(),source);}}
        if("PRESET".equals(text(rule,"mode"))){for(Map<String,Object> unit:units)for(Map<String,Object> c:rows(unit.get("components")))c.put("source_id",sourceIds.get(number(c,"batch_id")));for(int start=0;start<units.size();start+=500){List<Map<String,Object>> chunk=units.subList(start,Math.min(start+500,units.size()));List<Object> values=new ArrayList<>();for(Map<String,Object> u:chunk)Collections.addAll(values,company,pool,number(u,"unit"),encode(u.get("components")),money(u.get("cost")),channel);db.update("INSERT INTO oms_lucky_unit(company_code,pool_id,sequence_no,components_json,cost,channel_id) VALUES "+String.join(",",Collections.nCopies(chunk.size(),"(?,?,?,?,?,?)")),values.toArray());}}
    }
    private long rawAvailable(String company,String bag,long channel){Long q=db.queryForObject("SELECT COALESCE(SUM(available_quantity),0) FROM oms_lucky_pool WHERE company_code=? AND bag_sku=? AND channel_id=? AND state='ACTIVE'",Long.class,company,bag,channel);return q==null?0:q;}
    public long available(String company,String bag,long channel){return Math.max(0,rawAvailable(company,bag,channel)-LuckyBagStock.waiting(db,company,bag,channel));}
    public Map<String,Object> release(String company,long allocationRule,String bag,String actor){
        List<Map<String,Object>> sources=db.queryForList("SELECT s.sku_sn FROM oms_lucky_source s JOIN oms_lucky_pool p ON p.company_code=s.company_code AND p.id=s.pool_id WHERE p.company_code=? AND p.rule_id=? AND p.bag_sku=?",company,allocationRule,bag);List<String> keys=new ArrayList<>();keys.add(bag);for(Map<String,Object> s:sources)keys.add(text(s,"sku_sn"));lockSkus(company,keys);
        List<Map<String,Object>> pools=db.queryForList("SELECT * FROM oms_lucky_pool WHERE company_code=? AND rule_id=? AND bag_sku=? ORDER BY id FOR UPDATE",company,allocationRule,bag);require(!pools.isEmpty(),"本单没有福袋保障来源");boolean pending=false;
        for(Map<String,Object> pool:pools){long id=number(pool,"id"),channel=number(pool,"channel_id");if("DRAINED".equals(text(pool,"state")))continue;
            db.update("UPDATE oms_lucky_pool SET state='DRAINING',release_requested_at=COALESCE(release_requested_at,NOW()) WHERE company_code=? AND id=?",company,id);project(company,bag,channel);
            long blocked=db.queryForObject("SELECT COUNT(*) FROM oms_lucky_exposure e JOIN oms_lucky_pool p ON p.company_code=e.company_code AND p.channel_id=e.channel_id AND p.bag_sku=e.bag_sku WHERE p.company_code=? AND p.id=? AND (e.quantity>0 OR e.uncertain=1 OR e.update_time<p.release_requested_at)",Long.class,company,id);
            if(blocked>0||LuckyBagStock.waiting(db,company,bag,channel)>0){pending=true;continue;}
            long freeBags=number(pool,"available_quantity");List<Map<String,Object>> held=db.queryForList("SELECT * FROM oms_lucky_source WHERE company_code=? AND pool_id=? ORDER BY sku_sn,batch_id FOR UPDATE",company,id);Map<String,Long> need=new HashMap<>();for(Map<String,Object> s:held)need.put(text(s,"group_key"),freeBags);
            Map<String,Map<Long,Long>> release=new TreeMap<>();Map<Long,Long> changed=new TreeMap<>();
            for(Map<String,Object> s:held){String group=text(s,"group_key");long free=number(s,"quantity")-number(s,"occupied_quantity")-number(s,"consumed_quantity")-number(s,"released_quantity"),take=number(pool,"occupied_quantity")==0?free:Math.min(free,need.getOrDefault(group,0L));if(take<=0)continue;release.computeIfAbsent(text(s,"sku_sn"),k->new TreeMap<>()).merge(number(s,"batch_id"),take,Long::sum);changed.put(number(s,"id"),take);need.put(group,Math.max(0,need.get(group)-take));}
            require(need.values().stream().allMatch(v->v==0),"福袋可释放袋数与原组件余额不一致");for(Map.Entry<String,Map<Long,Long>> e:release.entrySet())inventoryMutation.settleReservation(company,e.getKey(),e.getValue(),"LBREL"+UUID.randomUUID().toString().replace("-",""),"LUCKY-"+id,false);
            for(Map.Entry<Long,Long> e:changed.entrySet())require(db.update("UPDATE oms_lucky_source SET released_quantity=released_quantity+? WHERE company_code=? AND id=? AND quantity-occupied_quantity-consumed_quantity-released_quantity>=?",e.getValue(),company,e.getKey(),e.getValue())==1,"福袋来源释放余额已变化");
            db.update("UPDATE oms_lucky_unit SET state='RELEASED' WHERE company_code=? AND pool_id=? AND state='AVAILABLE'",company,id);
            db.update("UPDATE oms_lucky_pool SET available_quantity=0,released_quantity=released_quantity+?,state=IF(occupied_quantity=0,'DRAINED','DRAINING') WHERE company_code=? AND id=?",freeBags,company,id);pending|=number(pool,"occupied_quantity")>0;for(String sku:release.keySet())OrderStockService.refresh(db,company,sku,0);project(company,bag,channel);
        }
        Map<String,Object> totals=db.queryForMap("SELECT COALESCE(SUM(released_quantity),0) released,COALESCE(SUM(occupied_quantity),0) occupied,COALESCE(SUM(consumed_quantity),0) consumed FROM oms_lucky_pool WHERE company_code=? AND rule_id=? AND bag_sku=?",company,allocationRule,bag);
        db.update("UPDATE rule_stock_result SET released_quantity=?,occupied_quantity=?,consumed_quantity=?,release_status=?,release_error=?,release_operator=? WHERE company_code=? AND rule_id=? AND sku_sn=?",totals.get("released"),totals.get("occupied"),totals.get("consumed"),pending?"PARTIAL":"RELEASED",pending?"已停止新增投放，等待平台清零核对、未处理订单或原订单占用结束":"",actor,company,allocationRule,bag);
        return map("state",pending?"WAIT_CONFIRM":"RELEASED","balances",totals);
    }
    public void project(String company,String bag,long channel){db.update("INSERT INTO oms_channel_inventory(company_code,channel_id,sku_sn) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id=id",company,channel,bag);LuckyBagStock.project(db,company,bag,channel);}
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED) public Map<String,Object> reclaim(String company,String bag,long rule,String actor){List<Map<String,Object>> heads=db.queryForList("SELECT daily_enabled FROM rule_stock_info WHERE company_code=? AND id=? FOR UPDATE",company,rule);require(heads.size()==1&&number(heads.get(0),"daily_enabled")==0,"请先停用原日常分货规则，再回收福袋保障");return release(company,rule,bag,actor);}
    @Transactional(readOnly=true) public Map<String,Object> report(String company,String bag,long channel){
        List<Map<String,Object>> pools=db.queryForList("SELECT p.*,h.rule_name,h.daily_enabled FROM oms_lucky_pool p LEFT JOIN rule_stock_info h ON h.company_code=p.company_code AND h.id=p.rule_id WHERE p.company_code=? AND p.bag_sku=?"+(channel>0?" AND p.channel_id=?":"")+" ORDER BY p.id DESC LIMIT 100",channel>0?new Object[]{company,bag,channel}:new Object[]{company,bag});
        Map<Long,Map<String,Object>> byId=new HashMap<>();for(Map<String,Object> p:pools){p.remove("definition_json");p.put("components",new ArrayList<>());byId.put(number(p,"id"),p);}
        if(!pools.isEmpty()){List<Object> args=new ArrayList<>();args.add(company);args.addAll(byId.keySet());for(Map<String,Object> source:db.queryForList("SELECT s.*,b.batch_code FROM oms_lucky_source s LEFT JOIN wms_inventory_batch b ON b.company_code=s.company_code AND b.id=s.batch_id WHERE s.company_code=? AND s.pool_id IN ("+String.join(",",Collections.nCopies(byId.size(),"?"))+") ORDER BY s.pool_id,s.sku_sn,s.batch_id",args.toArray()))((List<Map<String,Object>>)byId.get(number(source,"pool_id")).get("components")).add(source);}
        List<Map<String,Object>> exposures=db.queryForList("SELECT channel_id,group_id,quantity,uncertain,update_time FROM oms_lucky_exposure WHERE company_code=? AND bag_sku=?"+(channel>0?" AND channel_id=?":"")+" ORDER BY channel_id,group_id LIMIT 200",channel>0?new Object[]{company,bag,channel}:new Object[]{company,bag});
        return map("sku_sn",bag,"unit","袋","pools",pools,"exposures",exposures,"definition",definition(company,bag),"notice","袋数保障与实际组件件数分别记录。回收须先停用原日常规则，平台清零并核对未处理订单后再次检查；已出库退货只在仓库实收后恢复组件库存。最多展示最近100个保障批次。");
    }
    @Transactional(readOnly=true) public Map<String,Object> snapshot(String company,String bag,long channel,List<String> stores){
        require(channel>0&&!stores.isEmpty(),"福袋同步须配置渠道和允许仓");Map<String,Object> def=definition(company,bag);require(!def.isEmpty(),"福袋库存规则尚未核实，不能按零同步");List<Object> args=new ArrayList<>(Arrays.asList(company,bag,channel));args.addAll(stores);List<Map<String,Object>> pools=db.queryForList("SELECT id,revision,store_code,available_quantity FROM oms_lucky_pool WHERE company_code=? AND bag_sku=? AND channel_id=? AND state='ACTIVE' AND store_code IN ("+String.join(",",Collections.nCopies(stores.size(),"?"))+")",args.toArray());long free=pools.stream().mapToLong(p->number(p,"available_quantity")).sum();require(db.queryForObject("SELECT COUNT(*) FROM oms_lucky_pool WHERE company_code=? AND bag_sku=? AND channel_id=?",Long.class,company,bag,channel)>0,"福袋尚未分配渠道库存，不能按零同步");return map("available",number(def,"enabled")==1?Math.max(0,free-LuckyBagStock.waiting(db,company,bag,channel)):0,"whole_set_available",free,"unit","袋","goods_type","LUCKY_BAG","pools",pools,"revision",def.get("revision"),"calculated_at",java.time.LocalDateTime.now().toString());
    }
}

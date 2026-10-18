package com.oms.inventory.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import javax.annotation.Resource;
import java.util.*;

/** Pure, company-scoped facts. Never refreshes a balance or retries a reservation. */
@Service
public class OrderStockDiagnosticsService {
    @Resource private JdbcTemplate jdbc;
    private static final ObjectMapper JSON=new ObjectMapper();
    static long n(Map<String,Object> row,String key){Object value=row.get(key);return value==null?0:new java.math.BigDecimal(value.toString()).longValueExact();}
    static String s(Map<String,Object> row,String key){Object value=row.get(key);return value==null?"":value.toString();}
    private static String marks(int size){return String.join(",",Collections.nCopies(size,"?"));}
    private static String key(long channel,String tid){return channel+"/"+tid;}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=20)
    public List<Map<String,Object>> facts(String company,List<Map<String,Object>> keys){
        if(company==null||!company.matches("[A-Za-z0-9_-]{1,50}")||keys==null||keys.isEmpty()||keys.size()>50)throw new IllegalArgumentException("库存核对范围无效");
        company=company.toUpperCase(Locale.ROOT);List<Object> args=new ArrayList<>();args.add(company);Set<String> seen=new HashSet<>();List<String> conditions=new ArrayList<>();
        for(Map<String,Object> input:keys){long channel=n(input,"channel_id");String tid=s(input,"platform_tid");if(channel<=0||!com.oms.common.util.FulfillmentReference.valid(tid)||!seen.add(key(channel,tid)))throw new IllegalArgumentException("订单核对标识无效或重复");conditions.add("(channel_id=? AND platform_tid=?)");args.add(channel);args.add(tid);}
        List<Map<String,Object>> demands=jdbc.queryForList("SELECT * FROM oms_order_stock_demand WHERE company_code=? AND ("+String.join(" OR ",conditions)+") ORDER BY id",args.toArray());
        Map<Long,List<Map<String,Object>>> sources=new HashMap<>();Set<String> skus=new TreeSet<>();for(Map<String,Object> demand:demands)skus.add(s(demand,"sku_sn"));
        if(!demands.isEmpty()){List<Object> sourceArgs=new ArrayList<>();sourceArgs.add(company);for(Map<String,Object> demand:demands)sourceArgs.add(demand.get("id"));for(Map<String,Object> source:jdbc.queryForList("SELECT * FROM oms_order_stock_source WHERE company_code=? AND reserved=1 AND demand_id IN ("+marks(demands.size())+") ORDER BY id",sourceArgs.toArray()))sources.computeIfAbsent(n(source,"demand_id"),k->new ArrayList<>()).add(source);}
        Map<String,Map<String,Object>> companyStock=new HashMap<>(),channelStock=new HashMap<>();
        if(!skus.isEmpty()){List<Object> balanceArgs=new ArrayList<>();balanceArgs.add(company);balanceArgs.addAll(skus);for(Map<String,Object> row:jdbc.queryForList("SELECT sku_sn,available_stock,frozen_stock,total_stock FROM oms_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+")",balanceArgs.toArray()))companyStock.put(s(row,"sku_sn"),row);for(Map<String,Object> row:jdbc.queryForList("SELECT channel_id,sku_sn,allocated_stock,quota_stock,quota_reserved_stock,available_stock,frozen_stock FROM oms_channel_inventory WHERE company_code=? AND sku_sn IN ("+marks(skus.size())+")",balanceArgs.toArray()))channelStock.put(n(row,"channel_id")+"/"+s(row,"sku_sn"),row);}
        Map<String,List<Map<String,Object>>> byOrder=new HashMap<>();for(Map<String,Object> demand:demands)byOrder.computeIfAbsent(key(n(demand,"channel_id"),s(demand,"platform_tid")),k->new ArrayList<>()).add(demand);
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> input:keys){Map<String,Object> output=new LinkedHashMap<>();output.put("channel_id",input.get("channel_id"));output.put("platform_tid",input.get("platform_tid"));List<Map<String,Object>> lines=new ArrayList<>();Map<String,List<Map<String,Object>>> bySku=new LinkedHashMap<>();for(Map<String,Object> demand:byOrder.getOrDefault(key(n(input,"channel_id"),s(input,"platform_tid")),Collections.emptyList()))bySku.computeIfAbsent(s(demand,"sku_sn"),k->new ArrayList<>()).add(demand);
            for(Map.Entry<String,List<Map<String,Object>>> entry:bySku.entrySet()){
                String sku=entry.getKey();Map<String,Object> balance=companyStock.getOrDefault(sku,Collections.emptyMap()),channel=channelStock.getOrDefault(n(input,"channel_id")+"/"+sku,Collections.emptyMap());
                long required=0,reserved=0,outbound=0,sourceQuantity=0,sourceOutbound=0,locked=0,publicQty=0,activePublic=0;boolean ledgerInvalid=false,publicAllowed=true;Set<String> reasons=new HashSet<>();Map<String,Object> decision=Collections.emptyMap();
                for(Map<String,Object> demand:entry.getValue()){
                    boolean cancelled="CANCELLED".equals(s(demand,"status"));long r=n(demand,"reserved_quantity"),out=n(demand,"outbound_quantity");if(!cancelled)required+=n(demand,"demand_quantity")-n(demand,"cancelled_quantity");reserved+=r;outbound+=out;publicAllowed&=n(demand,"allow_public_stock")==1;reasons.add(s(demand,"reason_code"));ledgerInvalid|=r<0||r>n(demand,"demand_quantity")-n(demand,"cancelled_quantity")||out<0||out>r;
                    try{if(demand.get("decision_snapshot")!=null)decision=JSON.readValue(s(demand,"decision_snapshot"),Map.class);}catch(Exception e){ledgerInvalid=true;}
                    List<Map<String,Object>> lineSources=sources.getOrDefault(n(demand,"id"),Collections.emptyList());
                    for(Map<String,Object> source:lineSources){long q=n(source,"quantity"),o=n(source,"outbound_quantity");sourceQuantity+=q;sourceOutbound+=o;ledgerInvalid|=q<0||o<0||o>q;if("LOCK".equals(s(source,"source_type")))locked+=q;else if("PUBLIC_EXTRA".equals(s(source,"source_type"))){publicQty+=q;activePublic+=q-o;}else ledgerInvalid=true;}
                    Map<String,Object> line=new LinkedHashMap<>();for(String field:Arrays.asList("platform_oid","sku_sn","demand_quantity","reserved_quantity","outbound_quantity","status","reason_code"))line.put(field,demand.get(field));line.put("sources",lineSources);line.put("decision_snapshot",decision);lines.add(line);
                }
                long pending=Math.max(0,required-reserved),active=Math.max(0,reserved-outbound);ledgerInvalid|=sourceQuantity!=reserved||sourceOutbound!=outbound||(reserved>0&&(balance.isEmpty()||channel.isEmpty()));
                long quota=n(channel,"quota_stock"),otherPublic=Math.max(0,n(channel,"quota_reserved_stock")-activePublic),quotaForThis=Math.max(0,quota-otherPublic-n(channel,"frozen_stock"));
                Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("basis","CURRENT_BALANCE");evidence.put("required",required);evidence.put("reserved",reserved);evidence.put("outbound",outbound);evidence.put("pending",pending);evidence.put("locked_reserved",locked);evidence.put("public_reserved",publicQty);evidence.put("active_public",activePublic);evidence.put("company_available",n(balance,"available_stock"));evidence.put("company_frozen",n(balance,"frozen_stock"));evidence.put("channel_quota",quota);evidence.put("other_public_reserved",otherPublic);evidence.put("quota_for_this_order",quotaForThis);evidence.put("last_decision",decision);
                List<Map<String,Object>> issues=new ArrayList<>();
                if(ledgerInvalid||(pending>0&&reasons.contains("BALANCE_MISMATCH")))issues.add(issue("LEDGER","SOURCE_BALANCE_MISMATCH","COMPANY",sku,true,"预占需求、来源或余额记录不一致，请核对台账",evidence));
                if(!ledgerInvalid&&activePublic>quotaForThis){Map<String,Object> quotaEvidence=new LinkedHashMap<>(evidence);quotaEvidence.put("excess_quantity",activePublic-quotaForThis);issues.add(issue("QUOTA","CHANNEL_QUOTA_EXCEEDED","CHANNEL",sku,false,"当前渠道额度 "+quota+" 件，本订单仍公共预占 "+activePublic+" 件，超可用额度 "+(activePublic-quotaForThis)+" 件；需求已满足",quotaEvidence));}
                if(pending>Math.max(0,n(channel,"allocated_stock"))&&!publicAllowed)issues.add(issue("RESTRICTED","PUBLIC_STOCK_DISABLED","CHANNEL",sku,true,"渠道锁库不足，预占时设置不允许公共库存补占",evidence));
                if(pending>0&&!ledgerInvalid&&publicAllowed&&n(balance,"available_stock")+n(channel,"allocated_stock")<pending){Map<String,Object> shortage=new LinkedHashMap<>(evidence);shortage.put("shortage_quantity",pending-Math.max(0,n(balance,"available_stock"))-Math.max(0,n(channel,"allocated_stock")));issues.add(issue("SHORTAGE","COMPANY_STOCK_SHORTAGE","COMPANY",sku,true,"公司可用库存不足，尚未满足 "+pending+" 件需求",shortage));}
                if(pending>Math.max(0,n(balance,"available_stock"))+Math.max(0,n(channel,"allocated_stock"))&&n(balance,"frozen_stock")>0)issues.add(issue("RESTRICTED","COMPANY_STOCK_FROZEN","COMPANY",sku,true,"公司库存冻结 "+n(balance,"frozen_stock")+" 件，仍有需求未满足",evidence));
                Map<String,Object> skuFact=new LinkedHashMap<>();skuFact.put("sku_sn",sku);skuFact.put("evidence",evidence);skuFact.put("issues",issues);output.computeIfAbsent("sku_facts",k->new ArrayList<Map<String,Object>>());((List<Map<String,Object>>)output.get("sku_facts")).add(skuFact);
            }
            output.put("lines",lines);output.putIfAbsent("sku_facts",Collections.emptyList());result.add(output);
        }
        return result;
    }
    private Map<String,Object> issue(String category,String reason,String scope,String sku,boolean blocking,String message,Map<String,Object> evidence){Map<String,Object> row=new LinkedHashMap<>();row.put("domain","INVENTORY");row.put("category",category);row.put("reason_code",reason);row.put("scope",scope);row.put("stage","RESERVE");row.put("sku_sn",sku);row.put("severity",blocking?"ERROR":"WARNING");row.put("blocking",blocking?1:0);row.put("message",message);row.put("evidence",evidence);return row;}
}

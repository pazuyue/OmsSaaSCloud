package com.oms.common.lucky;

import java.math.BigDecimal;
import java.util.*;

/** Versioned, bounded lucky-bag rules shared by preview and authoritative stock planning. */
public final class LuckyBagRules {
    private LuckyBagRules(){}
    public static Map<String,Object> map(Object... pairs){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)r.put(pairs[i].toString(),pairs[i+1]);return r;}
    public static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    public static String text(Map<String,Object> r,String k){return r.get(k)==null?"":r.get(k).toString();}
    public static long number(Map<String,Object> r,String k){try{return r.get(k)==null?0:new BigDecimal(r.get(k).toString()).longValueExact();}catch(Exception e){throw new IllegalArgumentException("数量须为整数："+k);}}
    public static BigDecimal money(Object value){try{BigDecimal n=new BigDecimal(value==null?"0":value.toString());require(n.signum()>=0&&n.scale()<=4&&n.compareTo(new BigDecimal("100000000"))<=0,"金额须为非负数且最多四位小数");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("金额无效");}}
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> rows(Object value){require(value instanceof List,"分组或候选商品格式无效");List<Map<String,Object>> r=new ArrayList<>();for(Object item:(List<?>)value){require(item instanceof Map,"分组或候选商品格式无效");r.add((Map<String,Object>)item);}return r;}

    public static Map<String,Object> normalize(Map<String,Object> input){
        String mode=text(input,"mode"),selection=text(input,"selection");if(mode.isEmpty())mode="RANDOM";if(selection.isEmpty())selection="WEIGHTED";
        require(Arrays.asList("RANDOM","PRESET").contains(mode),"福袋配货方式无效");
        require(Arrays.asList("WEIGHTED","CLEARANCE").contains(selection),"福袋选品方式无效");
        long enabled=number(input,"enabled");require(enabled==0||enabled==1,"福袋启用状态无效");
        List<Map<String,Object>> groups=rows(input.get("groups"));require(!groups.isEmpty()&&groups.size()<=10,"福袋须配置1至10个分组");
        Set<String> skus=new LinkedHashSet<>(),keys=new HashSet<>();List<Map<String,Object>> clean=new ArrayList<>();
        for(Map<String,Object> g:groups){
            String key=text(g,"key"),name=text(g,"name").trim();require(key.matches("[A-Za-z0-9_-]{1,32}")&&keys.add(key),"分组标识无效或重复");require(!name.isEmpty()&&name.length()<=50,"分组名称须为1至50字");
            List<Map<String,Object>> choices=rows(g.get("candidates"));require(!choices.isEmpty(),"每组至少选择一个候选SKU");List<Map<String,Object>> candidates=new ArrayList<>();
            for(Map<String,Object> c:choices){String sku=text(c,"sku_sn").trim();require(!sku.isEmpty()&&sku.length()<=50&&skus.add(sku),"候选SKU重复、跨组重叠或无效："+sku);long weight=number(c,"weight"),priority=number(c,"clearance_priority");require(weight>=1&&weight<=10000&&priority>=0&&priority<=10000,"权重须为1至10000，清仓优先级须为0至10000");
                Object fixed=c.get("fixed_quantity");if(fixed!=null){require(mode.equals("PRESET"),"固定数量配额须使用预制组合池");long q=number(c,"fixed_quantity");require(q>=0&&q<=5000,"预制固定数量须为0至5000");}
                candidates.add(map("sku_sn",sku,"goods_name",text(c,"goods_name"),"weight",weight,"clearance_priority",priority,"fixed_quantity",fixed==null?null:number(c,"fixed_quantity")));
            }
            clean.add(map("key",key,"name",name,"candidates",candidates));
        }
        require(skus.size()<=200,"一个福袋规则最多200个候选SKU");
        BigDecimal cap=money(input.get("max_cost"));require(cap.signum()==0||mode.equals("PRESET"),"有每袋成本上限时须使用预制组合池，保证投放前组成与成本确定");
        long batch=number(input,"preset_quantity");require(batch>=0&&batch<=5000,"预制批次袋数须为0至5000");
        if(mode.equals("PRESET")){require(batch>0,"预制组合池须填写每批袋数");for(Map<String,Object> group:clean){long fixed=0;boolean free=false;for(Map<String,Object> c:rows(group.get("candidates"))){if(c.get("fixed_quantity")==null)free=true;else fixed+=number(c,"fixed_quantity");}require(fixed<=batch&&(free||fixed==batch),"固定数量配额必须能组成完整预制批次："+text(group,"name"));}}
        return map("enabled",enabled,"mode",mode,"selection",selection,"max_cost",cap,"preset_quantity",batch,"groups",clean,"pieces",clean.size());
    }
    /** Drafts may be incomplete, but remain bounded and structurally safe to edit. */
    public static Map<String,Object> draft(Map<String,Object> input){
        String mode=text(input,"mode"),selection=text(input,"selection");require(Arrays.asList("RANDOM","PRESET").contains(mode)&&Arrays.asList("WEIGHTED","CLEARANCE").contains(selection),"福袋配货或选品方式无效");long enabled=number(input,"enabled"),batch=number(input,"preset_quantity");require((enabled==0||enabled==1)&&batch>=0&&batch<=5000,"福袋状态或批次袋数无效");List<Map<String,Object>> groups=rows(input.get("groups"));require(groups.size()<=10,"最多10个福袋分组");Set<String> keys=new HashSet<>(),skus=new HashSet<>();List<Map<String,Object>> clean=new ArrayList<>();
        for(Map<String,Object> group:groups){String key=text(group,"key"),name=text(group,"name").trim();require(key.matches("[A-Za-z0-9_-]{1,32}")&&keys.add(key)&&name.length()<=50,"分组标识或名称无效");List<Map<String,Object>> candidates=new ArrayList<>();for(Map<String,Object> c:rows(group.get("candidates"))){String sku=text(c,"sku_sn");long weight=number(c,"weight"),priority=number(c,"clearance_priority");require(!sku.isEmpty()&&sku.length()<=50&&skus.add(sku)&&weight>=1&&weight<=10000&&priority>=0&&priority<=10000,"候选SKU重复或权重/清仓优先级无效");Long fixed=c.get("fixed_quantity")==null?null:number(c,"fixed_quantity");require(fixed==null||fixed>=0&&fixed<=5000,"固定数量须为空或0至5000");candidates.add(map("sku_sn",sku,"goods_name",text(c,"goods_name"),"weight",weight,"clearance_priority",priority,"fixed_quantity",fixed));}clean.add(map("key",key,"name",name,"candidates",candidates));}require(skus.size()<=200,"候选SKU合计最多200种");return map("enabled",enabled,"mode",mode,"selection",selection,"preset_quantity",batch,"max_cost",money(input.get("max_cost")),"groups",clean,"pieces",clean.size());
    }
    public static List<String> skus(Map<String,Object> rule){List<String> out=new ArrayList<>();for(Map<String,Object> g:rows(rule.get("groups")))for(Map<String,Object> c:rows(g.get("candidates")))out.add(text(c,"sku_sn"));return out;}

    /** Stock facts are actual batch costs; missing costs cannot pass an enabled cost cap. */
    public static List<Map<String,Object>> plan(Map<String,Object> rule,List<Map<String,Object>> facts,int quantity,long seed){
        require(quantity>0&&quantity<=5000,"单次福袋保障数量须为1至5000袋");
        if("PRESET".equals(text(rule,"mode")))require(quantity==number(rule,"preset_quantity"),"预制组合池必须按完整批次创建，固定数量配额按批次计算");
        Map<String,List<Map<String,Object>>> bySku=new HashMap<>();Set<Long> ids=new HashSet<>();
        for(Map<String,Object> f:facts){require(number(f,"available")>=0&&number(f,"batch_id")>0&&ids.add(number(f,"batch_id")),"批次库存无效或重复");bySku.computeIfAbsent(text(f,"sku_sn"),k->new ArrayList<>()).add(f);}
        for(List<Map<String,Object>> batches:bySku.values())batches.sort(Comparator.comparingLong(f->number(f,"batch_id")));
        for(int attempt=0;attempt<8;attempt++){
            Random random=new Random(seed+attempt*104729L);Map<Long,Long> remaining=new HashMap<>();for(Map<String,Object> f:facts)remaining.put(number(f,"batch_id"),number(f,"available"));
            Map<String,Long> counts=new HashMap<>();List<Map<String,Object>> units=new ArrayList<>();boolean failed=false;int[] budget={200000};
            for(int unit=0;unit<quantity;unit++){
                List<Map<String,Object>> chosen=new ArrayList<>();
                if(!choose(rule,rows(rule.get("groups")),0,quantity-unit,bySku,remaining,counts,random,BigDecimal.ZERO,chosen,budget)){failed=true;break;}
                BigDecimal cost=BigDecimal.ZERO;for(Map<String,Object> c:chosen){long id=number(c,"batch_id");remaining.put(id,remaining.get(id)-1);counts.merge(text(c,"sku_sn"),1L,Long::sum);cost=cost.add(money(c.get("unit_cost")));}
                units.add(map("unit",unit+1,"components",chosen,"cost",cost));
            }
            if(!failed)return units;
        }
        throw new IllegalArgumentException("当前库存未能形成满足固定数量与成本上限的完整福袋批次；请调整候选、数量或成本后重新试算");
    }
    private static boolean choose(Map<String,Object> rule,List<Map<String,Object>> groups,int index,int unitsLeft,Map<String,List<Map<String,Object>>> facts,Map<Long,Long> stock,Map<String,Long> counts,Random random,BigDecimal cost,List<Map<String,Object>> chosen,int[] budget){
        if(index==groups.size())return true;if(--budget[0]<0)return false;
        Map<String,Object> group=groups.get(index);List<Map<String,Object>> candidates=rows(group.get("candidates"));long required=0;
        for(Map<String,Object> c:candidates)if(c.get("fixed_quantity")!=null)required+=Math.max(0,number(c,"fixed_quantity")-counts.getOrDefault(text(c,"sku_sn"),0L));
        if(required>unitsLeft)return false;
        List<Map<String,Object>> options=new ArrayList<>();
        for(Map<String,Object> c:candidates){String sku=text(c,"sku_sn");long used=counts.getOrDefault(sku,0L);if(c.get("fixed_quantity")!=null&&used>=number(c,"fixed_quantity"))continue;if(required==unitsLeft&&c.get("fixed_quantity")==null)continue;
            List<Map<String,Object>> batches=facts.getOrDefault(sku,Collections.emptyList());Map<String,Object> first=null;long available=0;for(Map<String,Object> f:batches){long q=stock.getOrDefault(number(f,"batch_id"),0L);available+=q;if(q>0&&first==null)first=f;}
            if(first==null)continue;if(c.get("fixed_quantity")!=null&&number(c,"fixed_quantity")-used>available)return false;
            Map<String,Object> option=new LinkedHashMap<>(first);option.put("goods_name",text(c,"goods_name").isEmpty()?sku:c.get("goods_name"));option.put("group_key",group.get("key"));option.put("group_name",group.get("name"));option.put("quantity",1);option.put("rank",-Math.log(Math.max(Double.MIN_VALUE,random.nextDouble()))/number(c,"weight"));option.put("clearance_priority",c.get("clearance_priority"));options.add(option);
        }
        options.sort((a,b)->{if("CLEARANCE".equals(text(rule,"selection"))){int p=Long.compare(number(b,"clearance_priority"),number(a,"clearance_priority"));if(p!=0)return p;int age=Long.compare(number(a,"batch_id"),number(b,"batch_id"));if(age!=0)return age;}return Double.compare(((Number)a.get("rank")).doubleValue(),((Number)b.get("rank")).doubleValue());});
        BigDecimal cap=money(rule.get("max_cost"));
        for(Map<String,Object> option:options){if(--budget[0]<0)return false;if(cap.signum()>0&&(option.get("unit_cost")==null||cost.add(money(option.get("unit_cost"))).compareTo(cap)>0))continue;
            Map<String,Object> item=new LinkedHashMap<>(option);item.remove("rank");item.remove("available");chosen.add(item);
            if(choose(rule,groups,index+1,unitsLeft,facts,stock,counts,random,cost.add(money(option.get("unit_cost"))),chosen,budget))return true;
            chosen.remove(chosen.size()-1);
        }return false;
    }
}

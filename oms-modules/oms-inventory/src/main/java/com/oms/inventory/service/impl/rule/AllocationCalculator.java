package com.oms.inventory.service.impl.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.oms.inventory.service.impl.InventoryQueryService.number;

/** Pure calculation shared by preview and execution; DB affected-row counts never affect quotas. */
public final class AllocationCalculator {
    private AllocationCalculator() { }

    public static List<Map<String,Object>> calculate(long available, boolean priority, boolean locking,
            List<Map<String,Object>> channels, Map<Long,Map<String,Object>> current) {
        if(available<0 || available>Integer.MAX_VALUE) throw new IllegalArgumentException("可分库存超出有效范围");
        long remaining=available,total=0;
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> channel:channels) {
            long id=number(channel,"channelId");
            BigDecimal value=new BigDecimal(channel.get("percentage").toString());
            int rounding=(int)number(channel,"decimalHandleType");
            BigDecimal expected=number(channel,"ruleType")==2 ? value : BigDecimal.valueOf(available).multiply(value).movePointLeft(2);
            long target=expected.setScale(0,rounding==2?RoundingMode.UP:rounding==3?RoundingMode.HALF_UP:RoundingMode.DOWN).longValueExact();
            if(target<0 || target>Integer.MAX_VALUE) throw new IllegalArgumentException("渠道分配数量超出有效范围");
            if(priority) target=Math.min(target,remaining);
            remaining-=target;total+=target;
            Map<String,Object> old=current.get(id);
            long before=number(old,locking?"allocatedStock":"availableStock");
            long reserved=number(old,"reservedStock"),frozen=number(old,"frozenStock");
            long after=locking ? Math.addExact(before,target) : Math.max(0,target-reserved-frozen);
            if(after>Integer.MAX_VALUE) throw new IllegalArgumentException("渠道库存超出有效范围");
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("channelId",id);row.put("channelName",channel.get("channelName"));row.put("target",target);
            row.put("before",before);row.put("after",after);row.put("change",after-before);
            row.put("unchanged",old!=null && before==after);
            row.put("reservedStock",reserved);row.put("frozenStock",frozen);
            row.put("warning",!locking && target<reserved+frozen?"配额小于订单预占与冻结数量，可售量归零":"");
            result.add(row);
        }
        if(locking && total>available) throw new IllegalArgumentException("锁库总量超过实际可用库存，请降低比例或使用按优先级分配");
        return result;
    }
}

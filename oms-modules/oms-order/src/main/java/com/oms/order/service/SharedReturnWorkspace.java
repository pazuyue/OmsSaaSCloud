package com.oms.order.service;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
import static com.oms.order.service.ExchangeService.*;
@Service public class SharedReturnWorkspace {
    @Resource ExchangeService exchanges;
    @Resource RefundService refunds;
    @Resource SharedReturnClient parcels;
    void own(String c,String kind,long owner,long source){if("EXCHANGE".equals(kind)){require(owner==source,"换货退回来源不属于本单");exchanges.row(c,owner,false);}else{require("REFUND".equals(kind),"售后来源类型无效");refunds.business(c,owner,false);require(refunds.repository.db().queryForObject("SELECT COUNT(*) FROM oms_refund_return_plan WHERE company_code=? AND refund_id=? AND id=?",Long.class,c,owner,source)==1,"退回计划不属于本退款");}}
    public Map<String,Object> report(String c,String kind,long owner,long source){own(c,kind,owner,source);return data(parcels.report(c,kind,source,SecurityConstants.INNER));}
    public Map<String,Object> action(String c,String kind,long owner,long source,String action,Map<String,Object> input,Set<String> permissions,String actor){
        require(Arrays.asList("query","dispatch","retry","cancel","allocate","reject","posting").contains(action),"共同退回操作无效");Map<String,Object> report=report(c,kind,owner,source);require(Boolean.TRUE.equals(report.get("bound")),"当前单据尚无共同退回包裹");Set<String> kinds=new HashSet<>();Object raw=report.get("members");if(raw instanceof List)for(Object m:(List<?>)raw)kinds.add(text(object(m),"owner_kind"));
        for(String k:kinds){String permission="EXCHANGE".equals(k)?"order:exchange:execute":"order:refund:goods";require(permissions!=null&&(permissions.contains("*:*:*")||permissions.contains(permission)),"共同THK涉及"+("EXCHANGE".equals(k)?"换货":"退款")+"，须同时具备对应处理权限");}
        Map<String,Object> body=new LinkedHashMap<>(input);body.put("actor",actor);return data(parcels.action(c,kind,source,action,body,SecurityConstants.INNER));
    }
}

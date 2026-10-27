package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Bounded log metadata lookup. No payload and no warehouse requests in list queries. */
@Service public class WarehouseInteractionSummary {
    @Resource WmsStore db;
    public void attach(String c,List<Map<String,Object>> documents){
        Set<String> keys=new LinkedHashSet<>();for(Map<String,Object> d:documents){String type=text(d.get("documentType")),sn=text(d.get("sn"));if(type.endsWith("_OUT")&&!"PURCHASE_RETURN_OUT".equals(type)||type.startsWith("ASSEMBLY_")||type.startsWith("DISASSEMBLY_"))sn=text(d.get("relationSn"));d.put("interactionSn",sn);if(!sn.isEmpty())keys.add(sn);}
        Map<String,Map<String,Object>> logs=new HashMap<>();List<String> numbers=new ArrayList<>(keys);
        for(int at=0;at<numbers.size();at+=250){List<String> part=numbers.subList(at,Math.min(at+250,numbers.size()));List<Object> args=new ArrayList<>();args.add(c);args.addAll(part);args.add(c);
            for(Map<String,Object> log:db.jdbc.queryForList("SELECT l.id,l.ticket_sn,l.action,l.direction,l.result,l.request_id,l.error_info,l.result_summary,l.duration_ms,l.create_time FROM (SELECT MAX(id) id FROM wms_interaction_log WHERE company_code=? AND ticket_sn IN ("+String.join(",",Collections.nCopies(part.size(),"?"))+") GROUP BY ticket_sn) x JOIN wms_interaction_log l ON l.id=x.id AND l.company_code=?",args.toArray())){ReturnQueryWarehouse.formatTimes(log);logs.put(text(log.get("ticket_sn")),log);}
        }
        for(Map<String,Object> d:documents){d.put("latestInteraction",logs.getOrDefault(text(d.get("interactionSn")),Collections.emptyMap()));String state=text(d.get("dispatchState")),cancel=text(d.get("cancelState"));String label="CONFIRMED".equals(cancel)||"CANCELED".equals(state)?"仓库已截停":Arrays.asList("PENDING","UNKNOWN").contains(cancel)||"CANCEL_PENDING".equals(state)?"截停待确认":"ACCEPTED".equals(state)?"仓库已受理":"UNKNOWN".equals(state)?"受理结果待确认":"FAILED".equals(state)?"下发失败":"SENDING".equals(state)?"等待仓库响应":"未下发";d.put("warehouseLabel",d.get("actualWarehouse")!=null&&id(d.get("actualWarehouse"))==2?"虚拟执行，无仓库交互":label);}
    }
}

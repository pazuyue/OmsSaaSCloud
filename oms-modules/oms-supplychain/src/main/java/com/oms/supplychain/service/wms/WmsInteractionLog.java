package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

@Service
public class WmsInteractionLog {
    @Resource private WmsStore db;
    public long start(String company,long connection,Long task,String sn,String direction,String action,String body){return db.insert("wms_interaction_log",map("company_code",company,"connection_id",connection,"task_id",task,"ticket_sn",sn,"direction",direction,"action",action,"request_id",UUID.randomUUID().toString(),"request_body",redact(body),"result","STARTED"));}
    public void finish(long key,String result,String response,String error,long elapsed){db.jdbc.update("UPDATE wms_interaction_log SET result=?,response_body=?,error_info=?,duration_ms=? WHERE id=?",result,redact(response),cut(error,1000),elapsed,key);}
    private static final Set<String> ACTIONS=new HashSet<>(Arrays.asList("OUTBOUND_CREATE","OUTBOUND_QUERY","OUTBOUND_RECEIPT","OUTBOUND_REPAIR","CREATE","QUERY","CANCEL","RECEIPT"));
    private static final Set<String> RESULTS=new HashSet<>(Arrays.asList("STARTED","SUCCESS","FAILED","UNKNOWN"));
    private static final String SELECT="SELECT l.id,l.connection_id,c.name connection_name,c.provider,l.ticket_sn,l.direction,l.action,l.request_id,l.result,l.error_info,l.duration_ms,l.create_time FROM wms_interaction_log l JOIN wms_connection c ON c.id=l.connection_id AND c.company_code=l.company_code";
    public List<Map<String,Object>> list(String company,String sn,int page){return list(company,sn,page,"","");}
    public List<Map<String,Object>> list(String company,String sn,int page,String action,String result){
        require(action.isEmpty()||ACTIONS.contains(action),"日志动作无效");require(result.isEmpty()||RESULTS.contains(result),"日志结果无效");require(page>=1&&page<=100000,"日志页码无效");
        List<Object> args=new ArrayList<>(Arrays.asList(company));String where=" WHERE l.company_code=?";
        if(!sn.isEmpty()){where+=" AND l.ticket_sn=?";args.add(sn);}if(!action.isEmpty()){where+=" AND l.action=?";args.add(action);}if(!result.isEmpty()){where+=" AND l.result=?";args.add(result);}args.add((page-1)*50);
        return db.jdbc.queryForList(SELECT+where+" ORDER BY l.id DESC LIMIT 50 OFFSET ?",args.toArray());
    }
    public Map<String,Object> summary(String company,String sn){
        require(sn.length()<=100,"执行单号过长");List<Map<String,Object>> counts=db.jdbc.queryForList("SELECT action,COUNT(*) log_count FROM wms_interaction_log WHERE company_code=? AND (?='' OR ticket_sn=?) GROUP BY action",company,sn,sn);
        Map<String,Object> result=map("counts",counts,"total",counts.stream().mapToLong(r->id(r.get("log_count"))).sum());
        if(!sn.isEmpty())for(boolean first:Arrays.asList(true,false)){
            List<Map<String,Object>> records=db.jdbc.queryForList(SELECT+" WHERE l.company_code=? AND l.ticket_sn=? AND l.action IN ('OUTBOUND_CREATE','CREATE') ORDER BY l.id "+(first?"ASC":"DESC")+" LIMIT 1",company,sn);
            result.put(first?"first_create":"latest_create",records.isEmpty()?null:records.get(0));
        }
        return result;
    }
    public Map<String,Object> detail(String company,long key){return db.one("SELECT * FROM wms_interaction_log WHERE id=? AND company_code=?",key,company);}
    public static String redact(String body){String s=body==null?"":body;s=s.replaceAll("(?is)(<(?:secret|appSecret|app_secret|token|access_token|sign|mobile|tel|phone|detailAddress|address|name|oaid|receiver_name|receiver_phone|receiver_address|receiver_mobile)>).*?(</[^>]+>)","$1***$2");s=s.replaceAll("(?i)(\"(?:secret|appSecret|app_secret|token|access_token|sign|mobile|tel|phone|detailAddress|address|name|oaid|receiver_name|receiver_phone|receiver_address|receiver_mobile)\"\\s*:\\s*\")[^\"]*(\")","$1***$2");return cut(s,1024*1024);}
    private static String cut(String s,int n){return s==null?"":s.substring(0,Math.min(n,s.length()));}
}

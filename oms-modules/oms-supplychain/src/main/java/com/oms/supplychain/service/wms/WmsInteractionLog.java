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
    public List<Map<String,Object>> list(String company,String sn,int page){return db.jdbc.queryForList("SELECT l.id,l.connection_id,c.name connection_name,c.provider,l.ticket_sn,l.direction,l.action,l.request_id,l.result,l.error_info,l.duration_ms,l.create_time FROM wms_interaction_log l JOIN wms_connection c ON c.id=l.connection_id AND c.company_code=l.company_code WHERE l.company_code=? AND (?='' OR l.ticket_sn=?) ORDER BY l.id DESC LIMIT 50 OFFSET ?",company,sn,sn,Math.max(0,page-1)*50);}
    public Map<String,Object> detail(String company,long key){return db.one("SELECT * FROM wms_interaction_log WHERE id=? AND company_code=?",key,company);}
    public static String redact(String body){String s=body==null?"":body;s=s.replaceAll("(?is)(<(?:secret|appSecret|app_secret|token|access_token|sign|mobile|tel|phone|detailAddress|address)>).*?(</[^>]+>)","$1***$2");s=s.replaceAll("(?i)(\"(?:secret|appSecret|app_secret|token|access_token|sign|mobile|tel|phone|detailAddress|address)\"\\s*:\\s*\")[^\"]*(\")","$1***$2");return cut(s,1024*1024);}
    private static String cut(String s,int n){return s==null?"":s.substring(0,Math.min(n,s.length()));}
}

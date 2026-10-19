package com.oms.channel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import static com.oms.channel.platform.ChannelPlatformService.map;

/** Durable attempt records, separate from business task state and credential storage. */
@Service public class PlatformInteractionLog {
    @Resource JdbcTemplate db;
    @Resource PlatformTransactionManager transactionManager;
    @Resource ChannelSettings settings;
    @Value("${oms.platform-log.payload-days:14}") int payloadDays=14;
    @Value("${oms.platform-log.summary-days:90}") int summaryDays=90;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final ThreadLocal<Map<String,Object>> CONTEXT=new ThreadLocal<>();
    static final Set<String> NUMBER_TYPES=new LinkedHashSet<>(Arrays.asList("OMS_ORDER","PLATFORM_TID","PLATFORM_OID","EXCHANGE","PLATFORM_EXCHANGE","REFUND","PLATFORM_REFUND","SHIPMENT","PACKAGE","WAYBILL","TASK","PLATFORM_ITEM","PLATFORM_SKU"));
    static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static String s(Map<String,?> m,String key){return m.get(key)==null?"":String.valueOf(m.get(key));}
    static long n(Map<String,?> m,String key){try{return Long.parseLong(s(m,key));}catch(Exception e){return 0;}}
    static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("日志结构无效");}}
    static String bounded(String value,int length){return value.length()>length?value.substring(0,length):value;}
    private TransactionTemplate tx(){TransactionTemplate t=new TransactionTemplate(transactionManager);t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);return t;}
    static String id(){return "PL"+UUID.randomUUID().toString().replace("-","");}
    public final class Scope implements AutoCloseable {private final Map<String,Object> previous;private Scope(Map<String,Object> value){previous=CONTEXT.get();CONTEXT.set(value);}@Override public void close(){if(previous==null)CONTEXT.remove();else CONTEXT.set(previous);}}
    public Scope scope(Map<String,Object> context){return new Scope(new LinkedHashMap<>(context));}
    public Map<String,Object> begin(String company,Long channel,Long app,String action,String kind,String environment,String category,String actor,Map<String,Object> metadata,Object request){return beginScoped(com.oms.common.tenant.TenantRouting.company(company),channel,app,action,kind,environment,category,actor,metadata,request);}
    private Map<String,Object> beginScoped(String company,Long channel,Long app,String action,String kind,String environment,String category,String actor,Map<String,Object> metadata,Object request){
        require(company!=null&&company.matches("[A-Za-z0-9_-]{1,64}"),"日志公司无效");
        require(Arrays.asList("OPERATION","API","SIMULATION","CONFIG","NOTIFICATION","DATA_SYNC").contains(kind),"日志类型无效");
        require(Arrays.asList("REAL","SIMULATION","LOCAL").contains(environment),"日志环境无效");
        require(Arrays.asList("AUTH","SERVICE","ORDER","SHIPPING","STOCK","PRODUCT","EXCHANGE","REFUND","NOTIFICATION","CONFIG","DATA_SYNC").contains(category),"日志分类无效");
        String requestId=id(),root=s(metadata,"root_request_id");if(root.isEmpty())root=requestId;require(root.matches("[A-Za-z0-9_-]{1,64}"),"日志链路编号无效");String rootId=root;
        String parent=s(metadata,"parent_request_id"),operation=s(metadata,"operation_id");require(parent.isEmpty()||parent.matches("[A-Za-z0-9_-]{1,64}"),"上级日志编号无效");require(operation.isEmpty()||operation.matches("[A-Za-z0-9_-]{1,64}"),"业务请求编号无效");
        PlatformLogSanitizer.Snapshot payload=PlatformLogSanitizer.snapshot(request);
        return tx().execute(status->{
            GeneratedKeyHolder key=new GeneratedKeyHolder();String sql="INSERT INTO channel_interaction_log(company_code,channel_id,app_id,action,result,operator,record_version,platform_code,entry_kind,category,environment,direction,request_id,root_request_id,parent_request_id,operation_id,interface_name,source_service,trigger_source,request_summary) VALUES (?,?,?,?,'RUNNING',?,1,'TM',?,?,?,?,?,?,?,?,?,?,?,?)";
            db.update(connection->{PreparedStatement p=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);Object[] values={company,channel,app,bounded(action,80),bounded(actor,100),kind,category,environment,"API".equals(kind)?"OUTBOUND":"NOTIFICATION".equals(kind)?"INBOUND":"LOCAL",requestId,rootId,parent,operation,bounded(s(metadata,"interface_name"),128),bounded(s(metadata,"source_service").isEmpty()?"channel":s(metadata,"source_service"),32),bounded(s(metadata,"trigger_source").isEmpty()?"SYSTEM":s(metadata,"trigger_source"),24),json(map("interface",s(metadata,"interface_name"),"simulation","SIMULATION".equals(environment),"notice","报文按需读取"))};for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);return p;},key);
            long log=key.getKey().longValue();db.update("INSERT INTO channel_interaction_payload(company_code,log_id,request_json,request_truncated) VALUES (?,?,?,?)",company,log,payload.json,payload.truncated?1:0);links(company,log,metadata.get("refs"));
            return map("id",log,"company_code",company,"channel_id",channel,"app_id",app,"request_id",requestId,"root_request_id",rootId,"parent_request_id",parent,"operation_id",operation,"environment",environment,"category",category,"operator",actor,"refs",metadata.get("refs"),"source_service",metadata.getOrDefault("source_service","channel"),"trigger_source",metadata.getOrDefault("trigger_source","SYSTEM"));
        });
    }
    public void finish(Map<String,Object> log,String result,String error,Object response,long duration,Integer http,boolean sent){
        require(Arrays.asList("SUCCESS","FAILED","UNKNOWN").contains(result),"日志结果无效");PlatformLogSanitizer.Snapshot payload=PlatformLogSanitizer.snapshot(response,TmallClient.transport().forbidden);String company=s(log,"company_code");long logId=n(log,"id");String code=error==null?"":error;code=code.matches("[A-Za-z0-9_.-]{0,100}")?code:"PLATFORM_ERROR";for(String secret:TmallClient.transport().forbidden)if(code.contains(secret))code="PLATFORM_ERROR";String safeCode=code;
        String platformId=platformId(safeTree(payload.json));for(String secret:TmallClient.transport().forbidden)if(platformId.contains(secret))platformId="";String safePlatformId=platformId;
        tx().execute(status->{int changed=db.update("UPDATE channel_interaction_log SET result=?,error_code=?,response_summary=?,duration_ms=?,http_status=?,request_sent=?,platform_request_id=?,finished_at=NOW() WHERE company_code=? AND id=? AND request_id=? AND result='RUNNING'",result,safeCode,json(map("result",result,"error_code",safeCode,"simulation","SIMULATION".equals(s(log,"environment")),"notice","脱敏报文按需读取")),Math.max(0,Math.min(duration,86400000)),http,sent?1:0,safePlatformId,company,logId,s(log,"request_id"));if(changed==1){db.update("UPDATE channel_interaction_payload SET response_json=?,response_truncated=? WHERE company_code=? AND log_id=?",payload.json,payload.truncated?1:0,company,logId);responseLinks(company,logId,safeTree(payload.json),new int[]{0});}return null;});
    }
    public <T> T call(String method,Object request,Supplier<T> invoke){
        Map<String,Object> parent=CONTEXT.get();require(parent!=null,"平台调用缺少明确日志上下文");Map<String,Object> metadata=new LinkedHashMap<>(parent);metadata.put("parent_request_id",s(parent,"request_id"));metadata.put("interface_name",method);
        boolean simulation="SIMULATION".equals(s(parent,"environment"));Map<String,Object> log=begin(s(parent,"company_code"),n(parent,"channel_id"),n(parent,"app_id"),method,simulation?"SIMULATION":"API",s(parent,"environment"),s(parent,"category"),s(parent,"operator"),metadata,request);long start=System.nanoTime();TmallClient.clearTransport();
        try{T value=invoke.get();TmallClient.Transport transport=TmallClient.transport();finish(log,"SUCCESS","",transport.response==null?value:transport.response,(System.nanoTime()-start)/1000000,transport.httpStatus,transport.sent);return value;}
        catch(RuntimeException failure){TmallClient.Transport transport=TmallClient.transport();String result=failure instanceof TmallClient.Failure?(((TmallClient.Failure)failure).unknown?"UNKNOWN":"FAILED"):failure instanceof IllegalArgumentException?"FAILED":"UNKNOWN";String code=failure instanceof TmallClient.Failure?((TmallClient.Failure)failure).code:"VALIDATION_OR_PERSISTENCE_ERROR";try{finish(log,result,code,transport.response==null?map("error_code",code):transport.response,(System.nanoTime()-start)/1000000,transport.httpStatus,transport.sent);}catch(RuntimeException loggingFailure){/* Durable RUNNING becomes UNKNOWN; never resend a platform write here. */}throw failure;}
        finally{TmallClient.clearTransport();}
    }
    void links(String company,long log,Object raw){if(raw==null)return;require(raw instanceof Collection&&((Collection<?>)raw).size()<=100,"业务关联数量无效");for(Object value:(Collection<?>)raw){require(value instanceof Map,"业务关联无效");Map<?,?> ref=(Map<?,?>)value;String type=String.valueOf(ref.get("type")),number=String.valueOf(ref.get("number"));require(NUMBER_TYPES.contains(type)&&number.length()<=128&&number.matches("[A-Za-z0-9_:.\\-]{1,128}"),"业务单号格式无效");db.update("INSERT IGNORE INTO channel_interaction_business_link(company_code,log_id,number_type,number_value) VALUES (?,?,?,?)",company,log,type,number);}}
    private void responseLinks(String company,long log,JsonNode value,int[] visited){if(value==null||++visited[0]>1000)return;if(value.isObject()){for(String[] spec:new String[][]{{"refund_id","PLATFORM_REFUND"},{"dispute_id","PLATFORM_EXCHANGE"},{"biz_order_id","PLATFORM_OID"},{"bizorder_id","PLATFORM_OID"},{"tid","PLATFORM_TID"},{"oid","PLATFORM_OID"},{"num_iid","PLATFORM_ITEM"},{"sku_id","PLATFORM_SKU"}}){String number=value.path(spec[0]).asText("");if(number.matches("[0-9]{1,64}"))links(company,log,Collections.singletonList(map("type",spec[1],"number",number)));}Iterator<JsonNode> children=value.elements();while(children.hasNext()&&visited[0]<1000)responseLinks(company,log,children.next(),visited);}else if(value.isArray()){int count=0;for(JsonNode node:value){if(++count>50)break;responseLinks(company,log,node,visited);}}}
    private String platformId(Object response){JsonNode node=JSON.valueToTree(response);if(node==null||!node.isObject())return "";List<JsonNode> envelopes=new ArrayList<>();envelopes.add(node);Iterator<Map.Entry<String,JsonNode>> fields=node.fields();while(fields.hasNext()){Map.Entry<String,JsonNode> field=fields.next();if(field.getKey().endsWith("_response")&&field.getValue().isObject())envelopes.add(field.getValue());}for(JsonNode envelope:envelopes){String value=envelope.path("request_id").asText(envelope.path("requestId").asText(""));if(value.matches("[A-Za-z0-9_:.\\-]{1,128}"))return value;}return "";}

    public String category(String action){if(action.startsWith("PRODUCT_")||action.startsWith("taobao.items."))return "PRODUCT";if(action.contains("quantity")||action.contains("inventory.mode")||action.equals("taobao.item.seller.get")||action.equals("taobao.trades.sold.get")||action.startsWith("STOCK_"))return "STOCK";if(action.startsWith("tmall.exchange"))return "EXCHANGE";if(action.contains("SUBSCRIPTION")||action.contains("subscribe"))return "SERVICE";if(action.contains("OAUTH")||action.contains("TOKEN")||action.contains("AUTHORIZATION")||action.contains("SHOP_QUERY"))return "AUTH";return "AUTH";}
    private JsonNode safeTree(String body){try{return JSON.readTree(body);}catch(Exception e){return JSON.createObjectNode();}}
    public String companyForShop(String company,long channel){List<Map<String,Object>> shops=db.queryForList("SELECT company_code,channel_type FROM t_channel WHERE channel_id=? AND UPPER(company_code)=UPPER(?)",channel,company);require(shops.size()==1&&"TM".equals(s(shops.get(0),"channel_type")),"店铺不存在、不属于当前公司或尚未接入该平台");return com.oms.common.tenant.TenantRouting.company(s(shops.get(0),"company_code"));}
    public int summaryDays(String c){return settings==null?summaryDays:(int)n(settings.retention(c),"summary_days");}
    public int payloadDays(String c){return settings==null?payloadDays:(int)n(settings.retention(c),"payload_days");}
    public int purge(String company){long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);int total=0;for(int batch=0;batch<1000;batch++){int[] removed=purgeBatch(company);total+=removed[0];if(removed[0]<1000&&removed[1]<2000||System.nanoTime()>=deadline)break;}return total;}
    private int[] purgeBatch(String company){int summaries=Math.max(1,Math.min(summaryDays(company),3650)),payloads=Math.max(1,Math.min(payloadDays(company),summaries));return tx().execute(status->{int payloadRemoved=db.update("DELETE FROM channel_interaction_payload WHERE company_code=? AND create_time<DATE_SUB(NOW(),INTERVAL ? DAY) LIMIT 2000",company,payloads);List<Long> ids=db.queryForList("SELECT id FROM channel_interaction_log WHERE company_code=? AND record_version=1 AND create_time<DATE_SUB(NOW(),INTERVAL ? DAY) ORDER BY id LIMIT 1000",Long.class,company,summaries);if(ids.isEmpty())return new int[]{0,payloadRemoved};String slots=String.join(",",Collections.nCopies(ids.size(),"?"));List<Object> args=new ArrayList<>();args.add(company);args.addAll(ids);for(String table:Arrays.asList("channel_interaction_payload","channel_interaction_business_link"))db.update("DELETE FROM "+table+" WHERE company_code=? AND log_id IN ("+slots+")",args.toArray());return new int[]{db.update("DELETE FROM channel_interaction_log WHERE company_code=? AND id IN ("+slots+")",args.toArray()),payloadRemoved};});}
}

package com.oms.channel.platform;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Exchange protocol belongs to the credential-owning channel service. */
@Service
public class ExchangePlatformService {
    @Resource private JdbcTemplate db;
    @Resource private ChannelPlatformService platform;
    private static final ObjectMapper JSON=new ObjectMapper();
    static final Map<String,String> METHODS=new LinkedHashMap<>();
    static {METHODS.put("LIST","tmall.exchange.receive.get");METHODS.put("DETAIL","tmall.exchange.get");METHODS.put("REASONS","tmall.exchange.refusereason.get");METHODS.put("AGREE","tmall.exchange.agree");METHODS.put("REFUSE","tmall.exchange.refuse");METHODS.put("RECEIVED","tmall.exchange.returngoods.agree");METHODS.put("CONSIGN","tmall.exchange.consigngoods");}
    private static String s(Map<String,?> m,String k){return m.get(k)==null?"":m.get(k).toString();}
    private static void require(boolean v,String m){if(!v)throw new IllegalArgumentException(m);}
    private String company(String c,long channel){List<String> found=db.queryForList("SELECT company_code FROM t_channel WHERE UPPER(company_code)=UPPER(?) AND channel_id=?",String.class,c,channel);require(found.size()==1,"店铺不存在或不属于当前公司");return found.get(0);}
    public Map<String,Object> capability(String c,long channel){String company=company(c,channel);Map<String,Object> result=platform.detail(company,channel);List<Map<String,Object>> access=db.queryForList("SELECT read_enabled,write_enabled,revision FROM channel_exchange_access WHERE UPPER(company_code)=UPPER(?) AND channel_id=?",company,channel);Map<String,Object> safe=new LinkedHashMap<>();for(String key:Arrays.asList("channel_id","channel_name","auth_label","availability","simulation","can_query"))safe.put(key,result.get(key));safe.put("read_enabled",access.isEmpty()?0:access.get(0).get("read_enabled"));safe.put("write_enabled",access.isEmpty()?0:access.get(0).get("write_enabled"));safe.put("revision",access.isEmpty()?0:access.get(0).get("revision"));return safe;}
    public void access(String c,long channel,Map<String,Object> body,String actor){String company=company(c,channel);platform.detail(company,channel);long revision=Long.parseLong(s(body,"revision"));int read=Integer.parseInt(s(body,"read_enabled")),write=Integer.parseInt(s(body,"write_enabled"));require((read==0||read==1)&&(write==0||write==1)&&write<=read,"换货接口开关无效");int changed;if(revision==0)changed=db.update("INSERT IGNORE INTO channel_exchange_access(company_code,channel_id,read_enabled,write_enabled,updated_by) VALUES (?,?,?,?,?)",company,channel,read,write,actor);else changed=db.update("UPDATE channel_exchange_access SET read_enabled=?,write_enabled=?,updated_by=?,revision=revision+1 WHERE UPPER(company_code)=UPPER(?) AND channel_id=? AND revision=?",read,write,actor,company,channel,revision);require(changed==1,"换货权限配置已变化，请刷新");}
    @SuppressWarnings("unchecked") public Map<String,Object> call(String c,long channel,Map<String,Object> body){
        String company=company(c,channel),operation=s(body,"operation");require(METHODS.containsKey(operation),"不支持的换货操作");boolean write=Arrays.asList("AGREE","REFUSE","RECEIVED","CONSIGN").contains(operation);
        Map<String,Object> capability=capability(company,channel);require(Boolean.TRUE.equals(capability.get("can_query")),"店铺授权未就绪，请在渠道授权中核对");require("1".equals(s(capability,"read_enabled")),"尚未启用店铺换货查询");require(!write||"1".equals(s(capability,"write_enabled")),"尚未启用店铺换货写操作");
        require(body.get("args") instanceof Map,"换货参数无效");Map<String,String> args=new LinkedHashMap<>();for(Map.Entry<?,?> e:((Map<?,?>)body.get("args")).entrySet()){require(e.getKey() instanceof String&&e.getValue()!=null,"换货参数无效");args.put(e.getKey().toString(),e.getValue().toString());}
        Set<String> allowed=new HashSet<>(Arrays.asList("fields","start_gmt_modified_time","end_gmt_modifed_time","start_created_time","end_created_time","page_no","page_size","refund_id_array","biz_order_id","dispute_status_array","speed_exchange","dispute_id","address_id","complete_address","mobile","post","leave_message","seller_refuse_reason_id","logistics_company_name","logistics_type","dispute_type","logistics_no"));require(allowed.containsAll(args.keySet()),"换货接口包含未开放参数");
        if(!"LIST".equals(operation))require(args.getOrDefault("dispute_id","").matches("[1-9][0-9]{0,63}"),"平台换货单号无效");
        if("LIST".equals(operation)){int page=Integer.parseInt(args.getOrDefault("page_no","1")),size=Integer.parseInt(args.getOrDefault("page_size","30"));require(page>=1&&page<=100000&&size>=1&&size<=50,"换货分页范围无效");require(args.containsKey("start_gmt_modified_time")&&args.containsKey("end_gmt_modifed_time"),"换货同步需要明确时间范围");}
        require(args.getOrDefault("fields", "").length()>0&&args.get("fields").length()<=2000,"请明确指定平台返回字段");
        if("AGREE".equals(operation))require(args.getOrDefault("address_id", "").matches("[1-9][0-9]{0,18}"),"请指定平台退货地址 ID");
        if("REFUSE".equals(operation))require(args.getOrDefault("seller_refuse_reason_id", "").matches("[1-9][0-9]{0,18}"),"请选择平台拒绝原因");
        if("CONSIGN".equals(operation))require(Arrays.asList("100","200").contains(args.get("logistics_type"))&&!args.getOrDefault("logistics_no", "").isEmpty()&&!args.getOrDefault("logistics_company_name", "").isEmpty(),"换出发货物流资料不完整");
        @SuppressWarnings("unchecked") Map<String,Object> context=body.get("log_context") instanceof Map?(Map<String,Object>)body.get("log_context"):Collections.emptyMap();return platform.exchange(company,channel,METHODS.get(operation),args,operation,write,context);
    }
    /** Local fixtures use only the trusted application's simulation flag, never a request flag. */
    static Map<String,Object> simulated(JdbcTemplate db,String company,long channel,String operation,Map<String,String> args){
        List<Map<String,Object>> rows;
        if("LIST".equals(operation)){int size=Integer.parseInt(args.getOrDefault("page_size","30")),page=Integer.parseInt(args.getOrDefault("page_no","1"));rows=db.queryForList("SELECT payload FROM channel_exchange_sim WHERE UPPER(company_code)=UPPER(?) AND channel_id=? AND modified>=? AND modified<=? ORDER BY modified,dispute_id LIMIT ? OFFSET ?",company,channel,args.get("start_gmt_modified_time"),args.get("end_gmt_modifed_time"),size,(page-1)*size);List<Map<String,Object>> records=new ArrayList<>();for(Map<String,Object> row:rows)records.add(parse(row.get("payload")));return ChannelPlatformService.map("records",records,"page_results",records.size(),"simulation",true);}
        rows=db.queryForList("SELECT payload FROM channel_exchange_sim WHERE UPPER(company_code)=UPPER(?) AND channel_id=? AND dispute_id=?",company,channel,args.get("dispute_id"));require(rows.size()==1,"模拟平台没有该换货申请");Map<String,Object> record=parse(rows.get(0).get("payload"));
        if("REASONS".equals(operation))return ChannelPlatformService.map("reasons",Arrays.asList(ChannelPlatformService.map("reason_id","4002","reason_text","双方协商后不满足换货条件")),"simulation",true);
        if(!"DETAIL".equals(operation)){String state=s(record,"status");if("AGREE".equals(operation)){require("1".equals(state),"平台当前状态不允许同意");record.put("status","2");}else if("REFUSE".equals(operation)){require("1".equals(state),"平台当前状态不允许拒绝");require(args.containsKey("seller_refuse_reason_id"),"请选择平台允许的拒绝原因");record.put("status","4");}else if("RECEIVED".equals(operation)){require("3".equals(state),"平台尚未进入待确认收货");record.put("status","12");}else if("CONSIGN".equals(operation)){require("12".equals(state),"平台尚未允许换出发货");require(args.getOrDefault("logistics_no","").startsWith("TEST-"),"模拟换出只允许测试运单");record.put("status","13");record.put("seller_logistic_no",args.get("logistics_no"));}LocalDateTime next=LocalDateTime.now().withNano(0),previous=LocalDateTime.parse(s(record,"modified"),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));if(!next.isAfter(previous))next=previous.plusSeconds(1);record.put("modified",next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));db.update("UPDATE channel_exchange_sim SET payload=?,modified=? WHERE UPPER(company_code)=UPPER(?) AND channel_id=? AND dispute_id=?",json(record),s(record,"modified"),company,channel,args.get("dispute_id"));}
        return ChannelPlatformService.map("exchange",record,"simulation",true);
    }
    static Map<String,Object> normalize(JsonNode response,String method,String operation){
        JsonNode root=response.path(method.replace('.', '_')+"_response");
        if(!root.isObject())throw new TmallClient.Failure("EXCHANGE_INVALID_RESPONSE",true);
        if("LIST".equals(operation)){
            JsonNode items=root.path("results");if(items.isObject())items=items.path("exchange");
            int count=root.path("page_results").asInt(-1);
            if(count<0||(!items.isArray()&&count!=0))throw new TmallClient.Failure("EXCHANGE_LIST_INVALID",true);
            List<Object> records=items.isArray()?JSON.convertValue(items,List.class):Collections.emptyList();
            if(records.size()!=count)throw new TmallClient.Failure("EXCHANGE_LIST_COUNT",true);
            return ChannelPlatformService.map("records",records,"page_results",count,"simulation",false);
        }
        JsonNode result=root.path("result");
        if("REASONS".equals(operation)){
            JsonNode items=result.path("results");if(items.isObject())items=items.path("reason");
            if(!items.isArray())throw new TmallClient.Failure("EXCHANGE_REASONS_INVALID",true);
            return ChannelPlatformService.map("reasons",JSON.convertValue(items,List.class),"simulation",false);
        }
        if(!result.has("success"))throw new TmallClient.Failure("EXCHANGE_RESULT_UNKNOWN",true);
        if(!result.path("success").asBoolean()){
            String code=result.path("msg_code").asText("EXCHANGE_REJECTED");
            throw new TmallClient.Failure(code.matches("[A-Za-z0-9_.-]{1,100}")?code:"EXCHANGE_REJECTED",false);
        }
        JsonNode exchange=result.path("exchange");
        if(!exchange.isObject()||exchange.path("dispute_id").asText().isEmpty())throw new TmallClient.Failure("EXCHANGE_RESULT_INCOMPLETE",true);
        return ChannelPlatformService.map("exchange",JSON.convertValue(exchange,Map.class),"simulation",false);
    }
    static Map<String,Object> parse(Object value){try{return JSON.readValue(String.valueOf(value),Map.class);}catch(Exception e){throw new IllegalArgumentException("换货数据无法解析");}}
    static String json(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("换货数据无法编码");}}
}

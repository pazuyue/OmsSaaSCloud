package com.oms.channel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.annotation.Resource;
import java.net.URI;
import java.net.URLDecoder;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ChannelPlatformService {
    @Resource private JdbcTemplate db;
    @Resource private PlatformTransactionManager transactionManager;
    @Resource private PlatformSecrets secrets;
    @Resource private TmallClient client;
    @Value("${oms.channel.simulation-enabled:false}") private boolean simulationEnabled;
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String APP_PUBLIC="id,name,app_key,redirect_uri,article_code,item_code,renewal_url,enabled,simulation_mode,(secret_cipher IS NOT NULL) AS secret_configured";
    public static Map<String,Object> map(Object... values) {Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)m.put(values[i].toString(),values[i+1]);return m;}
    private Map<String,Object> one(String sql,Object... args) {List<Map<String,Object>> rows=db.queryForList(sql,args);if(rows.isEmpty())throw new IllegalArgumentException("记录不存在或不属于当前公司");return rows.get(0);}
    private static String str(Map<String,Object> m,String key) {return m.get(key)==null?"":m.get(key).toString().trim();}
    private static long number(Object value) {try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new IllegalArgumentException("编号或数值无效");}}
    private static String field(Map<String,Object> m,String key,int length,boolean required) {String s=str(m,key);if(s.length()>length||(required&&s.isEmpty()))throw new IllegalArgumentException(key+" 不能为空或超出长度限制");return s;}
    private static void require(boolean ok,String message) {if(!ok)throw new IllegalArgumentException(message);}
    private TransactionTemplate tx() {return new TransactionTemplate(transactionManager);}
    private Map<String,Object> shop(String company,long id) {return one("SELECT * FROM t_channel WHERE company_code=? AND channel_id=?",company,id);}
    private Map<String,Object> app(String company,long id) {return one("SELECT * FROM channel_platform_app WHERE company_code=? AND id=?",company,id);}
    private Map<String,Object> binding(String company,long id) {return one("SELECT * FROM channel_platform_binding WHERE company_code=? AND channel_id=?",company,id);}
    private void usableShop(Map<String,Object> shop) {require("TM".equals(str(shop,"channel_type")),"首期仅支持天猫平台授权");require(number(shop.get("enabled"))==1&&number(shop.get("to_channel_enabled"))==1,"请先启用店铺及平台对接");}
    private static boolean simulated(Map<String,Object> app) {return "1".equals(str(app,"simulation_mode"));}
    private boolean configured(Map<String,Object> app) {return simulated(app)?simulationEnabled&&secrets.ready():!str(app,"app_key").isEmpty()&&!str(app,"secret_cipher").isEmpty()&&!str(app,"redirect_uri").isEmpty()&&secrets.ready();}
    private void usableApp(Map<String,Object> app) {require(number(app.get("enabled"))==1&&configured(app),"平台应用未启用或配置不完整");}
    public List<Map<String,Object>> apps(String company) {List<Map<String,Object>> rows=db.queryForList("SELECT "+APP_PUBLIC+",(SELECT COUNT(*) FROM channel_platform_binding b WHERE b.app_id=channel_platform_app.id AND b.company_code=channel_platform_app.company_code) AS bound_shops FROM channel_platform_app WHERE company_code=? ORDER BY id DESC",company);for(Map<String,Object> r:rows)r.put("encryption_ready",secrets.ready());return rows;}
    public long saveApp(String company,Map<String,Object> body,String user) {
        String name=field(body,"name",100,true), key=field(body,"app_key",100,false), secret=field(body,"secret",500,false);
        String redirect=field(body,"redirect_uri",500,false),article=field(body,"article_code",100,false),item=field(body,"item_code",100,false),renewal=field(body,"renewal_url",500,false);
        int enabled=(int)number(body.getOrDefault("enabled",0));require(enabled==0||enabled==1,"启用状态无效");
        if(!redirect.isEmpty()) {URI u=uri(redirect);require("https".equals(u.getScheme())&&"/channel-authorize".equals(u.getPath())&&u.getQuery()==null&&u.getFragment()==null,"回调地址必须是 OMS 的 HTTPS 域名加 /channel-authorize");}
        if(!renewal.isEmpty()) {URI u=uri(renewal);require("https".equals(u.getScheme())&&"fuwu.taobao.com".equalsIgnoreCase(u.getHost()),"订购地址必须是淘宝服务市场 HTTPS 页面");}
        require(key.isEmpty()||key.matches("[A-Za-z0-9_-]{1,100}"),"AppKey 格式无效");
        require(article.isEmpty()||article.matches("[A-Za-z0-9_-]{1,100}"),"服务商品编码格式无效");
        require(item.isEmpty()||item.matches("[A-Za-z0-9_-]{1,100}"),"收费项目编码格式无效");
        return tx().execute(status->{
            Long id=body.get("id")==null?null:number(body.get("id"));String cipher=secret.isEmpty()?null:secrets.encrypt(secret);
            if(id!=null) {
                Map<String,Object> old=one("SELECT * FROM channel_platform_app WHERE company_code=? AND id=? FOR UPDATE",company,id);
                require(!simulated(old),"模拟应用由本地演示配置维护，真实接入请新建平台应用");
                require(db.queryForObject("SELECT COUNT(*) FROM channel_platform_binding WHERE company_code=? AND app_id=? AND operation_until>NOW()",Integer.class,company,id)==0,"应用正在交互，请稍后修改");
                boolean referenced=db.queryForObject("SELECT COUNT(*) FROM channel_platform_binding WHERE company_code=? AND app_id=?",Integer.class,company,id)>0;
                require(!referenced||(key.equals(str(old,"app_key"))&&redirect.equals(str(old,"redirect_uri"))&&article.equals(str(old,"article_code"))&&item.equals(str(old,"item_code"))&&secret.isEmpty()),"应用已绑定店铺，凭据和收费项目不可直接改写；请创建新配置并重新授权");
                if(cipher==null)cipher=(String)old.get("secret_cipher");
            }
            if(enabled==1)require(!key.isEmpty()&&cipher!=null&&!redirect.isEmpty()&&secrets.ready(),"启用应用前请补齐 AppKey、AppSecret、授权回调地址");
            if(id==null) {
                db.update("INSERT INTO channel_platform_app(company_code,name,app_key,secret_cipher,redirect_uri,article_code,item_code,renewal_url,enabled) VALUES(?,?,?,?,?,?,?,?,?)",company,name,key,cipher,redirect,article,item,renewal,enabled);
                id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            } else db.update("UPDATE channel_platform_app SET name=?,app_key=?,secret_cipher=?,redirect_uri=?,article_code=?,item_code=?,renewal_url=?,enabled=? WHERE company_code=? AND id=?",name,key,cipher,redirect,article,item,renewal,enabled,company,id);
            audit(company,null,id,"APP_SAVE",user,map("name",name,"enabled",enabled));return id;
        });
    }
    private static URI uri(String value) {try{URI u=new URI(value);require(u.getHost()!=null&&u.getUserInfo()==null&&(u.getPort()==-1||u.getPort()==443),"地址格式无效");return u;}catch(Exception e){throw new IllegalArgumentException("地址格式无效");}}
    public void bind(String company,long id,Map<String,Object> body,String user) {
        long appId=number(body.get("app_id"));String expected=field(body,"expected_shop_id",64,true);require(expected.matches("[0-9]{1,32}"),"请输入平台店铺的数字 sid，不是卖家用户 ID");
        tx().execute(status->{
            one("SELECT id FROM channel_platform_app WHERE company_code=? AND id=? FOR UPDATE",company,appId);
            Map<String,Object> s=one("SELECT * FROM t_channel WHERE company_code=? AND channel_id=? FOR UPDATE",company,id);usableShop(s);
            List<Map<String,Object>> old=db.queryForList("SELECT * FROM channel_platform_binding WHERE company_code=? AND channel_id=? FOR UPDATE",company,id);
            if(!old.isEmpty()) {require(!future(old.get(0).get("operation_until")),"店铺正在交互，请稍后修改");require(str(old.get(0),"token_cipher").isEmpty(),"请先停用本地授权，再更换应用或店铺绑定");}
            if(old.isEmpty()) db.update("INSERT INTO channel_platform_binding(channel_id,company_code,app_id,expected_shop_id) VALUES(?,?,?,?)",id,company,appId,expected);
            else db.update("UPDATE channel_platform_binding SET app_id=?,expected_shop_id=?,auth_status='UNAUTHORIZED',seller_id=NULL,seller_nick=NULL,shop_title=NULL,service_status='UNKNOWN',service_expires_at=NULL,service_checked_at=NULL,operation_key=NULL,operation_until=NULL WHERE company_code=? AND channel_id=?",appId,expected,company,id);
            db.update("DELETE FROM channel_oauth_state WHERE company_code=? AND channel_id=?",company,id);
            audit(company,id,appId,"BIND",user,map("expected_shop_id",expected));return null;
        });
    }
    public Map<String,Object> detail(String company,long id) {
        Map<String,Object> s=shop(company,id),result=map("channel_id",id,"channel_name",s.get("channel_name"),"channel_type",s.get("channel_type"));
        List<Map<String,Object>> rows=db.queryForList("SELECT b.*,a.name AS app_name,a.enabled AS app_enabled,a.app_key,a.redirect_uri,a.article_code,a.item_code,a.renewal_url,a.simulation_mode,(a.secret_cipher IS NOT NULL) AS secret_configured FROM channel_platform_binding b JOIN channel_platform_app a ON a.id=b.app_id AND a.company_code=b.company_code WHERE b.company_code=? AND b.channel_id=?",company,id);
        if(rows.isEmpty()){result.putAll(map("auth_label","待配置","service_label","未核验","availability","未绑定平台应用","can_authorize",false,"can_refresh",false));return result;}
        Map<String,Object> b=rows.get(0);boolean hasToken=!str(b,"token_cipher").isEmpty();
        boolean appReady=number(b.get("app_enabled"))==1&&!str(b,"app_key").isEmpty()&&!str(b,"redirect_uri").isEmpty()&&("1".equals(str(b,"secret_configured"))||Boolean.TRUE.equals(b.get("secret_configured")))&&secrets.ready();
        boolean simulation=simulated(b);
        if(simulation)appReady=number(b.get("app_enabled"))==1&&simulationEnabled&&secrets.ready();
        int days=((Number)b.get("reminder_days")).intValue();String auth="未授权",service="未核验";
        if("LOCAL_DISABLED".equals(b.get("auth_status")))auth="本地已停用";
        else if("INVALID".equals(b.get("auth_status")))auth="授权失效";
        else if(hasToken)auth=!future(b.get("token_expires_at"))?"已过期":soon(b.get("token_expires_at"),days)?"即将到期":"已授权";
        if("SUBSCRIBED".equals(b.get("service_status")))service=!future(b.get("service_expires_at"))?"已到期":soon(b.get("service_expires_at"),days)?"即将到期":"服务有效";
        else if("UNSUBSCRIBED".equals(b.get("service_status")))service="未订购";
        boolean authValid=hasToken&&future(b.get("token_expires_at"))&&"AUTHORIZED".equals(b.get("auth_status"));
        boolean fresh=b.get("service_checked_at")!=null&&instant(b.get("service_checked_at")).isAfter(Instant.now().minusSeconds(86400));
        boolean shopReady="TM".equals(s.get("channel_type"))&&number(s.get("enabled"))==1&&number(s.get("to_channel_enabled"))==1;
        boolean serviceConfigured=!str(b,"article_code").isEmpty()&&!str(b,"item_code").isEmpty();
        String available=!shopReady?"店铺未启用平台对接":!appReady?"应用未配置完整或已停用":!authValid?"请完成店铺授权":!serviceConfigured?"请配置服务商品和收费项目":!fresh?"请核验服务订购状态":!future(b.get("service_expires_at"))?"请订购或续费服务":"授权与服务均有效";
        if(simulation&&"授权与服务均有效".equals(available))available="模拟对接成功（授权与服务有效）";
        boolean busy=future(b.get("operation_until"));
        b.remove("token_cipher");b.remove("refresh_cipher");b.remove("operation_key");
        result.putAll(b);result.putAll(map("auth_label",auth,"service_label",service,"availability",available,"simulation",simulation,"can_simulate",simulation&&appReady&&shopReady&&!busy,"service_stale",!fresh,"can_authorize",!simulation&&appReady&&shopReady&&!busy,"can_refresh",appReady&&shopReady&&!busy&&future(b.get("refresh_expires_at")),"busy",busy,"binding_locked",hasToken,"can_query_subscription",appReady&&shopReady&&serviceConfigured&&!busy&&!str(b,"seller_nick").isEmpty(),"can_query",appReady&&shopReady&&!busy&&authValid));
        return dates(result);
    }
    public List<Map<String,Object>> summaries(String company,List<Long> ids) {require(ids.size()<=100,"每次最多查询 100 家店铺");List<Map<String,Object>> rows=new ArrayList<>();for(Long id:ids)rows.add(detail(company,id));return rows;}
    public List<Map<String,Object>> reminders(String company) {
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> b:db.queryForList("SELECT channel_id FROM channel_platform_binding WHERE company_code=? AND reminder_enabled=1 AND ((token_expires_at IS NOT NULL AND token_expires_at<=DATE_ADD(NOW(),INTERVAL reminder_days DAY)) OR (service_expires_at IS NOT NULL AND service_expires_at<=DATE_ADD(NOW(),INTERVAL reminder_days DAY))) ORDER BY update_time DESC LIMIT 200",company))out.add(detail(company,number(b.get("channel_id"))));
        return out;
    }
    public void reminder(String company,long id,int days,boolean enabled,String user) {require(days>=1&&days<=90,"提前提醒天数必须为 1–90");binding(company,id);db.update("UPDATE channel_platform_binding SET reminder_days=?,reminder_enabled=? WHERE company_code=? AND channel_id=?",days,enabled?1:0,company,id);audit(company,id,null,"REMINDER_SAVE",user,map("days",days,"enabled",enabled));}
    public String authorize(String company,long id,long userId,String user) {
        return tx().execute(status->{
            usableShop(shop(company,id));Map<String,Object>b=binding(company,id),a=one("SELECT * FROM channel_platform_app WHERE company_code=? AND id=? FOR UPDATE",company,b.get("app_id"));usableApp(a);
            require(!simulated(a),"模拟店铺请使用模拟授权，不会跳转真实天猫平台");
            b=one("SELECT * FROM channel_platform_binding WHERE company_code=? AND channel_id=? FOR UPDATE",company,id);require(!future(b.get("operation_until")),"店铺正在交互，请稍后重试");
            String state=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
            db.update("DELETE FROM channel_oauth_state WHERE expires_at<NOW() OR (company_code=? AND channel_id=?)",company,id);
            db.update("INSERT INTO channel_oauth_state(state_hash,company_code,channel_id,app_id,user_id,expires_at) VALUES(?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 10 MINUTE))",TmallClient.digest("SHA-256",state),company,id,b.get("app_id"),userId);
            audit(company,id,number(b.get("app_id")),"AUTHORIZE_START",user,map("expected_shop_id",b.get("expected_shop_id")));
            return "https://oauth.taobao.com/authorize?response_type=code&view=tmall&client_id="+TmallClient.encode(str(a,"app_key"))+"&redirect_uri="+TmallClient.encode(str(a,"redirect_uri"))+"&state="+state;
        });
    }
    public long complete(String company,long userId,String state,String code,String user) {
        require(state!=null&&state.matches("[a-f0-9]{64}")&&code!=null&&!code.isEmpty()&&code.length()<=2048,"授权回调参数无效");
        Map<String,Object> s=tx().execute(status->{
            Map<String,Object> found=one("SELECT * FROM channel_oauth_state WHERE state_hash=? AND company_code=? AND user_id=? AND consumed=0 AND expires_at>NOW() FOR UPDATE",TmallClient.digest("SHA-256",state),company,userId);
            db.update("UPDATE channel_oauth_state SET consumed=1 WHERE state_hash=?",found.get("state_hash"));return found;
        });
        long id=number(s.get("channel_id"));
        interact(company,id,"OAUTH_EXCHANGE",user,(b,a)->{
            require(number(b.get("app_id"))==number(s.get("app_id")),"应用已变更，请重新发起授权");
            require(!simulated(a),"模拟应用不接受真实平台授权回调");
            Map<String,String> args=credentials(a);args.put("grant_type","authorization_code");args.put("code",code);args.put("redirect_uri",str(a,"redirect_uri"));
            JsonNode token=client.token(args);saveToken(company,id,b,a,token);return map("shop_id",b.get("expected_shop_id"),"authorization","verified");
        });return id;
    }
    private Map<String,String> credentials(Map<String,Object> app) {Map<String,String> p=new HashMap<>();p.put("client_id",str(app,"app_key"));p.put("client_secret",secrets.decrypt(str(app,"secret_cipher")));return p;}
    public void simulate(String company,long id,String user) {
        interact(company,id,"SIMULATED_AUTHORIZATION",user,(b,a)->{
            require(simulated(a)&&simulationEnabled,"此店铺不是本地模拟配置，不能模拟真实授权");
            saveToken(company,id,b,a,simulationToken(company,id));
            require(db.update("UPDATE channel_platform_binding SET service_status='SUBSCRIBED',service_expires_at=?,service_checked_at=NOW() WHERE company_code=? AND channel_id=? AND operation_key=? AND operation_until>NOW()",Timestamp.from(Instant.now().plusSeconds(365L*86400)),company,id,b.get("operation_key"))==1,"模拟操作已超时，请重试");
            return map("shop_id",b.get("expected_shop_id"),"authorization","simulated","subscription","simulated");
        });
    }
    private JsonNode simulationToken(String company,long id) {
        return new ObjectMapper().valueToTree(map("access_token","SIMULATED_"+UUID.randomUUID(),"refresh_token","SIMULATED_REFRESH_"+UUID.randomUUID(),"expires_in",30L*86400,"re_expires_in",60L*86400,"taobao_user_id","SIMULATED_SELLER_"+id,"taobao_user_nick",shop(company,id).get("channel_name")));
    }
    public void refresh(String company,long id,String user) {interact(company,id,"TOKEN_REFRESH",user,(b,a)->{
        require(future(b.get("refresh_expires_at"))&&!str(b,"refresh_cipher").isEmpty(),"平台未提供有效刷新令牌，请重新授权");
        if(simulated(a)){saveToken(company,id,b,a,simulationToken(company,id));return map("authorization","simulated_refresh");}
        Map<String,String> args=credentials(a);args.put("grant_type","refresh_token");args.put("refresh_token",secrets.decrypt(str(b,"refresh_cipher")));
        saveToken(company,id,b,a,client.token(args));return map("authorization","refreshed");
    });}
    private void saveToken(String company,long id,Map<String,Object>b,Map<String,Object>a,JsonNode token) {
        String access=token.path("access_token").asText("");long expiry=token.path("expires_in").asLong(0);
        require(!access.isEmpty()&&expiry>0&&expiry<315360000,"平台未返回有效授权令牌或有效期");
        JsonNode shop=queryShop(a,access,b);require(str(b,"expected_shop_id").equals(shop.path("sid").asText()),"授权店铺与配置的店铺 ID 不一致，请使用正确店铺主账号授权");
        require(!token.path("sub_taobao_user_id").asText("").matches("[1-9][0-9]*"),"请使用店铺主账号授权");
        String seller=token.path("taobao_user_id").asText(token.path("taobao_open_uid").asText("")),nick=token.path("taobao_user_nick").asText("");
        require(!seller.isEmpty()&&!nick.isEmpty(),"平台未返回完整店铺账号信息");
        try{nick=URLDecoder.decode(nick,"UTF-8");}catch(Exception e){throw new IllegalArgumentException("平台账号信息格式无效");}
        require(nick.length()<=200&&seller.length()<=100&&shop.path("title").asText().length()<=200,"平台店铺信息超出长度限制");
        long refreshSeconds=token.path("re_expires_in").asLong(0);boolean canRefresh=refreshSeconds>0&&refreshSeconds<315360000&&!token.path("refresh_token").asText("").isEmpty();
        int changed=db.update("UPDATE channel_platform_binding SET token_cipher=?,refresh_cipher=?,token_expires_at=?,refresh_expires_at=?,auth_status='AUTHORIZED',seller_id=?,seller_nick=?,shop_title=? WHERE company_code=? AND channel_id=? AND operation_key=? AND operation_until>NOW()",secrets.encrypt(access),canRefresh?secrets.encrypt(token.path("refresh_token").asText()):null,Timestamp.from(Instant.now().plusSeconds(expiry)),canRefresh?Timestamp.from(Instant.now().plusSeconds(refreshSeconds)):null,seller,nick,shop.path("title").asText(),company,id,b.get("operation_key"));
        require(changed==1,"交互已超时，请重新授权");
    }
    private JsonNode queryShop(Map<String,Object>a,String token,Map<String,Object>b) {
        if(simulated(a)){require(simulationEnabled,"本地模拟功能未启用");return new ObjectMapper().valueToTree(map("sid",b.get("expected_shop_id"),"title",shop(str(b,"company_code"),number(b.get("channel_id"))).get("channel_name")));}
        JsonNode response=client.api(str(a,"app_key"),secrets.decrypt(str(a,"secret_cipher")),token,"taobao.shop.seller.get",Collections.singletonMap("fields","sid,title"));JsonNode shop=response.path("shop_seller_get_response").path("shop");require(shop.hasNonNull("sid"),"平台店铺查询响应不完整");return shop;
    }
    public void query(String company,long id,String kind,String user) {
        require("shop".equals(kind)||"subscription".equals(kind),"不支持的查询类型");
        interact(company,id,"shop".equals(kind)?"taobao.shop.seller.get":"taobao.vas.subscribe.get",user,(b,a)->{
            if("shop".equals(kind)) {
                require(future(b.get("token_expires_at"))&&"AUTHORIZED".equals(b.get("auth_status")),"授权已失效，请重新授权");
                JsonNode s=queryShop(a,secrets.decrypt(str(b,"token_cipher")),b);require(str(b,"expected_shop_id").equals(s.path("sid").asText()),"平台店铺身份不一致");
                require(db.update("UPDATE channel_platform_binding SET shop_title=? WHERE company_code=? AND channel_id=? AND operation_key=? AND operation_until>NOW()",s.path("title").asText(),company,id,b.get("operation_key"))==1,"交互已超时，请重新查询");return map("shop_id",s.path("sid").asText(),"title",s.path("title").asText());
            }
            require(!str(b,"seller_nick").isEmpty(),"请先完成一次店铺授权以核对账号");require(!str(a,"article_code").isEmpty()&&!str(a,"item_code").isEmpty(),"请先配置服务商品编码和收费项目编码");
            Map<String,String> args=new HashMap<>();args.put("article_code",str(a,"article_code"));args.put("nick",str(b,"seller_nick"));
            JsonNode response;
            if(simulated(a)) {
                Object deadline=b.get("service_expires_at");
                require(deadline!=null,"请先完成模拟授权");
                response=new ObjectMapper().valueToTree(map("vas_subscribe_get_response",map("article_user_subscribes",map("article_user_subscribe",Collections.singletonList(map("item_code",a.get("item_code"),"deadline",LocalDateTime.ofInstant(instant(deadline),ZoneId.of("Asia/Shanghai")).format(DATE)))))));
            } else response=client.api(str(a,"app_key"),secrets.decrypt(str(a,"secret_cipher")),null,"taobao.vas.subscribe.get",args);
            JsonNode root=response.get("vas_subscribe_get_response");require(root!=null&&root.isObject(),"平台订购查询响应不完整");
            JsonNode items=root.path("article_user_subscribes").path("article_user_subscribe");Timestamp deadline=null;
            if(!items.isMissingNode()&&!items.isNull())require(items.isArray(),"平台订购列表格式无效");
            if(items.isArray())for(JsonNode item:items)if(str(a,"item_code").equals(item.path("item_code").asText())) {
                Timestamp value=Timestamp.valueOf(LocalDateTime.parse(item.path("deadline").asText(),DATE));if(deadline==null||value.after(deadline))deadline=value;
            }
            require(db.update("UPDATE channel_platform_binding SET service_status=?,service_expires_at=?,service_checked_at=NOW() WHERE company_code=? AND channel_id=? AND operation_key=? AND operation_until>NOW()",deadline==null?"UNSUBSCRIBED":"SUBSCRIBED",deadline,company,id,b.get("operation_key"))==1,"交互已超时，请重新查询");
            return map("item_code",a.get("item_code"),"deadline",deadline==null?null:deadline.toString());
        });
    }
    public void disable(String company,long id,String user) {tx().execute(status->{Map<String,Object>b=one("SELECT * FROM channel_platform_binding WHERE company_code=? AND channel_id=? FOR UPDATE",company,id);require(!future(b.get("operation_until")),"店铺正在交互，请稍后停用");db.update("UPDATE channel_platform_binding SET token_cipher=NULL,refresh_cipher=NULL,token_expires_at=NULL,refresh_expires_at=NULL,auth_status='LOCAL_DISABLED' WHERE company_code=? AND channel_id=?",company,id);db.update("DELETE FROM channel_oauth_state WHERE company_code=? AND channel_id=?",company,id);audit(company,id,number(b.get("app_id")),"LOCAL_DISABLE",user,map("result","本地凭据已清除，平台侧撤销需在淘宝操作"));return null;});}
    private interface Action {Map<String,Object> run(Map<String,Object> binding,Map<String,Object> app);}
    private void interact(String company,long id,String action,String user,Action call) {
        Map<String,Object> b=tx().execute(status->{
            usableShop(shop(company,id));Map<String,Object> initial=binding(company,id);
            usableApp(one("SELECT * FROM channel_platform_app WHERE company_code=? AND id=? FOR UPDATE",company,initial.get("app_id")));
            String lease=UUID.randomUUID().toString().replace("-","");
            require(db.update("UPDATE channel_platform_binding SET operation_key=?,operation_until=DATE_ADD(NOW(),INTERVAL 90 SECOND) WHERE company_code=? AND channel_id=? AND app_id=? AND (operation_until IS NULL OR operation_until<NOW())",lease,company,id,initial.get("app_id"))==1,"店铺正在交互，请勿重复操作");
            return binding(company,id);
        });
        long log=0,start=System.currentTimeMillis();
        try {
            log=insertLog(company,id,number(b.get("app_id")),action,"RUNNING",user,map("shop_id",b.get("expected_shop_id")));
            Map<String,Object> response=call.run(b,app(company,number(b.get("app_id"))));
            if(simulated(app(company,number(b.get("app_id")))))response.put("simulation",true);
            db.update("UPDATE channel_interaction_log SET result='SUCCESS',response_summary=?,duration_ms=? WHERE id=? AND company_code=?",json(response),System.currentTimeMillis()-start,log,company);
        } catch(RuntimeException e) {
            String code=e instanceof TmallClient.Failure?((TmallClient.Failure)e).code:"VALIDATION_OR_PERSISTENCE_ERROR";
            boolean unknown=!(e instanceof IllegalArgumentException)&&(!(e instanceof TmallClient.Failure)||((TmallClient.Failure)e).unknown);
            if(log!=0)db.update("UPDATE channel_interaction_log SET result=?,error_code=?,response_summary=?,duration_ms=? WHERE id=? AND company_code=?",unknown?"UNKNOWN":"FAILED",code,json(map("message",e instanceof IllegalArgumentException?e.getMessage():unknown?"平台响应未确认或结果未成功保存":"平台返回失败，请按错误码排查")),System.currentTimeMillis()-start,log,company);
            if(Arrays.asList("27","isv.session-expired","invalid_session").contains(code)||("TOKEN_REFRESH".equals(action)&&"invalid_grant".equals(code)))db.update("UPDATE channel_platform_binding SET auth_status='INVALID' WHERE company_code=? AND channel_id=? AND operation_key=?",company,id,b.get("operation_key"));
            if(e instanceof IllegalArgumentException||e instanceof TmallClient.Failure)throw e;
            throw new IllegalArgumentException("交互结果未确认，请查看日志后重试");
        } finally {db.update("UPDATE channel_platform_binding SET operation_key=NULL,operation_until=NULL WHERE company_code=? AND channel_id=? AND operation_key=?",company,id,b.get("operation_key"));}
    }
    private long insertLog(String company,Long channel,Long app,String action,String result,String user,Map<String,Object> request) {
        Map<String,Object> summary=new LinkedHashMap<>(request);
        if(app!=null&&simulated(app(company,app)))summary.put("simulation",true);
        return tx().execute(status->{db.update("INSERT INTO channel_interaction_log(company_code,channel_id,app_id,action,result,request_summary,operator) VALUES(?,?,?,?,?,?,?)",company,channel,app,action,result,json(summary),user);return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);});
    }
    private void audit(String company,Long channel,Long app,String action,String user,Map<String,Object> request) {insertLog(company,channel,app,action,"SUCCESS",user,request);}
    public Map<String,Object> logs(String company,Long channel,String result,int page,int size) {
        require(page>=1&&page<=100000&&size>=1&&size<=100,"分页参数无效");
        String where=" WHERE company_code=?";List<Object> args=new ArrayList<>();args.add(company);
        if(channel!=null){shop(company,channel);where+=" AND channel_id=?";args.add(channel);}
        if(result!=null&&!result.isEmpty()){require(Arrays.asList("SUCCESS","FAILED","UNKNOWN","RUNNING").contains(result),"日志状态无效");where+=" AND (CASE WHEN result='RUNNING' AND create_time<DATE_SUB(NOW(),INTERVAL 90 SECOND) THEN 'UNKNOWN' ELSE result END)=?";args.add(result);}
        long total=db.queryForObject("SELECT COUNT(*) FROM channel_interaction_log"+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=db.queryForList("SELECT id,channel_id,app_id,action,CASE WHEN result='RUNNING' AND create_time<DATE_SUB(NOW(),INTERVAL 90 SECOND) THEN 'UNKNOWN' ELSE result END AS result,request_summary,response_summary,error_code,operator,duration_ms,create_time FROM channel_interaction_log"+where+" ORDER BY id DESC LIMIT ? OFFSET ?",args.toArray());
        rows.forEach(ChannelPlatformService::dates);return map("total",total,"rows",rows);
    }
    private static String json(Object value) {try{return new ObjectMapper().writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("摘要序列化失败");}}
    private static Map<String,Object> dates(Map<String,Object> row) {row.replaceAll((key,value)->value instanceof LocalDateTime?((LocalDateTime)value).format(DATE):value instanceof Timestamp?((Timestamp)value).toLocalDateTime().format(DATE):value);return row;}
    private static Instant instant(Object value) {if(value instanceof java.util.Date)return ((java.util.Date)value).toInstant();if(value instanceof LocalDateTime)return ((LocalDateTime)value).atZone(ZoneId.of("Asia/Shanghai")).toInstant();return LocalDateTime.parse(value.toString().replace('T',' '),DATE).atZone(ZoneId.of("Asia/Shanghai")).toInstant();}
    private static boolean future(Object value) {return value!=null&&instant(value).isAfter(Instant.now());}
    private static boolean soon(Object value,int days) {return value!=null&&instant(value).isBefore(Instant.now().plusSeconds(days*86400L));}
}

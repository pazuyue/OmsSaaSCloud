package com.oms.order.service;
import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderWorkspaceService {
    @Resource private OrderRepository repository;
    @Resource private OrderCatalog catalog;
    @Resource private ConfiguredPushReader push;
    @Resource private OrderBusinessService businessOrders;
    /** Business settings never expose or change pull connection/progress. */
    public List<Map<String,Object>> conversionConfigs(String company){
        List<Map<String,Object>> rows=repository.db().query("SELECT channel_id,channel_name,auto_convert,allowed_trade_types,allow_public_stock,revision FROM oms_order_pull_config WHERE company_code=? ORDER BY channel_id",ROW,company);
        for(Map<String,Object> row:rows)row.put("allowed_trade_types",tradeTypes(row.get("allowed_trade_types")));return rows;
    }
    public void saveConversion(String company,Map<String,Object> input,String actor){
        long channel=number(input,"channel_id");catalog.validateShop(company,channel);
        List<String> types=tradeTypes(input.get("allowed_trade_types"));
        for(String flag:Arrays.asList("auto_convert","allow_public_stock"))require(number(input,flag)==0||number(input,flag)==1,"配置开关值无效");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(repository.db().getDataSource()));
        tx.execute(status->{
            List<Map<String,Object>> found=repository.db().query("SELECT revision,lease_until FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel);
            require(found.size()==1,"请先在拉单管理完成店铺接入");Map<String,Object> before=found.get(0);
            require(number(before,"revision")==number(input,"revision"),"配置已变化，请刷新后重试");
            require(before.get("lease_until")==null||time(text(before,"lease_until")).isBefore(LocalDateTime.now()),"店铺任务正在执行，请稍后修改设置");
            repository.db().update("UPDATE oms_order_pull_config SET auto_convert=?,allowed_trade_types=?,allow_public_stock=?,revision=revision+1 WHERE company_code=? AND channel_id=?",number(input,"auto_convert"),encode(types),number(input,"allow_public_stock"),company,channel);return null;
        });
    }
    public Map<String,Object> catalog(String company){Map<String,Object> result=new LinkedHashMap<>();List<Map<String,Object>> channels=catalog.channels(company);channels.removeIf(c->!"TM".equals(text(c,"channel_type")));Set<Long> configured=new HashSet<>();for(Map<String,Object> c:repository.db().query("SELECT c.channel_id FROM oms_order_pull_config c JOIN oms_order_push_source s ON s.company_code=c.company_code AND s.source_key=c.source_key WHERE c.company_code=? AND s.enabled=1",ROW,company))configured.add(number(c,"channel_id"));for(Map<String,Object> c:channels)c.put("pull_configured",configured.contains(number(c,"channel_id")));result.put("channels",channels);result.put("sources",push.choices(company));return result;}
    public List<Map<String,Object>> configs(String company){List<Map<String,Object>> rows=repository.db().query("SELECT c.id,c.company_code,c.channel_id,c.channel_name,c.source_key,c.seller_nick,c.enabled,c.auto_convert,c.allowed_trade_types,c.allow_public_stock,c.interval_seconds,c.initial_time,c.cursor_time,c.next_run_at,c.last_error,c.revision,s.source_name,s.enabled source_enabled FROM oms_order_pull_config c LEFT JOIN oms_order_push_source s ON s.company_code=c.company_code AND s.source_key=c.source_key WHERE c.company_code=? ORDER BY c.channel_id",ROW,company);for(Map<String,Object> row:rows)row.put("allowed_trade_types",tradeTypes(row.get("allowed_trade_types")));return rows;}
    public void save(String company,Map<String,Object> input,String actor){
        long channel=number(input,"channel_id");String seller=text(input,"seller_nick").trim(),source=text(input,"source_key");
        Map<String,Object> shop=catalog.validateShop(company,channel);List<String> allowedTypes=tradeTypes(input.get("allowed_trade_types"));
        require(!seller.isEmpty()&&seller.length()<=200,"请填写推送库卖家账号");require(push.configured(company,source),"推送库不存在或已停用");
        for(String flag:Arrays.asList("enabled","auto_convert","allow_public_stock"))require(number(input,flag)==0||number(input,flag)==1,"配置开关值无效");
        
        long interval=number(input,"interval_seconds");require(interval>=10&&interval<=86400,"拉单间隔须为10至86400秒");
        LocalDateTime initial=time(text(input,"initial_time"));require(!initial.isAfter(LocalDateTime.now()),"首次拉取起点不能晚于当前时间");
        org.springframework.transaction.support.TransactionTemplate tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(repository.db().getDataSource()));
        tx.execute(status->{
            List<Map<String,Object>> lockedSource=repository.db().query("SELECT enabled FROM oms_order_push_source WHERE company_code=? AND source_key=? FOR UPDATE",ROW,company,source);
            require(lockedSource.size()==1&&number(lockedSource.get(0),"enabled")==1,"推送库不存在或已停用，请刷新后重试");
            List<Map<String,Object>> old=repository.db().query("SELECT * FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel);
            // Locking the source serializes competing bindings, including concurrent first saves.
            require(repository.db().query("SELECT channel_id FROM oms_order_pull_config WHERE company_code=? AND source_key=? AND seller_nick=? AND channel_id<>? FOR UPDATE",ROW,company,source,seller,channel).isEmpty(),"该推送库卖家账号已绑定其他店铺，请勿重复绑定");
            boolean newMapping=old.isEmpty()||!source.equals(text(old.get(0),"source_key"))||!seller.equals(text(old.get(0),"seller_nick"));
            if(newMapping&&!text(shop,"seller_nick").isEmpty()&&!seller.equals(text(shop,"seller_nick")))
                require(number(input,"confirm_seller_mapping")==1,"推送库卖家账号与平台授权名称不同，请确认店铺映射后保存");
            if(old.isEmpty())repository.db().update("INSERT INTO oms_order_pull_config(company_code,channel_id,channel_name,source_key,seller_nick,enabled,auto_convert,allowed_trade_types,allow_public_stock,interval_seconds,initial_time,cursor_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",company,channel,shop.get("channel_name"),source,seller,number(input,"enabled"),number(input,"auto_convert"),encode(allowedTypes),number(input,"allow_public_stock"),interval,initial.format(TIME),initial.format(TIME));
            else{
                Map<String,Object> before=old.get(0);require(number(before,"revision")==number(input,"revision"),"配置已变化，请刷新后重试");
                require(before.get("lease_until")==null||time(text(before,"lease_until")).isBefore(LocalDateTime.now()),"店铺任务正在执行，请稍后修改配置");
                long registered=repository.db().queryForObject("SELECT COUNT(*) FROM oms_platform_order WHERE company_code=? AND channel_id=?",Long.class,company,channel);
                if(registered>0)require(seller.equals(text(before,"seller_nick")),"已有平台订单，不能直接更换卖家账号，以免影响原订单处理");
                boolean changed=!source.equals(text(before,"source_key"))||!seller.equals(text(before,"seller_nick"));
                if(changed){require(number(input,"reset_cursor")==1,"切换来源或卖家须确认重新拉取起点");
                    require(repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_pull_task WHERE company_code=? AND channel_id=? AND status IN ('PENDING','RUNNING')",Long.class,company,channel)==0,"尚有未完成拉单任务，请完成后再切换来源");}
                String cursor=changed?initial.format(TIME):text(before,"cursor_time"),origin=changed?initial.format(TIME):text(before,"initial_time");
                repository.db().update("UPDATE oms_order_pull_config SET channel_name=?,source_key=?,seller_nick=?,enabled=?,auto_convert=?,allowed_trade_types=?,allow_public_stock=?,interval_seconds=?,initial_time=?,cursor_time=?,revision=revision+1,next_run_at=NOW(),last_error='' WHERE company_code=? AND channel_id=?",shop.get("channel_name"),source,seller,number(input,"enabled"),number(input,"auto_convert"),encode(allowedTypes),number(input,"allow_public_stock"),interval,origin,cursor,company,channel);
            }
            return null;
        });
    }
    /** Bounded, read-only source verification; no task creation or cursor changes. */
    public Map<String,Object> verifySource(String company,Map<String,Object> input){
        catalog.validateShop(company,number(input,"channel_id"));
        String seller=text(input,"seller_nick").trim(),source=text(input,"source_key");
        require(!seller.isEmpty()&&seller.length()<=200,"请填写推送库卖家账号");
        require(push.configured(company,source),"推送库不存在或已停用");
        LocalDateTime from=time(text(input,"start_time")),to=time(text(input,"end_time"));
        require(from.isBefore(to)&&!to.isAfter(LocalDateTime.now().plusSeconds(1)),"验证时间范围无效");
        require(java.time.Duration.between(from,to).compareTo(java.time.Duration.ofDays(90))<=0,"验证范围最多90天");
        Map<String,Object> task=new LinkedHashMap<>();task.put("start_time",from.format(TIME));task.put("end_time",to.format(TIME));task.put("page_time",from.format(TIME));task.put("page_tid",0);
        List<Map<String,Object>> rows;
        try{rows=push.preview(company,source,seller,task);}catch(RuntimeException e){throw new IllegalArgumentException("来源验证失败，请检查推送库连接、订单表读取权限或稍后重试");}
        Map<String,Object> result=new LinkedHashMap<>();result.put("orders",rows);result.put("seller_nick",seller);
        result.put("message",rows.isEmpty()?"所选时间范围内未找到订单，请核对账号或调整时间范围；仍可保存配置。":"已找到来源订单，请核对是否属于目标店铺。最多展示5条，不代表总数。");return result;
    }
    public Map<String,Object> backfillRequest(String company,Map<String,Object> input,String actor){if(input.get("channel_ids") instanceof List)for(Object id:(List<?>)input.get("channel_ids"))catalog.validateShop(company,Long.parseLong(id.toString()));return new OrderBackfillService(repository,push).submit(company,input,actor);}
    @SuppressWarnings("unchecked") public List<Long> backfill(String company,List<Long> channels,String start,String end,String actor){Map<String,Object> input=new LinkedHashMap<>();input.put("channel_ids",channels);input.put("start_time",start);input.put("end_time",end);return (List<Long>)backfillRequest(company,input,actor).get("task_ids");}
    public Map<String,Object> pullTask(String company,long id){return new OrderBackfillService(repository,push).task(company,id);}
    public TableDataInfo pullItems(String company,long id,String result,int page,int size){return new OrderBackfillService(repository,push).items(company,id,result,page,size);}
    public Map<String,Object> retryPullItems(String company,long id,String actor){return retryPullItems(company,id,actor,Collections.emptyList());}
    public Map<String,Object> retryPullItems(String company,long id,String actor,List<Long> items){return new OrderBackfillService(repository,push).retryFailures(company,id,actor,items);}
    public void retry(String company,long id,String actor){Map<String,Object> result=retryBatch(company,Collections.singletonList(id),actor).get(0);require(Boolean.TRUE.equals(result.get("submitted")),text(result,"message"));}
    public List<Map<String,Object>> retryBatch(String company,List<Long> ids,String actor){return new OrderExceptionService(repository).retry(company,ids,actor);}
    public List<Map<String,Object>> retryResults(String company,List<Long> ids){return new OrderExceptionService(repository).results(company,ids);}
    public TableDataInfo list(String company,String view,Long channel,String tid,String status,int page,int pageSize){return list(company,view,channel,tid,status,page,pageSize,"","","","",0);}
    public TableDataInfo list(String company,String view,Long channel,String tid,String status,int page,int pageSize,String category,String sku,String from,String to,int waitingMinutes){return list(company,view,channel,tid,status,page,pageSize,category,sku,from,to,waitingMinutes,Collections.emptyMap());}
    public TableDataInfo list(String company,String view,Long channel,String tid,String status,int page,int pageSize,String category,String sku,String from,String to,int waitingMinutes,Map<String,String> shared){require(Arrays.asList("platform","business","exceptions","tasks").contains(view),"订单视图无效");String alias="p",table="oms_platform_order p",select="p.id,p.channel_id,p.platform_tid,p.platform_status,p.trade_type,p.platform_created,p.source_modified,p.conversion_status,p.conversion_error,p.update_version,p.update_state,p.update_categories,p.update_summary,p.change_pending,p.demand_registered,p.business_order_id,p.payment,p.create_time,p.exception_code,p.exception_line_count abnormal_line_count,p.first_exception_at,p.last_exception_at,p.retry_count,p.system_failures,p.next_retry_at";
        if("business".equals(view))return businessOrders.list(company,channel,tid,status,sku,from,to,waitingMinutes,page,pageSize);if("tasks".equals(view)){table="oms_order_pull_task p";select="p.*";}
        String where=" WHERE p.company_code=?"+("tasks".equals(view)?"":" AND p.source_kind='PLATFORM'");List<Object> args=new ArrayList<>();args.add(company);if(channel!=null){where+=" AND p.channel_id=?";args.add(channel);}if("exceptions".equals(view)){
            where+=" AND (p.conversion_status='EXCEPTION' OR (p.exception_code<>'' AND p.conversion_status IN ('PENDING','PROCESSING')))";
            if(!category.isEmpty()){
                require(Arrays.asList("PRODUCT","CONFIG","SYSTEM","DATA").contains(category),"异常分类无效");
                where+=" AND "+("PRODUCT".equals(category)?"p.exception_code='PRODUCT_INVALID'":"CONFIG".equals(category)?"p.exception_code='TRADE_TYPE_DISABLED'":"SYSTEM".equals(category)?"p.exception_code='SYSTEM_FAILURE'":"p.exception_code NOT IN ('PRODUCT_INVALID','TRADE_TYPE_DISABLED','SYSTEM_FAILURE','')");
            }
            if(!sku.trim().isEmpty()){where+=" AND EXISTS (SELECT 1 FROM oms_platform_order_line l WHERE l.company_code=p.company_code AND l.platform_order_id=p.id AND (l.outer_sku_id=? OR l.sku_sn=?))";args.add(sku.trim());args.add(sku.trim());}
            if(!from.isEmpty()){where+=" AND p.first_exception_at>=?";args.add(time(from).format(TIME));}
            if(!to.isEmpty()){where+=" AND p.first_exception_at<=?";args.add(time(to).format(TIME));}
            require(waitingMinutes>=0&&waitingMinutes<=5256000,"等待分钟数无效");
            if(waitingMinutes>0){where+=" AND p.first_exception_at<=DATE_SUB(NOW(),INTERVAL ? MINUTE)";args.add(waitingMinutes);}
        }
        if(tid!=null&&!tid.trim().isEmpty()&&!"tasks".equals(view)){where+=" AND p.platform_tid=?";args.add(tid.trim());}
        if(status!=null&&!status.isEmpty()){where+=" AND p."+("business".equals(view)?"inventory_status":"tasks".equals(view)?"status":"conversion_status")+"=?";args.add(status);}
        if("platform".equals(view))where=PlatformDocumentQuery.append(where,args,shared,true);
        long total=repository.db().queryForObject("SELECT COUNT(*) FROM "+table+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));
        List<Map<String,Object>> rows=repository.db().query("SELECT "+select+",c.channel_name FROM "+table+" LEFT JOIN oms_order_pull_config c ON c.company_code=p.company_code AND c.channel_id=p.channel_id"+where+" ORDER BY p.id DESC LIMIT ? OFFSET ?",ROW,args.toArray());for(Map<String,Object> row:rows){if(!"business".equals(view)&&!"tasks".equals(view))OrderExceptionService.decorate(row);row.remove("receiver_phone");row.remove("receiver_address");row.remove("receiver_name");row.remove("oaid");}repository.messageProgress(company,rows,"tasks".equals(view)?"ORDER_PULL":"ORDER_CONVERT");return page(rows,total);
    }
    public Map<String,Object> detail(String company,long id,boolean business){Map<String,Object> row;if(business){List<Map<String,Object>> found=repository.db().query("SELECT * FROM oms_order WHERE company_code=? AND id=?",ROW,company,id);require(!found.isEmpty(),"业务订单不存在");row=found.get(0);row.put("lines",repository.db().query("SELECT * FROM oms_order_line WHERE company_code=? AND order_id=? ORDER BY id",ROW,company,id));}else{row=repository.raw(company,id);row.remove("raw_payload");row.remove("processing_payload");row.put("lines",repository.db().query("SELECT * FROM oms_platform_order_line WHERE company_code=? AND platform_order_id=? ORDER BY id",ROW,company,id));}
        if(!business){
            repository.messageProgress(company,Collections.singletonList(row),"ORDER_CONVERT");
            OrderExceptionService.decorate(row);Map<String,String> eligible=OrderProductMatchService.eligibleErrors(row);
            Map<String,Map<String,Object>> byOid=new HashMap<>();for(Map<String,Object> line:(List<Map<String,Object>>)row.get("lines"))byOid.put(text(line,"platform_oid"),line);
            for(Map<String,Object> issue:(List<Map<String,Object>>)row.get("exception_details")){
                Map<String,Object> line=byOid.get(text(issue,"platform_oid"));
                boolean can=line!=null&&eligible.containsKey(text(issue,"platform_oid"))&&number(line,"demand_prepared")==0&&text(line,"sku_sn").isEmpty()&&number(row,"change_pending")==0&&row.get("business_order_id")==null&&"EXCEPTION".equals(text(row,"conversion_status"));
                issue.put("can_manual_match",can);
            }
        }OrderDocumentInfo.attach(row);long platform=business?number(row,"platform_order_id"):id;row.put("logs",repository.db().query("SELECT action,result,message,operator_name,create_time FROM oms_order_process_log WHERE company_code=? AND platform_order_id=? ORDER BY id DESC LIMIT 100",ROW,company,platform));return row;
    }
}

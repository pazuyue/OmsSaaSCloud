package com.oms.order.service;

import com.ruoyi.common.core.web.page.TableDataInfo;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Backfill scope and results. Source data enters the same protected document synchronizer as AUTO. */
public final class OrderBackfillService {
    private final OrderRepository repository;
    private final ConfiguredPushReader push;
    public OrderBackfillService(OrderRepository repository,ConfiguredPushReader push){this.repository=repository;this.push=push;}
    private TransactionTemplate tx(){return new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));}
    public static List<String> tids(Object raw){
        List<?> values;if(raw instanceof List)values=(List<?>)raw;else {String input=raw==null?"":raw.toString();require(input.length()<=10000,"单号输入过长");values=Arrays.asList(input.trim().split("[\\s,，;；]+"));}
        require(values.size()<=1000,"单号输入过多");Set<String> unique=new LinkedHashSet<>();
        for(Object value:values){require(value instanceof String,"平台单号须按文本提交，避免长单号精度丢失");String tid=value.toString().trim();if(tid.isEmpty())continue;require(tid.matches("[1-9][0-9]{0,18}")&&new BigInteger(tid).compareTo(BigInteger.valueOf(Long.MAX_VALUE))<=0,"平台单号格式错误："+tid.substring(0,Math.min(30,tid.length())));unique.add(tid);}
        require(!unique.isEmpty()&&unique.size()<=100,"每批请输入1至100个有效平台单号");return new ArrayList<>(unique);
    }
    public static String windowEnd(Map<String,Object> task){
        LocalDateTime end=time(text(task,"end_time"));
        if("MANUAL".equals(text(task,"task_type"))&&!"TID".equals(text(task,"pull_mode"))){LocalDateTime start=time(text(task,"start_time")),cursor=time(text(task,"page_time"));long days=java.time.Duration.between(start,cursor).toDays();LocalDateTime boundary=start.plusDays(days+1);if(boundary.isBefore(end))end=boundary;}
        return end.format(TIME);
    }
    public Map<String,Object> submit(String company,Map<String,Object> input,String actor){
        String mode=text(input,"mode");if(mode.isEmpty())mode="TIME";require(Arrays.asList("TIME","TID").contains(mode),"请选择按时间或平台单号补拉");
        require(input.get("channel_ids") instanceof List,"请选择店铺");Set<Long> selected=new TreeSet<>();for(Object raw:(List<?>)input.get("channel_ids")){long id=Long.parseLong(raw.toString());require(id>0,"店铺参数无效");selected.add(id);}require(!selected.isEmpty()&&selected.size()<=20,"请选择1至20个店铺");require(!mode.equals("TID")||selected.size()==1,"按单号补拉请选择一个店铺");
        String request=text(input,"request_key");require(request.isEmpty()||request.matches("[A-Za-z0-9_-]{16,64}"),"补拉请求标识无效");
        List<String> numbers=mode.equals("TID")?tids(input.get("tids")):Collections.emptyList();
        LocalDateTime now=LocalDateTime.now().withNano(0),from=now,to=now;boolean clamped=false;
        if(mode.equals("TIME")){from=time(text(input,"start_time"));to=time(text(input,"end_time"));require(from.getYear()>=1000,"开始时间超出可查询范围");require(from.isBefore(to),"开始时间必须早于结束时间");require(from.isBefore(now),"开始时间不能晚于或等于当前时间");if(to.isAfter(now)){to=now;clamped=true;}}
        // Request identity uses the submitted range, so a clipped end stays idempotent on retry.
        List<String> canonicalTids=new ArrayList<>(numbers);Collections.sort(canonicalTids);
        String digest=hash(encode(Arrays.asList(mode,selected,text(input,"start_time"),text(input,"end_time"),canonicalTids)));
        String finalMode=mode,start=from.format(TIME),end=to.format(TIME);boolean adjusted=clamped;
        return tx().execute(status->{List<Long> tasks=new ArrayList<>();String actualStart=start,actualEnd=end;
            for(long channel:selected){Map<String,Object> config=repository.db().query("SELECT * FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,channel).stream().findFirst().orElseThrow(()->new IllegalArgumentException("店铺尚未配置订单拉取"));require(push.configured(company,text(config,"source_key")),"推送库未配置或已停用");
                List<Map<String,Object>> existing=request.isEmpty()?Collections.emptyList():repository.db().query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND channel_id=? AND request_key=?",ROW,company,channel,request);
                if(!existing.isEmpty()){Map<String,Object> old=existing.get(0);require(digest.equals(text(old,"request_hash")),"同一补拉请求的内容已改变，请重新提交");tasks.add(number(old,"id"));actualStart=text(old,"start_time");actualEnd=text(old,"end_time");continue;}
                String rawNumbers=finalMode.equals("TID")?encode(canonicalTids):null;
                List<Map<String,Object>> same=repository.db().query("SELECT id,start_time,end_time FROM oms_order_pull_task WHERE company_code=? AND channel_id=? AND task_type='MANUAL' AND pull_mode=? AND pull_source_key=? AND pull_seller_nick=? AND status IN ('PENDING','RUNNING') AND "+(finalMode.equals("TID")?"requested_tids=?":"start_time=? AND end_time=?")+" ORDER BY id LIMIT 1",ROW,finalMode.equals("TID")?new Object[]{company,channel,finalMode,config.get("source_key"),config.get("seller_nick"),rawNumbers}:new Object[]{company,channel,finalMode,config.get("source_key"),config.get("seller_nick"),start,end});
                if(!same.isEmpty()){tasks.add(number(same.get(0),"id"));continue;}
                long task=repository.insert("INSERT INTO oms_order_pull_task(company_code,channel_id,task_type,pull_mode,start_time,end_time,page_time,operator_name,requested_tids,request_key,request_hash,pull_source_key,pull_seller_nick) VALUES (?,?,'MANUAL',?,?,?,?,?,?,?,?,?,?)",company,channel,finalMode,start,end,start,actor,rawNumbers,request.isEmpty()?null:request,digest,config.get("source_key"),config.get("seller_nick"));tasks.add(task);
                if(finalMode.equals("TID"))for(String tid:numbers)repository.db().update("INSERT INTO oms_order_pull_item(company_code,task_id,platform_tid) VALUES (?,?,?)",company,task,tid);
                repository.pullChanged(company,task);
            }
            Map<String,Object> reply=new LinkedHashMap<>();reply.put("task_ids",tasks);reply.put("mode",finalMode);reply.put("start_time",actualStart);reply.put("end_time",actualEnd);reply.put("range_clamped",adjusted);reply.put("tid_count",numbers.size());return reply;
        });
    }
    private void result(String company,long task,String tid,Map<String,Object> result){repository.db().update("INSERT INTO oms_order_pull_item(company_code,task_id,platform_tid,platform_order_id,result,message) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE platform_order_id=VALUES(platform_order_id),result=VALUES(result),message=VALUES(message)",company,task,tid,result.get("platform_order_id"),result.get("result"),text(result,"message"));}
    private Map<String,Object> outcome(String status,String message){Map<String,Object> r=new LinkedHashMap<>();r.put("result",status);r.put("message",message);return r;}
    @SuppressWarnings("unchecked") public void commitPage(String company,long channel,String token,Map<String,Object> task,List<Map<String,Object>> rows,int limit){
        tx().execute(status->{repository.fence(company,channel,token);boolean manual="MANUAL".equals(text(task,"task_type")),byTid="TID".equals(text(task,"pull_mode"));long taskId=number(task,"id");int inserted=0,duplicates=0,updated=0,unchanged=0;
            List<String> selected=byTid?(List<String>)task.get("selected_tids"):Collections.emptyList();require(!byTid||selected!=null,"单号任务明细缺失");Set<String> found=new HashSet<>();
            for(Map<String,Object> row:rows){String tid=text(row,"tid");require(!byTid||selected.contains(tid)&&found.add(tid),"来源返回了范围外或重复单号");Map<String,Object> item;
                if(manual){TransactionTemplate nested=tx();nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);try{item=nested.execute(savepoint->repository.ingest(company,channel,row));}catch(RuntimeException e){String message=e instanceof IllegalArgumentException?e.getMessage():"平台数据保存失败，请重试该单号";if(message==null)message="补拉处理失败";item=outcome("FAILED",message.substring(0,Math.min(480,message.length())));}result(company,taskId,tid,item);}
                else item=repository.ingest(company,channel,row);
                if(Arrays.asList("UPDATED","CHANGE_PENDING").contains(text(item,"result")))updated++;else if("SKIPPED".equals(text(item,"result")))unchanged++;
                if("NEW".equals(text(item,"result")))inserted++;else if(!"FAILED".equals(text(item,"result")))duplicates++;
            }
            if(byTid)for(String tid:selected)if(!found.contains(tid))result(company,taskId,tid,outcome("NOT_FOUND","该店铺推送库未找到此单号；未查询天猫平台接口"));
            String cursor=text(task,"page_time"),cursorTid=text(task,"page_tid");boolean done;
            if(byTid)done=repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_pull_item WHERE company_code=? AND task_id=? AND result='PENDING'",Long.class,company,taskId)==0;
            else {String end=windowEnd(task);boolean segmentDone=rows.size()<limit;done=segmentDone&&end.equals(text(task,"end_time"));if(segmentDone){cursor=end;cursorTid="0";}else if(!rows.isEmpty()){cursor=text(rows.get(rows.size()-1),"jdp_modified");cursorTid=text(rows.get(rows.size()-1),"tid");}}
            long failed=manual?repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_pull_item WHERE company_code=? AND task_id=? AND result='FAILED'",Long.class,company,taskId):0;
            repository.db().update("UPDATE oms_order_pull_task SET page_time=?,page_tid=?,pulled_count=pulled_count+?,inserted_count=inserted_count+?,duplicate_count=duplicate_count+?,new_count=COALESCE(new_count,0)+?,updated_count=COALESCE(updated_count,0)+?,unchanged_count=COALESCE(unchanged_count,0)+?,failed_count=?,status=?,attempts=0,error_message=?,next_retry_at=NOW() WHERE company_code=? AND id=?",cursor,cursorTid,rows.size(),inserted,duplicates,inserted,updated,unchanged,failed,done?(failed>0?"PARTIAL":"SUCCESS"):"RUNNING",done&&failed>0?failed+" 条补拉失败，可仅重试失败项":"",company,taskId);
            if(done&&!manual)repository.db().update("UPDATE oms_order_pull_config SET cursor_time=GREATEST(cursor_time,?),last_error='' WHERE company_code=? AND channel_id=?",task.get("end_time"),company,channel);return null;
        });
    }
    public Map<String,Object> task(String company,long id){Map<String,Object> row=repository.db().query("SELECT t.*,c.channel_name FROM oms_order_pull_task t LEFT JOIN oms_order_pull_config c ON c.company_code=t.company_code AND c.channel_id=t.channel_id WHERE t.company_code=? AND t.id=?",ROW,company,id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("任务不存在或无权访问"));repository.messageProgress(company,Collections.singletonList(row),"ORDER_PULL");row.remove("request_hash");row.remove("request_key");row.remove("pull_seller_nick");row.remove("pull_source_key");row.put("results",repository.db().query("SELECT result,COUNT(*) count FROM oms_order_pull_item WHERE company_code=? AND task_id=? GROUP BY result",ROW,company,id));return row;}
    public TableDataInfo items(String company,long id,String filter,int page,int pageSize){task(company,id);require(filter.isEmpty()||Arrays.asList("PENDING","NEW","SKIPPED","UPDATED","CHANGE_PENDING","NOT_FOUND","FAILED").contains(filter),"补拉结果筛选无效");List<Object> args=new ArrayList<>(Arrays.asList(company,id));String where=" WHERE i.company_code=? AND i.task_id=?";if(!filter.isEmpty()){where+=" AND i.result=?";args.add(filter);}long total=repository.db().queryForObject("SELECT COUNT(*) FROM oms_order_pull_item i"+where,Long.class,args.toArray());int count=size(pageSize);args.add(count);args.add(offset(page,count));return page(repository.db().query("SELECT i.*,p.conversion_status,p.conversion_error,p.change_pending,p.business_order_id FROM oms_order_pull_item i LEFT JOIN oms_platform_order p ON p.company_code=i.company_code AND p.id=i.platform_order_id"+where+" ORDER BY i.id LIMIT ? OFFSET ?",ROW,args.toArray()),total);}
    public Map<String,Object> retryFailures(String company,long id,String actor){return retryFailures(company,id,actor,Collections.emptyList());}
    public Map<String,Object> retryFailures(String company,long id,String actor,List<Long> selected){return tx().execute(status->{
        Map<String,Object> before=task(company,id);repository.db().query("SELECT channel_id FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,before.get("channel_id"));
        Map<String,Object> task=repository.db().query("SELECT * FROM oms_order_pull_task WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
        require("MANUAL".equals(text(task,"task_type")),"仅补拉任务支持逐单重试");require(Arrays.asList("SUCCESS","PARTIAL","FAILED").contains(text(task,"status")),"任务尚未结束，请等待后重试失败项");
        Set<Long> ids=new TreeSet<>(selected);require(ids.size()<=100,"每次最多重试100个单号");List<Object> args=new ArrayList<>(Arrays.asList(company,id));String where="";
        if(!ids.isEmpty()){where=" AND id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";args.addAll(ids);}
        List<String> failed=repository.db().queryForList("SELECT platform_tid FROM oms_order_pull_item WHERE company_code=? AND task_id=? AND result='FAILED'"+where+" ORDER BY id LIMIT 101",String.class,args.toArray());
        require(ids.isEmpty()||failed.size()==ids.size(),"所选明细不属于该任务或并非补拉失败项");require(!failed.isEmpty(),"没有补拉失败项；未找到订单需重新按单号查询");require(failed.size()<=100,"失败单号超过100个，请勾选明细分批重试");
        Map<String,Object> config=repository.config(company,number(task,"channel_id"));require(text(task,"pull_source_key").equals(text(config,"source_key"))&&text(task,"pull_seller_nick").equals(text(config,"seller_nick")),"店铺拉单来源已改变，请核对店铺后重新补拉");
        Map<String,Object> body=new LinkedHashMap<>();body.put("mode","TID");body.put("channel_ids",Collections.singletonList(task.get("channel_id")));body.put("tids",failed);return submit(company,body,actor);
    });}
}

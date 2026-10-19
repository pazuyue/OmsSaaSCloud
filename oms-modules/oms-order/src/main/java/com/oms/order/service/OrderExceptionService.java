package com.oms.order.service;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.oms.order.service.OrderValues.*;

/** Reuses the document queue and shop lease. Each selected document has its own short transaction. */
public class OrderExceptionService {
    private final OrderRepository repository;
    public OrderExceptionService(OrderRepository repository){this.repository=repository;}
    private List<Long> ids(List<Long> ids){require(ids!=null&&!ids.isEmpty()&&ids.size()<=100,"每次请选择1至100张订单");for(Long id:ids)require(id!=null&&id>0,"订单ID无效");return new ArrayList<>(new LinkedHashSet<>(ids));}
    public List<Map<String,Object>> retry(String company,List<Long> requested,String actor){
        List<Map<String,Object>> result=new ArrayList<>();
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(repository.db().getDataSource()));
        for(long id:ids(requested)){
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",id);
            try{
                tx.execute(status->{
                    Map<String,Object> initial=repository.raw(company,id);
                    List<Map<String,Object>> configs=repository.db().query("SELECT * FROM oms_order_pull_config WHERE company_code=? AND channel_id=? FOR UPDATE",ROW,company,initial.get("channel_id"));
                    require(configs.size()==1,"店铺拉单配置不存在");Map<String,Object> config=configs.get(0);
                    require(config.get("lease_until")==null||time(text(config,"lease_until")).isBefore(LocalDateTime.now()),"店铺正在处理，请稍后重试");
                    Map<String,Object> row=repository.db().query("SELECT * FROM oms_platform_order WHERE company_code=? AND id=? FOR UPDATE",ROW,company,id).get(0);
                    require(number(row,"change_pending")==0,"平台业务数据已变化，待订单更新处理");
                    require(row.get("business_order_id")==null&&!"SUCCESS".equals(text(row,"conversion_status")),"已转单成功，无需重复转单");
                    require(Arrays.asList("EXCEPTION","WAITING").contains(text(row,"conversion_status")),"已在处理队列中，请勿重复提交");
                    require("WAIT_SELLER_SEND_GOODS".equals(text(row,"platform_status")),"平台状态不满足已付款待发货条件");
                    repository.db().update("UPDATE oms_platform_order SET conversion_status='PENDING',force_convert=1,retry_count=retry_count+1,system_failures=0,next_retry_at=NOW() WHERE company_code=? AND id=?",company,id);
                    repository.conversionChanged(company,id,true);repository.log(company,id,"RETRY","PENDING","已提交人工转单重试",actor);item.put("platform_tid",row.get("platform_tid"));return null;
                });
                item.put("submitted",true);item.put("message","已提交");
            }catch(IllegalArgumentException e){item.put("submitted",false);item.put("message",e.getMessage());}
            result.add(item);
        }
        return result;
    }
    public List<Map<String,Object>> results(String company,List<Long> requested){
        List<Long> keys=ids(requested);List<Object> args=new ArrayList<>();args.add(company);args.addAll(keys);
        return repository.db().query("SELECT p.id,p.platform_tid,p.conversion_status,p.conversion_error,p.exception_code,p.change_pending,p.business_order_id,p.next_retry_at,p.system_failures,o.inventory_status FROM oms_platform_order p LEFT JOIN oms_order o ON o.company_code=p.company_code AND o.id=p.business_order_id WHERE p.company_code=? AND p.id IN ("+String.join(",",Collections.nCopies(keys.size(),"?"))+")",ROW,args.toArray());
    }
    public static void decorate(Map<String,Object> row){
        String code=text(row,"exception_code");
        String category=category(code);row.put("exception_category",category);
        row.put("exception_advice","PRODUCT".equals(category)?"选择当前公司已有OMS SKU进行人工匹配，仅对本订单明细生效；已登记需求的明细不能直接换品":"CONFIG".equals(category)?"到该店铺转单设置核对允许的交易类型":"SYSTEM".equals(category)?"自动重试有次数上限；恢复服务后可人工重试":"PLATFORM_HOLD".equals(code)?"等待平台解除暂缓发货，订单更新由后续流程处理":"核对具体异常字段和来源报文，勿跳过校验");
        List<?> details=Collections.emptyList();Object value=row.get("exception_details");
        if(value!=null&&!value.toString().isEmpty())try{details=JSON.readValue(value.toString(),List.class);}catch(Exception e){throw new IllegalStateException("异常明细格式无效",e);}
        if(row.containsKey("exception_details")){row.put("exception_details",details);long count=details.stream().filter(d->d instanceof Map&&!text((Map<String,Object>)d,"platform_oid").isEmpty()).count();row.put("abnormal_line_count",count);}
    }
    public static String category(String code){return "PRODUCT_INVALID".equals(code)?"PRODUCT":"TRADE_TYPE_DISABLED".equals(code)?"CONFIG":"SYSTEM_FAILURE".equals(code)?"SYSTEM":code.isEmpty()?"":"DATA";}
}

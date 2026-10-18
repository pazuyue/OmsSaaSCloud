package com.oms.order.service;

import com.oms.common.api.OrderStockClient;
import com.oms.common.model.dto.OrderStockCommand;
import com.ruoyi.common.core.constant.SecurityConstants;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;

@Service
public class OrderProcessor {
    @Resource private OrderRepository repository;
    @Resource private OrderVoidService voids;
    @Resource private OrderCatalog catalog;
    @Resource private OrderStockClient inventory;
    @Resource private ConfiguredPushReader push;
    private final TmallOrderParser parser=new TmallOrderParser();

    public void shop(String company,long channel){String lease=token();if(!repository.claim(company,channel,lease))return;
        try {
            Map<String,Object> config=repository.config(company,channel);
            for(Map<String,Object> order:voids.due(company,channel)){if(!repository.renew(company,channel,lease))return;voids.process(company,number(order,"id"),channel,lease);}
            // Remote master-data validation before source reads and before any inventory transaction.
            Map<String,Object> task=repository.nextTask(company,channel,lease);
            try{catalog.validateShop(company,channel);}catch(RuntimeException e){if(task!=null)repository.taskFailed(company,channel,lease,number(task,"id"),safe(e));throw e;}
            if(task!=null)try{if(!text(task,"pull_source_key").isEmpty())require(text(task,"pull_source_key").equals(text(config,"source_key"))&&text(task,"pull_seller_nick").equals(text(config,"seller_nick")),"店铺拉单来源已改变，请核对后重新提交补拉");List<Map<String,Object>> rows="TID".equals(text(task,"pull_mode"))&&task.get("selected_tids") instanceof List&&((List<?>)task.get("selected_tids")).isEmpty()?Collections.emptyList():push.read(company,text(config,"source_key"),text(config,"seller_nick"),task,50);repository.page(company,channel,lease,task,rows,50);}catch(RuntimeException e){repository.taskFailed(company,channel,lease,number(task,"id"),safe(e));}
            boolean convert=number(config,"auto_convert")==1;
            List<Map<String,Object>> orders=repository.dueOrders(company,channel,convert);
            for(Map<String,Object> order:orders){if(!repository.renew(company,channel,lease))break;process(company,number(order,"id"),lease,config,convert);}
        } catch(RuntimeException e){repository.db().update("UPDATE oms_order_pull_config SET last_error=? WHERE company_code=? AND channel_id=? AND lease_token=?",safe(e),company,channel,lease);}
        finally{repository.release(company,channel,lease);}
    }
    public void process(String company,long id,String lease,Map<String,Object> config,boolean convert){
        try {
            Map<String,Object> order=repository.snapshot(company,id,lease);if(order==null)return;
            if(order.get("business_order_id")!=null){
                OrderExchangeGuard.requireReady(repository,company,number(order,"business_order_id"),false);
                List<Map<String,Object>> lines=repository.db().query("SELECT platform_oid,sku_sn,quantity,stock_reference,business_type FROM oms_order_line WHERE company_code=? AND order_id=? AND business_type='SALE' ORDER BY id",ROW,company,order.get("business_order_id"));require(!lines.isEmpty(),"业务订单商品明细缺失");List<Map<String,Object>> receipts=new ArrayList<>();
                for(Map.Entry<String,List<Map<String,Object>>> group:OrderFulfillmentLines.groups(order,lines).entrySet()){
                    OrderStockCommand command=new OrderStockCommand();command.setChannelId(number(order,"channel_id"));command.setPlatformTid(group.getKey());command.setAllowPublicStock(number(config,"allow_public_stock")==1);
                    for(Map<String,Object> line:group.getValue()){OrderStockCommand.Line item=new OrderStockCommand.Line();item.setPlatformOid(text(line,"platform_oid"));item.setSkuSn(text(line,"sku_sn"));item.setQuantity(Math.toIntExact(number(line,"quantity")));command.getLines().add(item);}
                    for(Map<String,Object> row:data(inventory.execute("reserve",company,command,SecurityConstants.INNER))){Map<String,Object> copy=new LinkedHashMap<>(row);copy.put("stock_reference",group.getKey());receipts.add(copy);}
                }
                repository.complete(company,id,lease,config,receipts);return;
            }
            TmallOrderParser.Trade trade=parser.parse(text(order,"processing_payload"));
            require(trade.tid.equals(text(order,"platform_tid"))&&trade.seller.equals(text(config,"seller_nick")),"报文订单号或卖家与拉取范围不一致");
            List<String> allowedTypes=order.get("business_order_id")==null?tradeTypes(config.get("allowed_trade_types")):Collections.singletonList(trade.type);
            if(!trade.eligible(allowedTypes)){
                if("WAIT_SELLER_SEND_GOODS".equals(trade.status))repository.failure(company,id,lease,"TRADE_TYPE_DISABLED",trade.ineligibleReason(allowedTypes),Collections.emptyList(),false);
                else repository.state(company,id,lease,"WAITING",trade.ineligibleReason(allowedTypes));
                return;
            }
            Map<String,String> manual=repository.manualMatches(company,id);
            List<String> skus=new ArrayList<>();for(TmallOrderParser.Line line:trade.lines){String sku=matchingSku(line,manual);if(!sku.isEmpty()&&!skus.contains(sku))skus.add(sku);}
            Map<String,List<Map<String,Object>>> goods=catalog.goods(company,skus);Map<String,String> matched=new HashMap<>(),errors=new LinkedHashMap<>();
            OrderStockCommand command=new OrderStockCommand();command.setChannelId(number(config,"channel_id"));command.setPlatformTid(trade.tid);command.setAllowPublicStock(number(config,"allow_public_stock")==1);
            for(TmallOrderParser.Line line:trade.lines){String sku=matchingSku(line,manual);List<Map<String,Object>> found=goods.getOrDefault(sku,Collections.emptyList());String error=sku.isEmpty()?"缺少outer_sku_id":found.isEmpty()?"内部SKU不存在："+sku:found.size()!=1?"内部SKU匹配不唯一："+sku:"";
                if(error.isEmpty()&&(number(found.get(0),"is_fd")==1||number(found.get(0),"is_package")==1))error="一期不处理套装或福袋："+line.outerSku;
                if(!error.isEmpty()){errors.put(line.oid,error);continue;}
                matched.put(line.oid,sku);OrderStockCommand.Line stock=new OrderStockCommand.Line();stock.setPlatformOid(line.oid);stock.setSkuSn(sku);stock.setQuantity(line.quantity);command.getLines().add(stock);
            }
            repository.parsed(company,id,lease,trade,matched,errors);
            if(!command.getLines().isEmpty()){repository.prepareDemand(company,id,lease);data(inventory.execute("register",company,command,SecurityConstants.INNER));repository.registered(company,id,lease);}
            if(!errors.isEmpty()){
                List<Map<String,Object>> details=new ArrayList<>();
                for(TmallOrderParser.Line line:trade.lines)if(errors.containsKey(line.oid)){
                    Map<String,Object> detail=new LinkedHashMap<>();detail.put("platform_oid",line.oid);detail.put("outer_sku_id",line.outerSku);
                    String sku=matchingSku(line,manual);detail.put("manual_sku_sn",manual.get(line.oid));List<Map<String,Object>> found=goods.getOrDefault(sku,Collections.emptyList());
                    detail.put("code",sku.isEmpty()?"OUTER_SKU_MISSING":found.isEmpty()?"SKU_NOT_FOUND":found.size()!=1?"SKU_AMBIGUOUS":"PRODUCT_UNSUPPORTED");
                    detail.put("message",errors.get(line.oid));details.add(detail);
                }
                repository.failure(company,id,lease,"PRODUCT_INVALID",errors.values().iterator().next(),details,false);return;
            }
            if(!convert&&number(order,"force_convert")==0&&order.get("business_order_id")==null){repository.state(company,id,lease,"WAITING","已登记销售需求，等待启用自动转单或手工重试");return;}
            List<Map<String,Object>> issues=parser.conversionIssues(trade);
            if(!issues.isEmpty()){repository.failure(company,id,lease,text(issues.get(0),"code"),text(issues.get(0),"message"),issues,false);return;}
            parser.validateConversion(trade,allowedTypes);
            List<Map<String,Object>> result=data(inventory.execute("reserve",company,command,SecurityConstants.INNER));
            repository.complete(company,id,lease,config,result);
        }catch(IllegalArgumentException e){repository.failure(company,id,lease,e instanceof ConversionException?((ConversionException)e).code:"ORDER_DATA_INVALID",safe(e),Collections.emptyList(),false);}
        catch(RuntimeException e){repository.failure(company,id,lease,"SYSTEM_FAILURE","服务调用或数据处理失败，请查看重试进度",Collections.emptyList(),true);}
    }
    private static String matchingSku(TmallOrderParser.Line line,Map<String,String> manual){
        // Keep the source snapshot intact; only trim boundary whitespace for lookup and stock commands.
        String sku=manual.getOrDefault(line.oid,line.outerSku);int start=0,end=sku.length();
        while(start<end&&(Character.isWhitespace(sku.charAt(start))||Character.isSpaceChar(sku.charAt(start))))start++;
        while(end>start&&(Character.isWhitespace(sku.charAt(end-1))||Character.isSpaceChar(sku.charAt(end-1))))end--;
        return sku.substring(start,end);
    }
    private String safe(Exception e){String text=e instanceof IllegalArgumentException?e.getMessage():"服务调用或数据处理失败，任务已保留，将自动重试";if(text==null)text="订单处理失败";return text.substring(0,Math.min(480,text.length()));}
}

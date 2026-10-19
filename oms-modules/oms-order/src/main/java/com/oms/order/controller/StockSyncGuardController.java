package com.oms.order.controller;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.annotation.InnerAuth;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.order.service.OrderValues.*;
@RestController public class StockSyncGuardController {
 @Resource JdbcTemplate db;
 @SuppressWarnings("unchecked") @InnerAuth @PostMapping("/orders/internal/stock-sync-guard")
 public R<Map<String,Object>> guard(@RequestParam("company_code") String company,@RequestBody Map<String,Object> body){
  long channel=number(body,"channel_id");require(channel>0,"店铺无效");List<Map<String,Object>> configs=db.query("SELECT enabled,allow_public_stock,cursor_time,last_error,TIMESTAMPDIFF(SECOND,cursor_time,NOW()) lag_seconds FROM oms_order_pull_config WHERE company_code=? AND channel_id=?",ROW,company,channel);
  Map<String,Object> result=new LinkedHashMap<>();boolean ready=!configs.isEmpty()&&number(configs.get(0),"enabled")==1&&text(configs.get(0),"last_error").isEmpty()&&number(configs.get(0),"lag_seconds")>=0&&number(configs.get(0),"lag_seconds")<=120;String reason=ready?"":"订单拉取未启用、存在异常或进度超过120秒，暂停增加平台库存";
  result.put("allow_public_stock",!configs.isEmpty()&&number(configs.get(0),"allow_public_stock")==1);
  List<String> policies=db.queryForList("SELECT policy_json FROM oms_order_allocation_config WHERE company_code=? AND channel_id=?",String.class,company,channel);
  List<?> allowed=Collections.emptyList();if(!policies.isEmpty())try{Map<?,?> policy=JSON.readValue(policies.get(0),Map.class);if(policy.get("warehouse_order") instanceof List)allowed=(List<?>)policy.get("warehouse_order");}catch(Exception e){throw new IllegalArgumentException("店铺分仓规则无法读取，停止同步");}result.put("allowed_warehouses",allowed);
  Object raw=body.get("trades");require(raw==null||raw instanceof List,"平台交易快照无效");List<Map<String,Object>> trades=raw==null?Collections.emptyList():(List<Map<String,Object>>)raw;require(trades.size()<=200,"平台待处理交易过多，暂停补高库存");
  for(Map<String,Object> trade:trades){String tid=text(trade,"tid"),status=text(trade,"status"),modified=text(trade,"modified");require(tid.matches("[1-9][0-9]{0,31}")&&modified.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:]{8}"),"平台交易身份或更新时间无效");
   List<Map<String,Object>> rows=db.query("SELECT demand_registered,change_pending,platform_status,platform_modified FROM oms_platform_order WHERE company_code=? AND channel_id=? AND platform_tid=?",ROW,company,channel,tid);
   if(!"WAIT_SELLER_SEND_GOODS".equals(status)||rows.size()!=1||number(rows.get(0),"demand_registered")!=1||number(rows.get(0),"change_pending")!=0||!status.equals(text(rows.get(0),"platform_status"))||text(rows.get(0),"platform_modified").compareTo(modified)<0){ready=false;reason="存在平台未付款、尚未接入或尚未登记最新库存需求的交易，暂停增加平台库存";break;}
   require(trade.get("lines") instanceof List&&!((List<?>)trade.get("lines")).isEmpty(),"平台交易缺少规格与数量，暂停增加库存");
   for(Map<String,Object> line:(List<Map<String,Object>>)trade.get("lines")){
    require(number(line,"quantity")>0,"平台交易数量无效");
    long covered=db.queryForObject("SELECT COUNT(*) FROM oms_platform_order_line l JOIN oms_platform_order p ON p.company_code=l.company_code AND p.id=l.platform_order_id WHERE p.company_code=? AND p.channel_id=? AND p.platform_tid=? AND l.platform_oid=? AND l.platform_item_id=? AND COALESCE(NULLIF(l.platform_sku_id,''),'0')=? AND l.sku_sn=? AND l.quantity=? AND l.demand_prepared=1 AND l.match_error=''",Long.class,company,channel,tid,text(line,"oid"),text(line,"item_id"),text(line,"sku_id"),text(body,"sku_sn"),number(line,"quantity"));
    if(covered!=1){ready=false;reason="平台销售规格尚未完成商品匹配和库存需求登记，暂停增加库存";break;}
   }
  }
  result.put("can_increase",ready);result.put("reason",reason);return R.ok(result);
 }
}

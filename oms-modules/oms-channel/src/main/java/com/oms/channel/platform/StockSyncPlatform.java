package com.oms.channel.platform;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.Resource;
import java.util.*;
import static com.oms.channel.platform.ChannelPlatformService.map;
import static com.oms.channel.platform.PlatformInteractionLog.*;
/** Only normal Tmall item/SKU inventory. Never edits prices, listing status or warehouse inventory. */
@Service public class StockSyncPlatform {
 @Resource ChannelPlatformService platform;
 @Resource JdbcTemplate db;
 static final ObjectMapper JSON=new ObjectMapper();
 public long quantity(String company,long channel,Map<String,Object> link,Map<String,Object> context){
  String item=s(link,"item_id"),sku=s(link,"sku_id");
  Map<String,String> args=new LinkedHashMap<>();args.put("num_iid",item);args.put("fields","num_iid,num,sku,approve_status");
  JsonNode response=platform.stockCall(company,channel,"taobao.item.seller.get",args,false,context);
  JsonNode result=response.path("item_seller_get_response").path("item");require(item.equals(result.path("num_iid").asText()),"平台商品身份不一致，请核对映射");
  if("0".equals(sku)){require(!result.path("skus").path("sku").isArray()||result.path("skus").path("sku").size()==0,"此商品包含规格，必须指定平台SKU ID");return validQuantity(result.path("num"));}
  for(JsonNode child:result.path("skus").path("sku"))if(sku.equals(child.path("sku_id").asText()))return validQuantity(child.path("quantity"));
  throw new IllegalArgumentException("平台SKU不属于该商品，请核对数字ID映射");
 }
 static long validQuantity(JsonNode node){require(node.isIntegralNumber()&&node.canConvertToLong()&&node.asLong()>=0&&node.asLong()<=Integer.MAX_VALUE,"平台库存数量缺失或无效");return node.asLong();}
 public void normalMode(String company,long channel,Map<String,Object> link,Map<String,Object> context){
  Map<String,Object> query=map("item_id",s(link,"item_id"));if(!"0".equals(s(link,"sku_id")))query.put("sku_ids",Collections.singletonList(s(link,"sku_id")));
  JsonNode result=platform.stockCall(company,channel,"taobao.inventory.mode.query",Collections.singletonMap("inventory_mode",json(query)),false,context).path("inventory_mode_query_response").path("result");
  require(result.path("success").asBoolean(false),"无法确认平台库存模式，暂停同步");JsonNode modes=result.path("mode_results");long mode=modes.path("item_mode").asLong(-1);
  if(!"0".equals(s(link,"sku_id"))){String value=modes.path("sku_mode").asText().replace(Character.toString((char)34),"").replace("'","");java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|[,{;])\\s*"+s(link,"sku_id")+"\\s*[:,]\\s*([1-4])(?:[},;]|$)").matcher(value);require(m.find(),"平台规格库存模式缺失，暂停同步");mode=Long.parseLong(m.group(1));}
  require(mode==1,"本期仅支持平台普通库存模式，当前商品需要专用库存接口");
 }
 public void write(String company,long channel,Map<String,Object> link,long quantity,Map<String,Object> context){
  Map<String,String> args=new LinkedHashMap<>();args.put("item_id",s(link,"item_id"));args.put("options",json(map("type",1)));
  if("0".equals(s(link,"sku_id")))args.put("item_quantity",String.valueOf(quantity));else args.put("sku_quantities",json(Collections.singletonList(map("sku_id",s(link,"sku_id"),"quantity",quantity))));
  JsonNode root=platform.stockCall(company,channel,"tmall.item.quantity.update",args,true,context);
  if(!s(link,"item_id").equals(root.path("tmall_item_quantity_update_response").path("quantity_update_result").asText()))throw new TmallClient.Failure("STOCK_WRITE_RESULT_UNKNOWN",true);
 }
 public List<Map<String,Object>> pending(String company,long channel,List<Map<String,Object>> links,Map<String,Object> context){
  Set<String> items=new HashSet<>();for(Map<String,Object> link:links)if(n(link,"enabled")==1)items.add(s(link,"item_id")+"/"+s(link,"sku_id"));List<Map<String,Object>> trades=new ArrayList<>();
  for(String state:Arrays.asList("ALL_WAIT_PAY","WAIT_SELLER_SEND_GOODS")){
   JsonNode root=platform.stockCall(company,channel,"taobao.trades.sold.get",new LinkedHashMap<String,String>(){{put("fields","tid,status,modified,orders.num_iid,orders.sku_id,orders.num,orders.oid");put("status",state);put("page_size","100");put("page_no","1");put("type","fixed,auction,guarantee_trade,step,b2c_cod,super_market_trade,super_market_cod_trade,tmall_i18n");}},false,context).path("trades_sold_get_response");
   require(root.has("total_results")&&root.path("total_results").asLong(-1)>=0&&root.path("total_results").asLong()>-1,"平台交易核对响应不完整");require(root.path("total_results").asLong()<=100,"平台待处理交易超过单轮核对范围，暂停补高库存");
   JsonNode list=root.path("trades").path("trade");require((list.isArray()?list.size():0)==root.path("total_results").asInt(),"平台交易核对未完整返回，暂停补高库存");
   for(JsonNode trade:list){JsonNode orders=trade.path("orders").path("order");require(orders.isArray()&&orders.size()>0,"平台交易缺少商品范围，暂停补高库存");List<Map<String,Object>> related=new ArrayList<>();for(JsonNode line:orders)if(items.contains(line.path("num_iid").asText()+"/"+line.path("sku_id").asText("0")))related.add(map("oid",line.path("oid").asText(),"item_id",line.path("num_iid").asText(),"sku_id",line.path("sku_id").asText("0"),"quantity",line.path("num").asLong(-1)));if(!related.isEmpty())trades.add(map("tid",trade.path("tid").asText(),"status",trade.path("status").asText(),"modified",trade.path("modified").asText(),"lines",related));}
  }return trades;
 }
 static JsonNode simulate(JdbcTemplate db,String company,long channel,String method,Map<String,String> args){
  String item=args.get("num_iid");if(item==null)item=args.get("item_id");if(method.equals("taobao.inventory.mode.query"))item=parse(args.get("inventory_mode")).path("item_id").asText();
  if(method.equals("taobao.trades.sold.get")){List<Object> trades=new ArrayList<>();Set<String> tids=new HashSet<>();for(Map<String,Object> row:db.queryForList("SELECT pending_trades FROM channel_stock_sync_sim WHERE company_code=? AND channel_id=? AND pending_trades IS NOT NULL",company,channel)){for(JsonNode trade:parse(row.get("pending_trades"))){String status=trade.path("status").asText();boolean matches=args.get("status").equals("ALL_WAIT_PAY")?!status.equals("WAIT_SELLER_SEND_GOODS"):status.equals("WAIT_SELLER_SEND_GOODS");if(matches&&tids.add(trade.path("tid").asText()))trades.add(JSON.convertValue(trade,Map.class));}}return JSON.valueToTree(map("trades_sold_get_response",map("total_results",trades.size(),"trades",map("trade",trades))));}
  List<Map<String,Object>> rows=db.queryForList("SELECT * FROM channel_stock_sync_sim WHERE company_code=? AND channel_id=? AND item_id=? ORDER BY sku_id",company,channel,item);require(!rows.isEmpty(),"模拟平台尚未配置该商品，不会调用真实平台");
  if(method.equals("taobao.inventory.mode.query")){Map<String,Object> modes=new TreeMap<>();for(Map<String,Object> row:rows)modes.put(s(row,"sku_id"),row.get("inventory_mode"));return JSON.valueToTree(map("inventory_mode_query_response",map("result",map("success",true,"mode_results",map("item_mode",rows.get(0).get("inventory_mode"),"sku_mode",json(modes))))));}
  if(method.equals("taobao.item.seller.get")){List<Object> skus=new ArrayList<>();long quantity=0;for(Map<String,Object> row:rows){quantity+=n(row,"quantity");if(!"0".equals(s(row,"sku_id")))skus.add(map("sku_id",s(row,"sku_id"),"quantity",n(row,"quantity")));}return JSON.valueToTree(map("item_seller_get_response",map("item",map("num_iid",item,"num",quantity,"skus",map("sku",skus)))));}
  String sku="0";long quantity;if(args.containsKey("sku_quantities")){JsonNode value=parse(args.get("sku_quantities")).get(0);sku=value.path("sku_id").asText();quantity=value.path("quantity").asLong();}else quantity=Long.parseLong(args.get("item_quantity"));
  Map<String,Object> row=null;for(Map<String,Object> r:rows)if(sku.equals(s(r,"sku_id")))row=r;require(row!=null,"模拟平台SKU不存在");String fail=s(row,"fail_write");if("FAILED".equals(fail))throw new TmallClient.Failure("SIMULATED_STOCK_REJECTED",false);db.update("UPDATE channel_stock_sync_sim SET quantity=? WHERE company_code=? AND channel_id=? AND item_id=? AND sku_id=?",quantity,company,channel,item,sku);if("UNKNOWN".equals(fail))throw new TmallClient.Failure("SIMULATED_STOCK_TIMEOUT",true);return JSON.valueToTree(map("tmall_item_quantity_update_response",map("quantity_update_result",item)));
 }
 static JsonNode parse(Object raw){try{return JSON.readTree(String.valueOf(raw));}catch(Exception e){throw new IllegalArgumentException("平台库存数据无法解析");}}
}

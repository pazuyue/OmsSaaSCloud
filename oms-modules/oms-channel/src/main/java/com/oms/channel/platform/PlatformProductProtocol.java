package com.oms.channel.platform;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.oms.channel.platform.ChannelPlatformService.map;
import static com.oms.channel.platform.PlatformInteractionLog.*;
/** Local catalog fixtures share the simulated platform quantity store; never used by real apps. */
final class PlatformProductProtocol {
 private PlatformProductProtocol(){}
 static boolean handles(JdbcTemplate db,String c,long channel,String method,Map<String,String> args){return method.startsWith("taobao.items.")||(method.equals("taobao.item.seller.get")&&db.queryForObject("SELECT COUNT(*) FROM channel_product_sim WHERE company_code=? AND channel_id=? AND item_id=?",Long.class,c,channel,args.get("num_iid"))>0);}
 static JsonNode simulated(JdbcTemplate db,String c,long channel,String method,Map<String,String> args){
  if(method.startsWith("taobao.items.")){List<Map<String,Object>> all=new ArrayList<>();for(Map<String,Object> r:db.queryForList("SELECT item_id,payload FROM channel_product_sim WHERE company_code=? AND channel_id=? ORDER BY item_id",c,channel)){JsonNode p=StockSyncPlatform.parse(r.get("payload"));boolean on=p.path("approve_status").asText().equals("onsale");if(on==method.equals("taobao.items.onsale.get"))all.add(map("num_iid",s(r,"item_id"),"title",p.path("title").asText(),"modified",p.path("modified").asText("")));}int page=Integer.parseInt(args.get("page_no")),size=Integer.parseInt(args.get("page_size")),start=Math.min(all.size(),(page-1)*size);return StockSyncPlatform.JSON.valueToTree(map(method.equals("taobao.items.onsale.get")?"items_onsale_get_response":"items_inventory_get_response",map("total_results",all.size(),"items",map("item",all.subList(start,Math.min(all.size(),start+size))))));}
  List<String> rows=db.queryForList("SELECT payload FROM channel_product_sim WHERE company_code=? AND channel_id=? AND item_id=?",String.class,c,channel,args.get("num_iid"));require(rows.size()==1,"模拟平台没有该商品，不会请求真实平台");JsonNode item=StockSyncPlatform.parse(rows.get(0));Map<String,Long> quantities=new HashMap<>();for(Map<String,Object> q:db.queryForList("SELECT sku_id,quantity FROM channel_stock_sync_sim WHERE company_code=? AND channel_id=? AND item_id=?",c,channel,args.get("num_iid")))quantities.put(s(q,"sku_id"),n(q,"quantity"));JsonNode skus=item.path("skus").path("sku");long total=0;if(skus.isArray()&&skus.size()>0){for(JsonNode sku:skus){if(quantities.containsKey(sku.path("sku_id").asText()))((com.fasterxml.jackson.databind.node.ObjectNode)sku).put("quantity",quantities.get(sku.path("sku_id").asText()));total+=sku.path("quantity").asLong();}((com.fasterxml.jackson.databind.node.ObjectNode)item).put("num",total);}else if(quantities.containsKey("0"))((com.fasterxml.jackson.databind.node.ObjectNode)item).put("num",quantities.get("0"));return StockSyncPlatform.JSON.valueToTree(map("item_seller_get_response",map("item",item)));
 }
}

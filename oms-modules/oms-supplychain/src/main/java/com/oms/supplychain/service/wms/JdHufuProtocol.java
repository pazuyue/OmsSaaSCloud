package com.oms.supplychain.service.wms;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** JD Hufu ERP-WMS JSON contract and official tripartite MD5 signing. */
@Component
public class JdHufuProtocol implements WmsProtocol {
    private final ObjectMapper json=new ObjectMapper();
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public String provider(){return "JD_HUFU";}
    public String contentType(){return "application/json;charset=UTF-8";}
    public Wire request(Map<String,Object> c,String secret,String action,Map<String,Object> task,Map<String,Object> ticket,List<Map<String,Object>> lines){
        Map<String,Object> head=map("entryOrderCode",task.get("ticket_sn"),"ownerCode",task.get("external_owner"),"warehouseCode",task.get("external_warehouse"));
        Map<String,Object> payload;String method,path;
        if(action.equals("CREATE")){
            method="entryorder.create";path="entryOrderCreate";head.put("orderType","CGRK");head.put("purchaseOrderCode",ticket.get("original_sn"));head.put("totalOrderLines",String.valueOf(lines.size()));
            List<Map<String,Object>> details=new ArrayList<>();for(Map<String,Object> l:lines)details.add(map("orderLineNo",String.valueOf(l.get("id")),"itemCode",l.get("sku_sn"),"itemName",l.get("goods_name"),"ownerCode",task.get("external_owner"),"planQty",l.get("number_expected"),"inventoryType","ZP","batchCode",l.get("batch_code"),"purchasePrice",text(l.get("purchase_price"))));
            payload=map("entryOrder",head,"orderLines",details);
        }else if(action.equals("QUERY")){method="entryorder.query";path="entryOrderQuery";head.put("entryOrderId",task.get("external_order"));head.put("page",1);head.put("pageSize",100);payload=head;}
        else{method="order.cancel";path="orderCancel";payload=map("orderCode",task.get("ticket_sn"),"orderId",task.get("external_order"),"orderType","CGRK","ownerCode",task.get("external_owner"),"warehouseCode",task.get("external_warehouse"),"cancelReason","OMS申请取消采购入库");}
        String body=encode(payload);Map<String,String> p=new TreeMap<>();p.put("app_key",text(c.get("app_key")));p.put("customerId",text(c.get("customer_id")));p.put("method","jingdong.hufu."+method);p.put("format","json");p.put("sign_method","md5");p.put("v",text(c.get("api_version")));p.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(TIME));p.put("sign",QimenProtocol.sign(p,body,secret));
        String endpoint=text(c.get("endpoint")).replaceAll("/+$","");
        return new Wire(endpoint+"/"+path+"?"+p.entrySet().stream().map(e->QimenProtocol.enc(e.getKey())+"="+QimenProtocol.enc(e.getValue())).collect(Collectors.joining("&")),body,contentType());
    }
    public Reply reply(String action,String body){JsonNode root=parse(body);require(root.has("flag"),"虎符响应缺少业务结果");Reply r=new Reply();r.success="success".equalsIgnoreCase(root.path("flag").asText());r.externalOrder=root.path("entryOrderId").asText(root.path("entryOrder").path("entryOrderId").asText());r.status=root.path("entryOrder").path("status").asText(root.path("status").asText());r.message=root.path("message").asText();return r;}
    public Receipt callback(Map<String,Object> c,String secret,Map<String,String> p,String body){
        require("jingdong.hufu.entryorder.confirm".equals(p.get("method")),"不支持的虎符回传动作");require("md5".equals(p.get("sign_method"))&&text(c.get("api_version")).equals(p.get("v")),"虎符签名算法或协议版本不匹配");require(text(c.get("app_key")).equals(p.get("app_key"))&&text(c.get("customer_id")).equals(text(p.get("customerId"))),"虎符回传应用或客户不匹配");
        LocalDateTime sent;try{sent=LocalDateTime.parse(p.get("timestamp"),TIME);}catch(Exception e){throw new IllegalArgumentException("回传时间格式错误");}require(Math.abs(Duration.between(sent,LocalDateTime.now(ZoneId.of("Asia/Shanghai"))).getSeconds())<=600,"回传时间超出允许范围");require(MessageDigest.isEqual(QimenProtocol.sign(p,body,secret).getBytes(StandardCharsets.UTF_8),text(p.get("sign")).getBytes(StandardCharsets.UTF_8)),"虎符回传验签失败");
        JsonNode root=parse(body),head=root.path("entryOrder");require(head.isObject(),"虎符回传缺少入库单信息");Receipt r=new Receipt();r.ticketSn=head.path("entryOrderCode").asText();r.externalOrder=head.path("entryOrderId").asText();r.messageId=head.path("outBizCode").asText();r.owner=head.path("ownerCode").asText();r.warehouse=head.path("warehouseCode").asText();r.status=head.path("status").asText();r.complete="FULFILLED".equals(r.status)||"CLOSED".equals(r.status)||(head.has("confirmType")&&"0".equals(head.path("confirmType").asText()));
        JsonNode lines=root.path("orderLines");require(lines.isMissingNode()||lines.isArray(),"虎符收货明细必须是数组");
        if(head.hasNonNull("totalOrderLines"))r.totalLines=qty(head.path("totalOrderLines"));
        r.sourceLineCount=lines.size();
        for(JsonNode line:lines){JsonNode batches=line.path("batchs");int actual=qty(line.path("actualQty"));if(batches.isArray()&&!batches.isEmpty()){int sum=0;for(JsonNode batch:batches){int n=qty(batch.path("actualQty"));sum=Math.addExact(sum,n);r.lines.add(line(line,batch,n));}require(sum==actual,"虎符批次数量与明细不一致");}else r.lines.add(line(line,line,actual));}
        return r;
    }
    private Line line(JsonNode head,JsonNode batch,int qty){Line l=new Line();l.sku=head.path("itemCode").asText();l.owner=head.path("ownerCode").asText();l.batch=batch.path("batchCode").asText();String type=batch.path("inventoryType").asText("ZP");require(Arrays.asList("ZP","CC","JS","XS").contains(type),"未知虎符库存类型");if(type.equals("ZP"))l.good=qty;else l.bad=qty;return l;}
    private int qty(JsonNode node){long n=id(node.asText());require(n>=0&&n<=100000000,"虎符回传数量无效");return (int)n;}
    public String acknowledgement(boolean ok,String message){return encode(map("flag",ok?"success":"failure","code",ok?"0":"OMS_REJECTED","message",message));}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("虎符报文编码失败");}}
    private JsonNode parse(String body){try{require(body.length()<=1024*1024,"虎符报文超过1MB");return json.readTree(body);}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("虎符JSON报文格式错误");}}
}

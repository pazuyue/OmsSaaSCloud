package com.oms.supplychain.service.wms;

import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import static com.oms.supplychain.service.wms.WmsStore.*;

/** Qimen standard WMS XML protocol; signing includes the exact raw HTTP body. */
@Component
public class QimenProtocol implements WmsProtocol {
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    public String provider(){return "QIMEN";}
    public String contentType(){return "application/xml;charset=UTF-8";}
    public Wire request(Map<String,Object> c,String secret,String action,Map<String,Object> task,Map<String,Object> ticket,List<Map<String,Object>> lines){
        String body=body(action,task,ticket,lines),method="taobao.qimen."+(action.equals("CANCEL")?"order.cancel":"entryorder."+(action.equals("CREATE")?"create":"query"));
        Map<String,String> p=new TreeMap<>();p.put("app_key",text(c.get("app_key")));p.put("customerId",text(c.get("customer_id")));p.put("method",method);p.put("format","xml");p.put("sign_method","md5");p.put("v","2.0");p.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(TIME));p.put("sign",sign(p,body,secret));
        String query=p.entrySet().stream().map(e->enc(e.getKey())+"="+enc(e.getValue())).collect(Collectors.joining("&"));
        return new Wire(text(c.get("endpoint"))+"?"+query,body,contentType());
    }
    private String body(String action,Map<String,Object> task,Map<String,Object> ticket,List<Map<String,Object>> lines){
        String sn=tag("entryOrderCode",task.get("ticket_sn")),owner=tag("ownerCode",task.get("external_owner")),warehouse=tag("warehouseCode",task.get("external_warehouse"));
        if(action.equals("QUERY"))return "<request>"+sn+tag("entryOrderId",task.get("external_order"))+owner+warehouse+tag("page",1)+tag("pageSize",100)+"</request>";
        if(action.equals("CANCEL"))return "<request>"+tag("orderCode",task.get("ticket_sn"))+tag("orderId",task.get("external_order"))+tag("orderType","CGRK")+owner+warehouse+tag("cancelReason",task.get("cancel_reason"))+"</request>";
        StringBuilder b=new StringBuilder("<request><entryOrder>");b.append(sn).append(owner).append(warehouse).append(tag("orderType","CGRK")).append(tag("purchaseOrderCode",ticket.get("original_sn"))).append(tag("totalOrderLines",lines.size())).append("</entryOrder><orderLines>");
        for(Map<String,Object> l:lines)b.append("<orderLine>").append(tag("orderLineNo",l.get("id"))).append(tag("itemCode",l.get("sku_sn"))).append(tag("itemName",l.get("goods_name"))).append(owner).append(tag("planQty",l.get("number_expected"))).append(tag("inventoryType","ZP")).append(tag("batchCode",l.get("batch_code"))).append(tag("purchasePrice",l.get("purchase_price"))).append("</orderLine>");
        return b.append("</orderLines></request>").toString();
    }
    public Reply reply(String action,String body){Element root=parse(body);require(root.getTagName().equals("response"),"仓库响应不是标准 response 报文");Reply r=new Reply();r.code=value(root,"code");r.success=value(root,"flag").equalsIgnoreCase("success");r.message=value(root,"message");r.externalOrder=value(root,"entryOrderId");r.status=value(root,"status");return r;}
    public Receipt callback(Map<String,Object> config,String secret,Map<String,String> p,String body){
        require("taobao.qimen.entryorder.confirm".equals(p.get("method")),"不支持的仓库回传动作");
        require("md5".equals(p.get("sign_method"))&&"2.0".equals(p.get("v")),"仓库回传协议版本或签名算法不匹配");
        require(text(config.get("app_key")).equals(p.get("app_key")),"回传应用不匹配");
        require(text(config.get("customer_id")).equals(text(p.get("customerId"))),"回传客户标识不匹配");
        LocalDateTime sent;try{sent=LocalDateTime.parse(p.get("timestamp"),TIME);}catch(Exception e){throw new IllegalArgumentException("回传时间格式无效");}
        require(Math.abs(Duration.between(sent,LocalDateTime.now(ZoneId.of("Asia/Shanghai"))).getSeconds())<=600,"回传时间超出允许范围");
        require(MessageDigest.isEqual(sign(p,body,secret).getBytes(StandardCharsets.UTF_8),text(p.get("sign")).getBytes(StandardCharsets.UTF_8)),"仓库回传验签失败");
        Element root=parse(body),head=child(root,"entryOrder");require(head!=null,"回传缺少入库单信息");
        Receipt r=new Receipt();r.ticketSn=value(head,"entryOrderCode");r.externalOrder=value(head,"entryOrderId");r.messageId=value(head,"outBizCode");r.warehouse=value(head,"warehouseCode");r.owner=value(head,"ownerCode");r.status=value(head,"status");r.complete="FULFILLED".equals(r.status)||"CLOSED".equals(r.status)||"0".equals(value(head,"confirmType"));
        if(!value(head,"totalOrderLines").isEmpty())r.totalLines=quantity(value(head,"totalOrderLines"));
        Element container=child(root,"orderLines");if(container!=null)for(Element line:children(container,"orderLine")){
            r.sourceLineCount++;
            String sku=value(line,"itemCode"),owner=value(line,"ownerCode");int actual=quantity(value(line,"actualQty"));Element batches=child(line,"batchs");
            if(batches!=null&&!children(batches,"batch").isEmpty()){
                int sum=0;for(Element batch:children(batches,"batch")){int n=quantity(value(batch,"actualQty"));sum=Math.addExact(sum,n);r.lines.add(line(sku,owner,value(batch,"batchCode"),value(batch,"inventoryType"),n));}require(sum==actual,"批次数量与明细实收数量不一致");
            }else r.lines.add(line(sku,owner,value(line,"batchCode"),value(line,"inventoryType"),actual));
        }
        return r;
    }
    private Line line(String sku,String owner,String batch,String type,int qty){Line l=new Line();l.sku=sku;l.owner=owner;l.batch=batch;require(Arrays.asList("","ZP","CC","JS","XS").contains(type),"未知仓库库存类型");if(type.isEmpty()||type.equals("ZP"))l.good=qty;else l.bad=qty;return l;}
    private int quantity(String s){long v=id(s);require(v>=0&&v<=100000000,"回传数量超出范围");return (int)v;}
    public String acknowledgement(boolean success,String message){return "<response>"+tag("flag",success?"success":"failure")+tag("code",success?"0":"OMS_REJECTED")+tag("message",message)+"</response>";}
    public static String sign(Map<String,String> params,String body,String secret){StringBuilder s=new StringBuilder(secret);new TreeMap<>(params).forEach((k,v)->{if(!k.equals("sign")&&v!=null&&!v.isEmpty())s.append(k).append(v);});s.append(body).append(secret);return hash("MD5",s.toString());}
    public static String hash(String algorithm,String value){try{byte[] bytes=MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8));char[] hex="0123456789ABCDEF".toCharArray(),out=new char[bytes.length*2];for(int i=0;i<bytes.length;i++){int n=bytes[i]&255;out[i*2]=hex[n>>>4];out[i*2+1]=hex[n&15];}return new String(out);}catch(Exception e){throw new IllegalStateException(e);}}
    public static String enc(String v){try{return URLEncoder.encode(v,"UTF-8");}catch(java.io.UnsupportedEncodingException e){throw new IllegalStateException(e);}}
    public static String tag(String key,Object value){return "<"+key+">"+escape(text(value))+"</"+key+">";}
    private static String escape(String v){return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static Element parse(String body){try{require(body.length()<=1024*1024,"仓库报文超过1MB");DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);return f.newDocumentBuilder().parse(new InputSource(new StringReader(body))).getDocumentElement();}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("仓库XML报文格式错误");}}
    private static Element child(Element e,String name){for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&name.equals(n.getNodeName()))return (Element)n;return null;}
    private static List<Element> children(Element e,String name){List<Element> out=new ArrayList<>();for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element&&name.equals(n.getNodeName()))out.add((Element)n);return out;}
    private static String value(Element e,String name){NodeList n=e.getElementsByTagName(name);return n.getLength()==0?"":n.item(0).getTextContent().trim();}
}

package com.ruoyi.common.core.messaging;
import java.util.*;
/** Registered business topology. Runtime configuration never accepts arbitrary queue names. */
public enum QueueKind {
 PRODUCT_PULL("channel","平台商品拉取","oms.channel.product-pull.v1",2,1),
 PRODUCT_BIND("channel","平台商品自动绑定","oms.channel.product-bind.v1",1,1),
 SALES_DOCUMENT("order","销售出库单同步","oms.order.sales-document.v1",2,5),
 ORDER_PULL("order","订单拉取","oms.order.order-pull.v1",2,1),
 ORDER_CONVERT("order","平台订单转单","oms.order.order-convert.v1",2,1),
 REFUND_PULL("order","退款拉取","oms.order.refund-pull.v1",1,1),
 REFUND_CONVERT("order","平台退款转单","oms.order.refund-convert.v1",1,1),
 REFUND_FLOW("order","售后自动流程","oms.order.refund-flow.v1",1,1),
 REFUND_GOODS("order","退款分项货物处理","oms.order.refund-goods.v1",1,1),
 REFUND_PLATFORM("order","退款平台结果核对","oms.order.refund-platform.v1",1,1),
 REFUND_API_PULL("order","普通退款列表补充同步","oms.order.refund-api-pull.v1",1,1),
 REFUND_API_DETAIL("order","普通退款完整详情同步","oms.order.refund-api-detail.v1",1,1),
 EXCHANGE_PULL("order","换货拉取","oms.order.exchange-pull.v1",1,1),
 EXCHANGE_CONVERT("order","平台换货转单","oms.order.exchange-convert.v1",1,1),
 EXCHANGE_FLOW("order","换货自动流程","oms.order.exchange-flow.v1",1,1);
 public static final int MAX_SERVICE_CONCURRENCY=16;
 public static int maxConcurrency(String service){return "order".equals(service)?MAX_SERVICE_CONCURRENCY:12;}
 public boolean platformRateLimited(){return this==EXCHANGE_FLOW||this==PRODUCT_PULL||this==EXCHANGE_PULL||this==REFUND_FLOW||this==REFUND_PLATFORM||this==REFUND_API_PULL||this==REFUND_API_DETAIL;}
 public final String service,title,queue;public final int concurrency,prefetch;
 QueueKind(String service,String title,String queue,int concurrency,int prefetch){this.service=service;this.title=title;this.queue=queue;this.concurrency=concurrency;this.prefetch=prefetch;}
 public String dead(){return queue+".dead";}
 public static QueueKind of(String type){try{return valueOf(type);}catch(Exception e){throw new IllegalArgumentException("未登记的业务队列");}}
 public static List<QueueKind> service(String service){if(!Arrays.asList("channel","order").contains(service))throw new IllegalArgumentException("消息服务无效");List<QueueKind> kinds=new ArrayList<>();for(QueueKind kind:values())if(kind.service.equals(service))kinds.add(kind);return kinds;}
 public Map<String,Object> metadata(){Map<String,Object> m=new LinkedHashMap<>();m.put("business_type",name());m.put("service_code",service);m.put("title",title);m.put("queue_name",queue);m.put("dead_queue",dead());m.put("exchange","oms.work.v1");m.put("routing_key",name());m.put("queue_type","quorum");return m;}
}

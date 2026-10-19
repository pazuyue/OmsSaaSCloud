package com.oms.channel.platform;
import com.oms.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.annotation.Resource;import java.util.*;
import static com.oms.messaging.MessageOutbox.*;
@Component @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true") public class ProductBindMessage implements MessageHandler {
 @Resource PlatformProductService products;@Resource MessageOutbox outbox;
 public Map<String,Object> context(String c,Map<String,Object> m){Map<String,Object> r=new LinkedHashMap<>();r.put("path","/oms-channel/platformProducts");r.put("channelId",n(m,"business_id"));return r;}
 public String type(){return "PRODUCT_BIND";}
 public Outcome handle(String c,Map<String,Object> m){long channel=n(m,"business_id");int bound=products.autoBind(c,channel);products.matchWake(c,channel);return Outcome.done("本批自动绑定 "+bound+" 个SKU；未匹配商品按渠道配置等待后续重试");}
 public void replay(String c,Map<String,Object> m,String actor,String reason){long channel=n(m,"business_id");outbox.enqueue(c,type(),channel,0,s(m,"business_number"),"RETRY:"+s(m,"event_id")+":"+token(),0);}
}

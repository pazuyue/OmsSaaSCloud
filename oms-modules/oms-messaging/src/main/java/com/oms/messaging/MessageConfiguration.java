package com.oms.messaging;
import com.ruoyi.common.core.messaging.QueueKind;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
@Configuration @EnableScheduling @ConditionalOnProperty(name="oms.messaging.enabled",havingValue="true")
public class MessageConfiguration {
 @Bean public Declarables messageTopology(@Value("${oms.messaging.service}") String service){List<Declarable> all=new ArrayList<>();DirectExchange work=new DirectExchange("oms.work.v1",true,false),dead=new DirectExchange("oms.dead.v1",true,false);all.add(work);all.add(dead);for(QueueKind k:QueueKind.service(service)){org.springframework.amqp.core.Queue q=QueueBuilder.durable(k.queue).quorum().deadLetterExchange("oms.dead.v1").deadLetterRoutingKey(k.name()).build(),dlq=QueueBuilder.durable(k.dead()).quorum().build();all.add(q);all.add(dlq);all.add(BindingBuilder.bind(q).to(work).with(k.name()));all.add(BindingBuilder.bind(dlq).to(dead).with(k.name()));}return new Declarables(all);}
}

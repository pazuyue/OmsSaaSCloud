package com.oms.channel.platform;

import com.oms.messaging.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductPullMessageTest {
 @Test void failureBeforeBusinessEntryCanWakeTheSamePendingTask(){ProductPullMessage h=new ProductPullMessage();h.products=mock(PlatformProductService.class);h.products.db=mock(JdbcTemplate.class);h.outbox=mock(MessageOutbox.class);Map<String,Object> task=new HashMap<>();task.put("state","PENDING");task.put("work_version",1);task.put("platform_shop_id","shop1");task.put("channel_id",101);when(h.products.task("QM",1)).thenReturn(task);when(h.products.shop("QM",101)).thenReturn(Collections.singletonMap("expected_shop_id","shop1"));when(h.products.db.queryForObject(anyString(),eq(Long.class),eq("QM"),eq(1L))).thenReturn(0L);Map<String,Object> message=new HashMap<>();message.put("business_id",1);message.put("business_version",1);message.put("business_number","PRODUCTPULL1");message.put("event_id","MEsource");h.replay("QM",message,"tester","配置已恢复");verify(h.outbox).enqueue(eq("QM"),eq("PRODUCT_PULL"),eq(1L),eq(1L),eq("PRODUCTPULL1"),startsWith("RETRY:MEsource:"),eq(0L));verify(h.products,never()).retry(anyString(),anyLong());task.put("state","SUCCESS");assertThrows(IllegalArgumentException.class,()->h.replay("QM",message,"tester","重复"));}
 @Test void pendingBusinessWithAnActiveLeaseCannotBeRestarted(){ProductPullMessage h=new ProductPullMessage();h.products=mock(PlatformProductService.class);h.products.db=mock(JdbcTemplate.class);h.outbox=mock(MessageOutbox.class);Map<String,Object> task=new HashMap<>();task.put("state","RUNNING");task.put("work_version",1);task.put("platform_shop_id","shop1");task.put("channel_id",101);when(h.products.task("QM",1)).thenReturn(task);when(h.products.shop("QM",101)).thenReturn(Collections.singletonMap("expected_shop_id","shop1"));when(h.products.db.queryForObject(anyString(),eq(Long.class),eq("QM"),eq(1L))).thenReturn(1L);Map<String,Object> message=new HashMap<>();message.put("business_id",1);message.put("business_version",1);assertThrows(IllegalArgumentException.class,()->h.replay("QM",message,"tester","仍在执行"));verifyNoInteractions(h.outbox);}
}

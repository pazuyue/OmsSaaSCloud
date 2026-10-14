package com.ruoyi.job.task;
import com.ruoyi.job.domain.*;
import com.ruoyi.job.util.*;
import com.ruoyi.job.service.ISysJobLogService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.core.constant.ScheduleConstants;
import com.ruoyi.common.core.utils.SpringUtils;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.quartz.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FulfillmentScanTaskTest {
    static Map<String,Object> stats(long failures){Map<String,Object> r=new LinkedHashMap<>();for(String key:Arrays.asList("scanned","succeeded","failed","unconfirmed","pending","skipped","review_pending","scope_failures","omitted"))r.put(key,0L);r.put("failed",failures);r.put("scanned",failures);r.put("items",Collections.emptyList());return r;}
    public static class Example {public JobScanResult scan(){return new JobScanResult("receipts",stats(1));} public void legacy(){} }
    @Test void remoteFailureCannotBeReportedAsSuccessAndOnlyCallsSelectedOperation(){FulfillmentScanClient orders=mock(FulfillmentScanClient.class);WmsScheduleClient warehouse=mock(WmsScheduleClient.class);FulfillmentScanTask task=new FulfillmentScanTask();ReflectionTestUtils.setField(task,"orders",orders);ReflectionTestUtils.setField(task,"warehouse",warehouse);when(orders.scan(eq("receipts"),anyString())).thenReturn(R.ok(stats(1)));assertTrue(task.receipts().hasFailures());verifyNoInteractions(warehouse);when(orders.scan(eq("receipts"),anyString())).thenReturn(R.fail("private detail"));assertThrows(IllegalStateException.class,task::receipts);}
    @Test void boundedReportDropsUnexpectedPayloadAndRequiresCompleteCounters(){Map<String,Object> input=stats(0);Map<String,Object> item=new HashMap<>();item.put("reference_id",10L);item.put("reference_sn","XS_TEST");item.put("secret","should-never-appear");input.put("items",Collections.nCopies(100,item));JobScanResult result=new JobScanResult("dispatch",input);assertEquals(50,((List<?>)result.getData().get("items")).size());assertFalse(result.getData().toString().contains("should-never-appear"));input.remove("scanned");assertThrows(IllegalStateException.class,()->new JobScanResult("dispatch",input));}
    @Test void quartzPersistsBusinessOutcomeAndPreservesLegacyVoidTasks() throws Exception {
        GenericApplicationContext app=new GenericApplicationContext();ISysJobLogService logs=mock(ISysJobLogService.class);app.getBeanFactory().registerSingleton("jobLogs",logs);app.refresh();new SpringUtils().postProcessBeanFactory(app.getBeanFactory());
        try{SysJob job=new SysJob();job.setJobName("fixture");job.setJobGroup("DEFAULT");job.setInvokeTarget(Example.class.getName()+".scan()");JobExecutionContext context=mock(JobExecutionContext.class);JobDataMap values=new JobDataMap();values.put(ScheduleConstants.TASK_PROPERTIES,job);when(context.getMergedJobDataMap()).thenReturn(values);AtomicReference<Object> result=new AtomicReference<>();doAnswer(i->{result.set(i.getArgument(0));return null;}).when(context).setResult(any());when(context.getResult()).thenAnswer(i->result.get());
            new QuartzDisallowConcurrentExecution().execute(context);org.mockito.ArgumentCaptor<SysJobLog> cap=org.mockito.ArgumentCaptor.forClass(SysJobLog.class);verify(logs).addJobLog(cap.capture());assertEquals("1",cap.getValue().getStatus());assertTrue(cap.getValue().getJobMessage().contains("失败 1"));assertTrue(cap.getValue().getExecutionData().contains("receipts"));
            clearInvocations(logs);result.set(null);job.setInvokeTarget(Example.class.getName()+".legacy()");new QuartzDisallowConcurrentExecution().execute(context);verify(logs).addJobLog(cap.capture());assertEquals("0",cap.getValue().getStatus());assertNull(cap.getValue().getExecutionData());
        }finally{app.close();}
    }
}

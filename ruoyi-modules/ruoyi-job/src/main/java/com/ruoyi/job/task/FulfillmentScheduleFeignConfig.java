package com.ruoyi.job.task;
import feign.Request;
import feign.Retryer;
import org.springframework.context.annotation.Bean;
/** A scan may include several bounded warehouse calls; never replay a scan on timeout. */
public class FulfillmentScheduleFeignConfig {
    @Bean public Request.Options scanOptions(){return new Request.Options(5000,300000);}
    @Bean public Retryer scanRetryer(){return Retryer.NEVER_RETRY;}
}

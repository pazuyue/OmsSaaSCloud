package com.oms.order;
import com.oms.common.annotation.EnableOmsConfig;
import com.ruoyi.common.security.annotation.EnableRyFeignClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableOmsConfig
@ComponentScan(basePackages="com.oms")
@EnableRyFeignClients(basePackages={"com.oms","com.ruoyi"})
@SpringBootApplication
@EnableScheduling
public class OrderApplication {
    public static void main(String[] args){SpringApplication.run(OrderApplication.class,args);}
}

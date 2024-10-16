package com.oms.inventory;

import com.oms.common.annotation.EnableOmsConfig;
import com.ruoyi.common.security.annotation.EnableRyFeignClients;
import com.ruoyi.common.swagger.annotation.EnableCustomSwagger2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@EnableCustomSwagger2
@EnableOmsConfig
@ComponentScan(basePackages = "com.oms")
@EnableRyFeignClients(basePackages = {"com.oms","com.ruoyi"})
@SpringBootApplication
public class OmsInventoryApplication {
    public static void main(String[] args)
    {
        SpringApplication.run(OmsInventoryApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  OMS库存管理模块启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
                " .-------.       ____     __        \n" +
                " |  _ _   \\      \\   \\   /  /    \n" +
                " | ( ' )  |       \\  _. /  '       \n" +
                " |(_ o _) /        _( )_ .'         \n" +
                " | (_,_).' __  ___(_ o _)'          \n" +
                " |  |\\ \\  |  ||   |(_,_)'         \n" +
                " |  | \\ `'   /|   `-'  /           \n" +
                " |  |  \\    /  \\      /           \n" +
                " ''-'   `'-'    `-..-'              ");
    }
}

package com.oms.common.config;

import org.springframework.boot.web.servlet.ServletComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
public class FilterConfig {
    public FilterConfig() {
        System.out.println("==========FilterConfig2============");
    }
}

package com.ruoyi.system.company;
import java.util.Map;
public interface CompanyRuntimeProbe {
    Map<String,Object> inspect(String company,Map<String,String> routes,String version,boolean requireLoaded);
}

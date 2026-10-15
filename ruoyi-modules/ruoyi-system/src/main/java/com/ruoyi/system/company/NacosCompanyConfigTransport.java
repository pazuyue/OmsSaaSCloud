package com.ruoyi.system.company;
import com.alibaba.cloud.nacos.NacosConfigManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import org.springframework.util.DigestUtils;
@Component
public class NacosCompanyConfigTransport implements CompanyConfigTransport {
    @Resource private NacosConfigManager manager;
    @Value("${oms.company.config-suffix:${spring.profiles.active:dev}.yml}") private String suffix;
    private String id(String key){CompanyServices.require(CompanyServices.APPS.containsKey(key)&&suffix.matches("[A-Za-z0-9_-]+\\.yml"),"服务配置标识无效");return CompanyServices.APPS.get(key)+"-"+suffix;}
    public String read(String service){try{String value=manager.getConfigService().getConfig(id(service),manager.getNacosConfigProperties().getGroup(),3000);CompanyServices.require(value!=null&&!value.trim().isEmpty(),"目标服务配置不存在");return value;}catch(com.alibaba.nacos.api.exception.NacosException e){throw new IllegalStateException("配置中心暂时不可用，请重试");}}
    public boolean compareWrite(String service,String original,String updated){try{return manager.getConfigService().publishConfigCas(id(service),manager.getNacosConfigProperties().getGroup(),updated,DigestUtils.md5DigestAsHex(original.getBytes(StandardCharsets.UTF_8)));}catch(com.alibaba.nacos.api.exception.NacosException e){throw new IllegalStateException("配置发布结果待确认，请刷新发布记录");}}
}

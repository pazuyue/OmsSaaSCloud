package com.ruoyi.system.api;

import com.ruoyi.common.core.constant.ServiceNameConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.SysCompanyModelAssociationConfig;
import com.ruoyi.system.api.factory.RemoteUserFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(contextId = "remoteSysCompanyModelAssociationConfigService", value = ServiceNameConstants.SYSTEM_SERVICE, fallbackFactory = RemoteUserFallbackFactory.class)
public interface RemoteSysCompanyModelAssociationConfigService {

    @PostMapping("/companyModelAssociationConfig/getInfo")
    public R<SysCompanyModelAssociationConfig> getInfoByCompanyCode(@RequestParam("company_code") String companyCode);
}

package com.oms.common.filter;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.PatternMatchUtils;

import javax.annotation.Resource;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

@Slf4j
@WebFilter(filterName = "DynamicDatasourceInterceptorFilter", value = {
        "/*"
        /*"/owner/*",
        "/poInfo/*",
        "/supplier/*",
        "/realStore/*",
        "/simulationStore/*",
        "/category/*",
        "/color/*",
        "/goodsAdministration/*",
        "/size/*",
        "/info/*",*/
})
public class DynamicDatasourceInterceptorFilter extends BaseController implements Filter {

    @Resource
    TokenService tokenService;
    @Resource private com.oms.common.tenant.TenantRouting tenantRouting;
    // 设置排除路径
    private final List<String> excludeUrls =  Arrays.asList("/druid/**","/actuator/**");

    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest req=(HttpServletRequest)servletRequest;
        HttpServletResponse resp=(HttpServletResponse)servletResponse;
        com.oms.common.tenant.TenantRouting.clearContext();
        try {
            String path=req.getRequestURI();boolean inner=SecurityConstants.INNER.equals(req.getHeader(SecurityConstants.FROM_SOURCE));
            if(isExcluded(path)){chain.doFilter(req,resp);return;}
            if(inner&&"POST".equals(req.getMethod())&&"/tenantRouting/internal/probe".equals(path)){chain.doFilter(req,resp);return;}
            if(inner&&"POST".equals(req.getMethod())&&Arrays.asList(
                "/allocation/internal/daily-scan-all","/orders/internal/scan-all","/exchanges/internal/scan-all","/refunds/internal/scan-all",
                "/platform/log-maintenance/internal/purge-all",
                "/orders/internal/schedule/dispatch","/orders/internal/schedule/receipts",
                "/orders/internal/schedule/virtual","/orders/internal/schedule/sales",
                "/orders/internal/schedule/platform","/orders/internal/schedule/issues","/orders/internal/schedule/allocation",
                "/shipmentWms/internal/schedule/inbound").contains(path)) {chain.doFilter(new CustomHttpServletRequest(req),resp);return;}
            // Callback company comes from its bound URL; signature and connection binding are checked in the controller.
            if("POST".equals(req.getMethod())&&path.matches("^/wmsCallback/[A-Za-z0-9_-]{1,64}/[a-f0-9]{32}$")){chain.doFilter(req,resp);return;}
            String company;
            try {
                String[] supplied=req.getParameterValues("company_code");
                if(supplied!=null&&supplied.length!=1)throw new IllegalArgumentException("公司参数不能重复");
                boolean internalPath=path.matches("^/(orders|orderCatalog|orderStock|shipmentWms|allocation|exchangePlatform|exchangeReturns|refundReturns|platformLogBusiness)/internal(/.*)?$")
                    ||path.matches("^/platform/log-center/internal/(simulation|refund-sync)/(start|finish)$")
                    ||Arrays.asList("/goodsReferences/internal","/warehouseReferences/internal","/api/goods/purchaseLookup","/api/WmsInventory/addInventory").contains(path);
                if(inner&&internalPath)company=com.oms.common.tenant.TenantRouting.company(req.getParameter("company_code"));
                else {
                    company=com.oms.common.tenant.TenantRouting.company(getCompanyCode());
                    if(supplied!=null&&!company.equals(com.oms.common.tenant.TenantRouting.company(supplied[0])))throw new IllegalArgumentException("不能操作其他公司的数据");
                }
            }catch(RuntimeException e){reject(resp,400,"公司身份缺失、无效或与登录公司不一致");return;}
            CustomHttpServletRequest wrapper=new CustomHttpServletRequest(req);wrapper.addParameter("company_code",company);
            com.oms.common.tenant.TenantRouting.Binding scope;
            boolean continuation="GET".equals(req.getMethod())
                ||("POST".equals(req.getMethod())&&"/platform/log-center/business-states".equals(path))
                ||(inner&&!Arrays.asList("/shipmentWms/internal/submit","/shipmentWms/internal/resolve","/orderStock/internal/virtual-outbound").contains(path)&&!path.matches("^/orderStock/internal/(register|reserve|release|cancel|allocate|warehouse-allocate)$"))
                ||path.matches("^/orders/shipments/[0-9]+/(receipt-reviews/[0-9]+|reconcile-receipts|query-warehouse)$");
            try{scope=continuation?tenantRouting.openForContinuation(company):tenantRouting.open(company);}catch(IllegalArgumentException e){reject(resp,503,e.getMessage());return;}catch(IllegalStateException e){reject(resp,503,"公司执行开关暂时无法核实，请稍后重试");return;}
            try(com.oms.common.tenant.TenantRouting.Binding ignored=scope){chain.doFilter(wrapper,resp);}
        }finally{com.oms.common.tenant.TenantRouting.clearContext();}
    }
    private void reject(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(new ObjectMapper().writeValueAsString(error(message)));
    }


    public String getCompanyCode() {
        String company_code;
        LoginUser user = tokenService.getLoginUser();
        if (Objects.isNull(user))
            throw new RuntimeException("用户信息为空");
        company_code = user.getCompanyCode();
        if (Objects.isNull(company_code)) {
            throw new RuntimeException("公司信息为空");
        }
        return company_code;
    }

    @Override
    public void destroy() {
        System.out.println("过滤器被销毁了");

    }

    public boolean isExcluded(String requestURI) {
        for (String pattern : excludeUrls) {
            if (PatternMatchUtils.simpleMatch(pattern, requestURI)) {
                return true;
            }
        }
        return false;
    }
}

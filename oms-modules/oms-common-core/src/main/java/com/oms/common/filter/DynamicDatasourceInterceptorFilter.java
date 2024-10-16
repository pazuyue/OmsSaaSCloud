package com.oms.common.filter;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
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
    // 设置排除路径
    private final List<String> excludeUrls =  Arrays.asList("/druid/**","/actuator/**");

    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        //log.info("===> DynamicDatasourceInterceptor doFilter");
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        CustomHttpServletRequest requestWrapper = new CustomHttpServletRequest(req);
        HttpServletResponse resp = (HttpServletResponse) servletResponse;
        // 获取请求参数
        String company_code = req.getParameter("company_code");
        String requestURI = req.getRequestURI();

        if (isExcluded(requestURI)) {
            // 在排除列表中，跳过
            //log.debug("在排除列表中，跳过");
            chain.doFilter(requestWrapper, resp);
            return;
        }
        log.debug("requestURI:" + requestURI);
        log.debug("company_code:" + company_code);
        if (Objects.isNull(company_code)) {
            try {
                company_code = this.getCompanyCode();
                log.debug("获取用户登陆公司:{}", company_code);
            } catch (Exception exception) {
                // 创建错误信息
                AjaxResult errorInfo = error("company_code is null");
                ObjectMapper mapper = new ObjectMapper();
                String jsonInString = mapper.writeValueAsString(errorInfo);
                // 设置HTTP状态码为400 Bad Request
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                // 输出错误信息
                resp.getWriter().write(jsonInString);
                return; // 不再执行后续的过滤器和请求处理
            }
        }

        // 将集合存到自定义HttpServletRequestWrapper
        requestWrapper.addParameter("company_code", company_code);
        log.debug("parameterMap:{}", requestWrapper);
        //切换到对应poolName的数据源
        DynamicDataSourceContextHolder.clear();
        DynamicDataSourceContextHolder.push(company_code);
        chain.doFilter(requestWrapper, resp);
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

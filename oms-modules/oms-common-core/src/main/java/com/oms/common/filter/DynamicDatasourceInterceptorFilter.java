package com.oms.common.filter;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import com.ruoyi.common.security.service.TokenService;
import com.ruoyi.common.security.utils.SecurityUtils;
import com.ruoyi.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.Resource;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

@Slf4j
@WebFilter(filterName = "DynamicDatasourceInterceptorFilter", value = {"/*"})
public class DynamicDatasourceInterceptorFilter extends BaseController implements Filter {

    @Resource
    TokenService tokenService;
    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        log.info("===> DynamicDatasourceInterceptor doFilter");
        HttpServletRequest req = (HttpServletRequest)servletRequest;
        HttpServletResponse resp = (HttpServletResponse)servletResponse;
        // 获取请求参数
        String dataKey = req.getParameter("company_code");
        log.debug("dataKey:"+dataKey);

        if (Objects.isNull(dataKey)) {
            LoginUser user = tokenService.getLoginUser();
            dataKey = user.getCompanyCode();
            log.debug("获取用户登陆公司{}:",dataKey);
            if (Objects.isNull(dataKey)) {
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
        log.debug("切库dataKey{}:",dataKey);
        //切换到对应poolName的数据源
        DynamicDataSourceContextHolder.clear();
        DynamicDataSourceContextHolder.push(dataKey);
        chain.doFilter(req,resp);
    }

    @Override
    public void destroy() {
        System.out.println("过滤器被销毁了");

    }
}

package com.oms.common.filter;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.core.constant.HttpStatus;
import com.ruoyi.common.core.web.controller.BaseController;
import com.ruoyi.common.core.web.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Value;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

@WebFilter(filterName = "DynamicDatasourceInterceptorFilter", value = {"/*"})
public class DynamicDatasourceInterceptorFilter extends BaseController implements Filter {

    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        System.out.println("===> DynamicDatasourceInterceptor doFilter");
        HttpServletRequest req = (HttpServletRequest)servletRequest;
        HttpServletResponse resp = (HttpServletResponse)servletResponse;
        // 获取请求参数
        String dataKey = req.getParameter("company_code");
        System.out.println("dataKey:"+dataKey);

        if (Objects.isNull(dataKey)) {
            // 创建错误信息
            //String errorMessage = "{'msg':'company_code is null','code':'"+ HttpStatus.ERROR+"'}";
            AjaxResult errorInfo = error("company_code is null");
            ObjectMapper mapper = new ObjectMapper();
            String jsonInString = mapper.writeValueAsString(errorInfo);

            // 设置HTTP状态码为400 Bad Request
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);

            // 输出错误信息
            resp.getWriter().write(jsonInString);
            return; // 不再执行后续的过滤器和请求处理
        }

        System.out.println("切库dataKey:"+dataKey);
        DynamicDataSourceContextHolder.clear();
        //切换到对应poolName的数据源
        DynamicDataSourceContextHolder.push(dataKey);

        chain.doFilter(req,resp);
    }
}

package com.oms.common.filter;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import org.springframework.beans.factory.annotation.Value;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

@WebFilter(filterName = "DynamicDatasourceInterceptorFilter", value = {"/*"})
public class DynamicDatasourceInterceptorFilter implements Filter {

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
        DynamicDataSourceContextHolder.clear();
        if (!Objects.isNull(dataKey)) {
            System.out.println("切库dataKey:"+dataKey);
            //切换到对应poolName的数据源
            DynamicDataSourceContextHolder.push(dataKey);
        }

        chain.doFilter(req,resp);
    }
}

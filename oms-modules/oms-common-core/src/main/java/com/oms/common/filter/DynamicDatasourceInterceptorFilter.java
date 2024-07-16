package com.oms.common.filter;

import org.springframework.beans.factory.annotation.Value;
import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebFilter(filterName = "DynamicDatasourceInterceptorFilter", value = {"/*"})
public class DynamicDatasourceInterceptorFilter implements Filter {
    @Value("${databaseName:false}")
    private String databaseName;

    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain) throws IOException, ServletException {
        System.out.println("===> DynamicDatasourceInterceptor doFilter");
        HttpServletRequest req = (HttpServletRequest)servletRequest;
        HttpServletResponse resp = (HttpServletResponse)servletResponse;
        databaseName = databaseName.toLowerCase();
        System.out.println("databaseName:"+databaseName);
       /* String companyCode = jwtInfo.getCompanyCode().toLowerCase();
        databaseName = databaseName.toLowerCase();
        String dataKey =companyCode+"_"+databaseName;
        System.out.println("dataKey:"+dataKey);
        DynamicDataSourceContextHolder.clear();
        //切换到对应poolName的数据源
        DynamicDataSourceContextHolder.push(dataKey);*/
        chain.doFilter(req,resp);
    }
}

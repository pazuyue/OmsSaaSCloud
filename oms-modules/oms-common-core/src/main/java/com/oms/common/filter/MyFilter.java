package com.oms.common.filter;

import javax.servlet.*;
import javax.servlet.annotation.WebFilter;
import java.io.IOException;

@WebFilter(filterName = "MyFilter",urlPatterns = {"/*"})
public class MyFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) {
        System.out.println("初始化MyFilter过滤器");
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        System.out.println("进入目标资源之前先干点啥");
        filterChain.doFilter(servletRequest, servletResponse);
        System.out.println("处理一下服务端返回的response");
    }

    @Override
    public void destroy() {
        System.out.println("过滤器被销毁了");
    }
}

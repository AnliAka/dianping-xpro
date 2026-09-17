package com.dianping.xpro.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.dianping.xpro.utils.Intercepter;
import com.dianping.xpro.utils.RefreshIntercepter;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private Intercepter intercepter;
    @Autowired
    private RefreshIntercepter refreshIntercepter;
    
    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        registry.addInterceptor(intercepter)
                .excludePathPatterns(
                    "/shop/**",
                    "/shop-type/**",
                    "/user/login",
                    "/user/code"
                    )
                .order(1);
        registry.addInterceptor(refreshIntercepter)
                .addPathPatterns("/**")
                .order(0);
    }
}

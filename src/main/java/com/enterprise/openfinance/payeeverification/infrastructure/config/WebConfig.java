package com.enterprise.openfinance.payeeverification.infrastructure.config;

import com.enterprise.openfinance.payeeverification.infrastructure.security.ApiHeaderGuardInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiHeaderGuardInterceptor apiHeaderGuardInterceptor;

    public WebConfig(ApiHeaderGuardInterceptor apiHeaderGuardInterceptor) {
        this.apiHeaderGuardInterceptor = apiHeaderGuardInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiHeaderGuardInterceptor)
                .addPathPatterns("/open-finance/v1/**");
    }
}

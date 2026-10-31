package com.nextstep.api.identity;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class IdentityWebConfig implements WebMvcConfigurer {

    private final EmailVerificationGate gate;

    IdentityWebConfig(EmailVerificationGate gate) {
        this.gate = gate;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate).addPathPatterns("/api/v1/**");
    }
}

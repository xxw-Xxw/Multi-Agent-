package com.docreview.config;

import com.docreview.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AppProperties props;

    public WebConfig(AuthInterceptor authInterceptor, AppProperties props) {
        this.authInterceptor = authInterceptor;
        this.props = props;
    }

    /** 允许来源通过 app.cors.allowed-origins 配置；Cookie 鉴权需要 allowCredentials。 */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.getCors().getAllowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(props.getCors().getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /** 除注册登录外的 /api/** 全部要求有效 JWT。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}

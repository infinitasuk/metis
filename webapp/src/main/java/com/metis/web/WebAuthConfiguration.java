package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import com.metis.engine.api.BattleRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebAuthConfiguration implements WebMvcConfigurer {

    private final AuthenticationContext authentication;
    private final BattleRepository battle;

    public WebAuthConfiguration(AuthenticationContext authentication, BattleRepository battle) {
        this.authentication = authentication;
        this.battle = battle;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new WebAuthInterceptor(authentication, battle));
    }
}

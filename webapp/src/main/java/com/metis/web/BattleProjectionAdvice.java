package com.metis.web;

import com.metis.engine.api.AuthenticationContext;
import com.metis.engine.api.BattleViews;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Covers all JSON read/mutation responses, including alternate admin log routes. */
@ControllerAdvice
public class BattleProjectionAdvice implements ResponseBodyAdvice<Object> {
    private final BattleViews views;
    private final AuthenticationContext authentication;
    public BattleProjectionAdvice(BattleViews views,AuthenticationContext authentication) { this.views=views; this.authentication=authentication; }
    @Override public boolean supports(MethodParameter type,Class<? extends HttpMessageConverter<?>> converter) {
        return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);
    }
    @Override public Object beforeBodyWrite(Object body,MethodParameter type,MediaType media,Class<? extends HttpMessageConverter<?>> converter,
                                           ServerHttpRequest request,ServerHttpResponse response) {
        if(!(request instanceof ServletServerHttpRequest servlet)) return body;
        var user=authentication.current(servlet.getServletRequest());
        if(user.isEmpty()) return body;
        var match=java.util.regex.Pattern.compile("/sessions/(\\d+)").matcher(request.getURI().getPath());
        Long id=match.find()?Long.valueOf(match.group(1)):null;
        return views.project(body,id,user.get());
    }
}

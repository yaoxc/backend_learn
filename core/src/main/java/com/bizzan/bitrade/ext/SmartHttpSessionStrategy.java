package com.bizzan.bitrade.ext;

import org.apache.commons.lang.StringUtils;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.HeaderHttpSessionIdResolver;
import org.springframework.session.web.http.HttpSessionIdResolver;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.List;

/**
 * 升级说明：Spring Session 2.x 移除了 HttpSessionStrategy / CookieHttpSessionStrategy / HeaderHttpSessionStrategy，
 * 改为 HttpSessionIdResolver / CookieHttpSessionIdResolver / HeaderHttpSessionIdResolver。
 * 本类改为实现 HttpSessionIdResolver，按请求头 x-auth-token 选择 Cookie 或 Header 方式解析/写入 SessionId。
 */
public class SmartHttpSessionStrategy implements HttpSessionIdResolver {
    private final CookieHttpSessionIdResolver browser = new CookieHttpSessionIdResolver();
    private final HeaderHttpSessionIdResolver api = new HeaderHttpSessionIdResolver("x-auth-token");
    private static final String TOKEN_NAME = "x-auth-token";

    @Override
    public List<String> resolveSessionIds(HttpServletRequest request) {
        String paramToken = request.getParameter(TOKEN_NAME);
        if (StringUtils.isNotEmpty(paramToken)) {
            return Collections.singletonList(paramToken);
        }
        return getResolver(request).resolveSessionIds(request);
    }

    @Override
    public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
        getResolver(request).setSessionId(request, response, sessionId);
    }

    @Override
    public void expireSession(HttpServletRequest request, HttpServletResponse response) {
        getResolver(request).expireSession(request, response);
    }

    private HttpSessionIdResolver getResolver(HttpServletRequest request) {
        return request.getHeader(TOKEN_NAME) != null ? this.api : this.browser;
    }
}

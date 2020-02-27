package com.mmall.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
public class CookieUtil {

    private final static String COOKIE_DOMAIN = "dy.com";  // 设置把Cookie写在哪个域名下，此处写在一级域名下。
    private final static String COOKIE_NAME = "mmall_login_token";

    public static void writeLoginToken(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setDomain(COOKIE_DOMAIN);
        cookie.setPath("/"); // 代表根目录
        cookie.setHttpOnly(true); // 设置为true，防止脚本攻击
        // 单位是秒
        // 如果这个maxage不设置，cookie就不会存入硬盘，而是存入内存，只在当前页面有效
        cookie.setMaxAge(60 * 60 * 24 * 365); // 如果是-1，代表永久
        log.info("write cookieName:{}, cookieValue:{}", cookie.getName(), cookie.getValue());
        response.addCookie(cookie);
    }

    public static String readLoginToken(HttpServletRequest request) {
        Cookie[] cks = request.getCookies();
        for (Cookie ck : cks) {
            log.info("read cookieName:{},cookieValue:{}", ck.getName(), ck.getValue());
            if (StringUtils.equals(ck.getName(), COOKIE_NAME)) {
                log.info("return cookieName:{},cookieValue:{}", ck.getName(), ck.getValue());
                return ck.getValue();
            }
        }
        return null;
    }

    public static void delLoginToken(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cks = request.getCookies();
        for (Cookie ck : cks) {
            if (StringUtils.equals(ck.getName(), COOKIE_NAME)) {
                ck.setDomain(COOKIE_DOMAIN);
                ck.setPath("/");
                ck.setMaxAge(0); // 设置生存期为0，表示删除此cookie
                log.info("del cookieName:{},cookieValue:{}", ck.getName(), ck.getValue());
                response.addCookie(ck);
                return;
            }
        }
    }
}

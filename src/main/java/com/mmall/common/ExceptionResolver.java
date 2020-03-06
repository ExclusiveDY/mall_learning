package com.mmall.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.json.MappingJacksonJsonView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
@Component
public class ExceptionResolver implements HandlerExceptionResolver {
    @Override
    public ModelAndView resolveException(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object o, Exception e) {
        log.error("{} Exception", httpServletRequest, e);
        ModelAndView modelAndView = new ModelAndView(new MappingJacksonJsonView());
        // 当使用的Jackson是2.0版本时，需要使用MappingJackson2JsonView，此项目使用1.9故使用原来的。
        // 此处返回要选择与ServerResponse中一样的格式，这样才不会让前端特殊处理
        modelAndView.addObject("status", ResponseCode.ERROR.getCode());
        modelAndView.addObject("msg", "接口异常，详情请查看服务端日志");
        modelAndView.addObject("data", e.toString());
        return modelAndView;
    }
}

package com.fss.common.web;

import com.fss.common.result.R;
import com.fss.common.trace.TraceContext;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 给所有 {@link R} 响应体统一补 traceId。
 *
 * <p>放在这里而不是每个 Controller 里手填：漏填一个就会让那条接口的报障无法定位，
 * 而这种遗漏在 code review 里很难看出来。
 */
@RestControllerAdvice
public class TraceIdResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType contentType, Class converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof R<?> r && r.getTraceId() == null) {
            r.setTraceId(TraceContext.get());
        }
        return body;
    }
}

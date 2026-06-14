package info.wesite.web.config;

// GlobalExceptionHandler.java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;

import info.wesite.core.utils.Constants;
import info.wesite.core.view.ResponseJson;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理自定义的资源未找到异常
     * 当控制器中抛出ResourceNotFoundException时，会跳转到自定义404页面
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView handleResourceNotFound(ResourceNotFoundException ex) {
        ModelAndView modelAndView = new ModelAndView("domain_404");
        modelAndView.addObject("errorMessage", ex.getMessage());
        modelAndView.addObject("resourceName", ex.getResourceName());
        modelAndView.addObject("resourceId", ex.getResourceId());
        modelAndView.addObject("errorType", "业务404 - 资源不存在");
        modelAndView.addObject(Constants.PAGE_TITLE, "Domain Not Found - Whose.Domains");
        return modelAndView;
    }

    /**
     * 处理NoHandlerFoundException - 访问不存在的URL
     * 需要配置: spring.mvc.throw-exception-if-no-handler-found=true
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ModelAndView handleNoHandlerFound(NoHandlerFoundException ex) {
        ModelAndView modelAndView = new ModelAndView("404");
        modelAndView.addObject("errorPath", ex.getRequestURL());
        modelAndView.addObject("errorType", "系统404 - 页面不存在");
        modelAndView.addObject("errorMessage", "请求的页面不存在: " + ex.getRequestURL());
        modelAndView.addObject(Constants.PAGE_TITLE, "Page Not Found - Whose.Domains");
        return modelAndView;
    }

    /**
     * 处理所有未捕获的异常 - 500错误
     * API请求返回JSON，页面请求返回错误页面
     */
    @ExceptionHandler(Exception.class)
    public Object handleGenericException(Exception ex, HttpServletRequest request, HttpServletResponse response) {
        logger.error("Unexpected error occurred", ex);
        
        // API请求返回JSON格式
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return ResponseEntity.status(HttpStatus.OK)
                    .body(ResponseJson.failure("Server error: " + ex.getMessage()));
        }
        
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        ModelAndView modelAndView = new ModelAndView("500");
        modelAndView.addObject("errorMessage", "An unexpected error occurred. Please try again later.");
        modelAndView.addObject(Constants.PAGE_TITLE, "Server Error - Whose.Domains");
        return modelAndView;
    }
}

// 错误响应DTO
class ErrorResponse {
    private String error;
    private String message;
    private int status;
    private long timestamp;

    public ErrorResponse(String error, String message, int status) {
        this.error = error;
        this.message = message;
        this.status = status;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
package org.dromara.web.directory;

import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;

/** Re-check external lifecycle on authenticated requests, even if session eviction is delayed. */
@Configuration
public class DirectorySessionGuard implements WebMvcConfigurer {
    private final DirectoryAccounts accounts;
    public DirectorySessionGuard(DirectoryAccounts accounts){this.accounts=accounts;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(new HandlerInterceptor(){
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler){
                // Error dispatch runs after the authentication context has been cleaned up.
                // The original request has already passed this lifecycle check.
                if(request.getDispatcherType()==jakarta.servlet.DispatcherType.ERROR)return true;
                if(!cn.dev33.satoken.stp.StpUtil.isLogin())return true;
                var user=LoginHelper.getLoginUser();
                if(user!=null&&user.getExternalSource()!=null)accounts.requireActive(user.getTenantId(),user.getUserId(),user.getExternalSource(),user.getExternalVersion());
                return true;
            }
        }).addPathPatterns("/**").excludePathPatterns("/auth/login","/auth/logout");
    }
}

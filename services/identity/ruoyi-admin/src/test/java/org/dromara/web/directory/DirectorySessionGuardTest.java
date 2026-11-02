package org.dromara.web.directory;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class DirectorySessionGuardTest {
    static class Registry extends InterceptorRegistry {
        List<Object> handlers(){return getInterceptors();}
    }
    @Test void errorRedispatchDoesNotRequireTheAlreadyClearedAuthenticationContext() throws Exception {
        var accounts=mock(DirectoryAccounts.class);var registry=new Registry();
        new DirectorySessionGuard(accounts).addInterceptors(registry);
        var interceptor=(org.springframework.web.servlet.handler.MappedInterceptor)registry.handlers().get(0);
        var request=new MockHttpServletRequest("GET","/error");request.setDispatcherType(DispatcherType.ERROR);
        assertTrue(interceptor.getInterceptor().preHandle(request,new MockHttpServletResponse(),new Object()));
        verifyNoInteractions(accounts);
    }
}

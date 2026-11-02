package org.dromara.web.directory;

import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class DirectoryCapabilitiesTest {
    @Test void capabilitiesUseCurrentTenantAndActorAndKeepReadSeparateFromWrite(){
        var access=mock(DirectoryAccess.class);
        var controller=new DirectoryController(null,null,null,access,null);
        try(var login=mockStatic(LoginHelper.class)){
            login.when(LoginHelper::getTenantId).thenReturn("tenant-a");
            login.when(LoginHelper::getUserId).thenReturn(42L);
            when(access.allowed("tenant-a",42L,false)).thenReturn(true);
            var result=controller.capabilities().getData();
            assertEquals(true,result.get("canRead"));assertEquals(false,result.get("canWrite"));
            when(access.allowed("tenant-a",42L,true)).thenReturn(true);
            assertEquals(true,controller.capabilities().getData().get("canWrite"));
            when(access.allowed("tenant-a",42L,false)).thenReturn(false);
            when(access.allowed("tenant-a",42L,true)).thenReturn(false);
            assertTrue(controller.capabilities().getData().values().stream().noneMatch(Boolean::booleanValue));
            verify(access,atLeastOnce()).allowed("tenant-a",42L,false);
            verify(access,atLeastOnce()).allowed("tenant-a",42L,true);
        }
    }
}

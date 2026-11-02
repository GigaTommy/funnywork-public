package org.dromara.web.directory;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.vo.SysUserImportVo;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.listener.SysUserImportListener;
import org.dromara.system.service.ISysConfigService;
import org.dromara.system.service.ISysUserService;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class UserImportFeedbackTest {
    @Test void partialImportReportsAlreadySavedRowsInsteadOfClaimingTotalFailure() {
        var users=mock(ISysUserService.class);var config=mock(ISysConfigService.class);
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("isolated-test-password");
        var existing=new SysUserVo();existing.setUserName("existing");
        when(users.selectUserByUserName("existing")).thenReturn(existing);
        try(var spring=mockStatic(cn.hutool.extra.spring.SpringUtil.class);var login=mockStatic(LoginHelper.class)) {
            spring.when(()->SpringUtils.getBean(jakarta.validation.Validator.class)).thenReturn(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
            spring.when(()->SpringUtils.getBean(ISysConfigService.class)).thenReturn(config);
            spring.when(()->SpringUtils.getBean(ISysUserService.class)).thenReturn(users);
            login.when(LoginHelper::getUserId).thenReturn(1L);
            var listener=new SysUserImportListener(false);
            var fresh=new SysUserImportVo();fresh.setUserName("fresh");fresh.setNickName("New local user");
            var duplicate=new SysUserImportVo();duplicate.setUserName("existing");
            listener.invoke(fresh,null);listener.invoke(duplicate,null);
            var error=assertThrows(ServiceException.class,()->listener.getExcelResult().getAnalysis());
            assertTrue(error.getMessage().contains("成功 1 条，失败 1 条"));
            assertTrue(error.getMessage().contains("成功记录已保存"));
            verify(users,times(1)).insertUser(any());verify(users,never()).updateUser(any());
        }
    }
    @Test void updateImportDoesNotOverwriteExternalIdentityProfile() {
        var users=mock(ISysUserService.class);var config=mock(ISysConfigService.class);
        when(config.selectConfigByKey("sys.user.initPassword")).thenReturn("isolated-test-password");
        var external=new SysUserVo();external.setUserName("external");external.setExternalSource("corporate");
        when(users.selectUserByUserName("external")).thenReturn(external);
        try(var spring=mockStatic(cn.hutool.extra.spring.SpringUtil.class);var login=mockStatic(LoginHelper.class)) {
            spring.when(()->SpringUtils.getBean(jakarta.validation.Validator.class)).thenReturn(jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
            spring.when(()->SpringUtils.getBean(ISysConfigService.class)).thenReturn(config);
            spring.when(()->SpringUtils.getBean(ISysUserService.class)).thenReturn(users);
            login.when(LoginHelper::getUserId).thenReturn(1L);
            var listener=new SysUserImportListener(true);var imported=new SysUserImportVo();imported.setUserName("external");imported.setNickName("must-not-replace");
            listener.invoke(imported,null);
            var error=assertThrows(ServiceException.class,()->listener.getExcelResult().getAnalysis());
            assertTrue(error.getMessage().contains("成功 0 条，失败 1 条"));assertTrue(error.getMessage().contains("身份源维护"));
            verify(users,never()).updateUser(any());verify(users,never()).insertUser(any());
        }
    }
}

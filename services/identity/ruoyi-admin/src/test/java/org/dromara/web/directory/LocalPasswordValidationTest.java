package org.dromara.web.directory;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.dromara.common.core.utils.PasswordValidation;
import org.dromara.common.core.utils.SpringUtils;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class LocalPasswordValidationTest {
    @Test void storedPasswordsMustSatisfyTheActualLoginConstraints() {
        try(var factory=Validation.buildDefaultValidatorFactory();var spring=mockStatic(cn.hutool.extra.spring.SpringUtil.class)) {
            spring.when(()->SpringUtils.getBean(Validator.class)).thenReturn(factory.getValidator());
            for(String invalid:new String[]{null,"","    ","1234","x".repeat(31)})
                assertThrows(ConstraintViolationException.class,()->PasswordValidation.requireLoginCompatible(invalid));
            for(String valid:new String[]{"12345","x".repeat(30),"密码验收五字"})
                assertDoesNotThrow(()->PasswordValidation.requireLoginCompatible(valid));
        }
    }
}

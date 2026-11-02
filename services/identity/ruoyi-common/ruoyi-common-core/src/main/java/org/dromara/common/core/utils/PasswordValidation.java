package org.dromara.common.core.utils;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.dromara.common.core.domain.model.PasswordLoginBody;

/** Reuse login rules before storing a local credential so it remains usable. */
public final class PasswordValidation {
    private PasswordValidation() {}
    public static void requireLoginCompatible(String password) {
        var violations = SpringUtils.getBean(Validator.class)
            .validateValue(PasswordLoginBody.class, "password", password);
        if (!violations.isEmpty()) throw new ConstraintViolationException("密码不符合登录要求", violations);
    }
}

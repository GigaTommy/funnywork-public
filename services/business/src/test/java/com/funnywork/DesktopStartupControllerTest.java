package com.funnywork;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopStartupControllerTest {
    @Test void usesExistingServicesAndIssuesCredentialOnlyAfterPolicyAndCatalogSucceed() {
        var models = mock(ModelMiddleware.class); var policies = mock(DesktopPolicies.class);
        var actor = mock(Identity.class);
        var request = new DesktopStartupController.Request("model-device", "a".repeat(32));
        when(models.bootstrap(actor)).thenReturn(Map.of("enabled", true));
        when(models.catalog(actor)).thenReturn(List.of(Map.of("provider_name", "OpenAI")));
        when(policies.issue(actor, request.policyDevice())).thenReturn(Map.of("schema", 1));
        when(models.credential(actor, request.modelDevice())).thenReturn(Map.of("token", "scoped-only"));
        var result = new DesktopStartupController(models, policies).startup(actor, request);
        assertEquals(1, result.get("schema"));
        var order = inOrder(models, policies);
        order.verify(models).bootstrap(actor); order.verify(policies).issue(actor, request.policyDevice());
        order.verify(models).catalog(actor); order.verify(models).credential(actor, request.modelDevice());
        verifyNoMoreInteractions(models, policies);
    }
    @Test void deniedPolicyCannotIssueCredentials() {
        var models = mock(ModelMiddleware.class); var policies = mock(DesktopPolicies.class); var actor = mock(Identity.class);
        when(models.bootstrap(actor)).thenReturn(Map.of("enabled", true));
        when(policies.issue(eq(actor), anyString())).thenThrow(ApiException.forbidden());
        assertThrows(ApiException.class, () -> new DesktopStartupController(models, policies).startup(actor,
            new DesktopStartupController.Request("device", "a".repeat(32))));
        verify(models, never()).credential(any(), anyString());
    }
    @Test void disabledModelsCannotIssuePolicyOrCredentials() {
        var models = mock(ModelMiddleware.class); var policies = mock(DesktopPolicies.class); var actor = mock(Identity.class);
        when(models.bootstrap(actor)).thenReturn(Map.of("enabled", false));
        assertThrows(ApiException.class, () -> new DesktopStartupController(models, policies).startup(actor,
            new DesktopStartupController.Request("device", "a".repeat(32))));
        verifyNoInteractions(policies); verify(models, never()).credential(any(), anyString());
    }
}

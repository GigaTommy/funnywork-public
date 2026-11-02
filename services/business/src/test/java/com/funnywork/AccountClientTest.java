package com.funnywork;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AccountClientTest {
    @Test void policiesRemainDistinctAndLegacyDefaultIsDesktop() {
        assertEquals(AccountClient.DESKTOP, AccountClient.from(null));
        assertFalse(AccountClient.from("desktop").mobile());
        assertTrue(AccountClient.from("ios").mobile());
        assertTrue(AccountClient.from("android").mobile());
        assertNotEquals(AccountClient.IOS.identityClient(), AccountClient.ANDROID.identityClient());
        assertThrows(ApiException.class, () -> AccountClient.from("ANDROID"));
    }
    @Test void headerRoutingRecognizesOnlyAllowlistedClients() {
        for (var client : AccountClient.values()) {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"clientid\":\"" + client.identityClient() + "\"}").getBytes(StandardCharsets.UTF_8));
            assertEquals(client.identityClient(), ManagementIdentity.clientId("header." + payload + ".signature"));
        }
        assertEquals(ManagementIdentity.DESKTOP_CLIENT, ManagementIdentity.clientId("malformed"));
    }
}

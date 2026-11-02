package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopModelPublicationTest {
    @Test void evenAnAuthenticatedDeviceCannotExtractTheSharedKey() {
        var releases=mock(DesktopModelReleases.class);
        var actor=new Identity("employee","tenant","USER",Set.of("VIEWER"),Set.of("production"),1);
        when(releases.resolve(actor)).thenReturn(new DesktopModelPublication.Configuration("tenant","private","public","https://api.deepseek.com/v1","deepseek-chat","fixture-key","disabled",2048,3));
        var receipts=mock(DesktopModelReceipts.class);
        var service=new DesktopModelPublication(releases,new ObjectMapper(),Clock.systemUTC(),receipts);
        var error=assertThrows(ApiException.class,()->service.publication(actor,new DesktopModelPublication.Device("a".repeat(32),"A".repeat(43)+"=")));
        assertEquals(410,error.status);assertEquals("MODEL_SECRET_PUBLICATION_DISABLED",error.code);
        assertFalse(error.getMessage().contains("fixture-key"));verifyNoInteractions(receipts);
        assertEquals("public",service.signer(actor).get("publicKey"));
        when(releases.resolve(actor)).thenThrow(ApiException.forbidden());
        assertEquals(403,assertThrows(ApiException.class,()->service.publication(actor,null)).status);
    }
}

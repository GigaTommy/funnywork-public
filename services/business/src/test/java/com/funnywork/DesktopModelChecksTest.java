package com.funnywork;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DesktopModelChecksTest {
    final Identity actor=new Identity("admin","tenant","USER",Set.of("ADMIN"),Set.of("a"),1);
    final DesktopModelReleases.Settings settings=new DesktopModelReleases.Settings(3L,"https://api.deepseek.com/v1","deepseek-chat","disabled",2048,"DEPLOYMENT_DEFAULT");
    DesktopModelReleases service(){var service=mock(DesktopModelReleases.class);when(service.forCheck(actor,settings)).thenReturn(new DesktopModelPublication.Configuration("tenant","private","public",settings.endpoint(),settings.model(),"secret",settings.thinking(),2048,3));return service;}
    @Test void discoveryIsNotReportedAsInferenceAndDoesNotSendPrompt(){var service=service();var check=new DesktopModelChecks(service,new ObjectMapper(),(url,token,body)->{assertEquals("https://api.deepseek.com/v1/models",url);assertEquals("secret",token);assertNull(body);return new DesktopModelChecks.Response(200,"{\"data\":[{\"id\":\"deepseek-chat\"}]}".getBytes(StandardCharsets.UTF_8));});var result=check.check(actor,settings,false);assertEquals("DISCOVERABLE",result.get("outcome"));assertEquals(false,result.get("inferenceVerified"));verify(service).recordCheck(eq(actor),eq(settings),eq("CONNECTION"),eq("DISCOVERABLE"),eq(200),anyLong());}
    @Test void unmeteredInferenceCheckCannotSpendProviderMoney(){var check=new DesktopModelChecks(service(),new ObjectMapper(),(url,token,body)->{fail("No unmetered request may be sent");return null;});var error=assertThrows(ApiException.class,()->check.check(actor,settings,true));assertEquals("METERED_INFERENCE_REQUIRED",error.code);}
    @Test void remoteFailuresAreSanitizedAndDoNotClaimInference(){for(int status:new int[]{401,302,500}){var check=new DesktopModelChecks(service(),new ObjectMapper(),(u,t,b)->new DesktopModelChecks.Response(status,"secret".getBytes()));var result=check.check(actor,settings,false);assertEquals(false,result.get("inferenceVerified"));assertEquals(status,result.get("httpStatus"));}}
    @Test void malformedOversizedAndEmptyResponsesAreRejected(){for(byte[] body:new byte[][]{"not json".getBytes(),new byte[262145],"{}".getBytes()}){var check=new DesktopModelChecks(service(),new ObjectMapper(),(u,t,b)->new DesktopModelChecks.Response(200,body));assertEquals("INVALID_RESPONSE",check.check(actor,settings,false).get("outcome"));}}
    @Test void revocationAfterRemoteCallSuppressesTheResult(){var service=service();doThrow(ApiException.forbidden()).when(service).recordCheck(any(),any(),anyString(),anyString(),anyInt(),anyLong());var check=new DesktopModelChecks(service,new ObjectMapper(),(u,t,b)->new DesktopModelChecks.Response(200,"{}".getBytes()));assertEquals(403,assertThrows(ApiException.class,()->check.check(actor,settings,false)).status);}
}

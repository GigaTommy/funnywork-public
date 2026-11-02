package org.dromara.web.directory;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.dromara.common.core.exception.ServiceException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev") @Tag("prod") @Tag("local")
class HttpDirectoryAdapterTest {
    @Test void authenticationUsesFixedEndpointAndOnlyReturnsStableSubject() throws Exception {
        var source=new DirectoryRegistry.Source("corp","000000","https://id.example/auth","https://id.example/users","FUNNYWORK_DIRECTORY_KEY",Map.of("dept",103L));
        var http=mock(HttpClient.class);HttpResponse<String> response=mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"externalId\":\"employee-1\",\"enabled\":true}");
        var adapter=new HttpDirectoryAdapter(source,new MockEnvironment().withProperty("FUNNYWORK_DIRECTORY_KEY","test-secret"),http);
        assertEquals("employee-1",adapter.authenticate("alice","password"));
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("https://id.example/auth",request.getValue().uri().toString());assertEquals("POST",request.getValue().method());
        assertEquals("Bearer test-secret",request.getValue().headers().firstValue("Authorization").orElseThrow());
        when(response.statusCode()).thenReturn(401);assertNull(adapter.authenticate("alice","wrong"));
        when(response.statusCode()).thenReturn(302);assertThrows(ServiceException.class,()->adapter.authenticate("alice","password"));
        when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"externalId\":\"employee-1\"}");
        assertThrows(ServiceException.class,()->adapter.authenticate("alice","password"));
    }
    @Test void oversizedResponseCancelsTheTransfer(){
        var body=HttpDirectoryAdapter.boundedBody();var subscription=mock(Flow.Subscription.class);body.onSubscribe(subscription);
        body.onNext(List.of(ByteBuffer.allocate(1_048_577)));
        verify(subscription).cancel();assertTrue(body.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void configurationRejectsUnsafeEndpointsAndDuplicateSources(@TempDir Path directory)throws Exception{
        var file=directory.resolve("identity.json");
        String source="{\"id\":\"corp\",\"tenantId\":\"000000\",\"authenticationUrl\":\"https://id.example/auth\",\"usersUrl\":\"https://id.example/users\",\"credentialRef\":\"FUNNYWORK_DIRECTORY_KEY\",\"departments\":{\"dept\":103}}";
        Files.writeString(file,"{\"sources\":["+source+"]}");assertEquals(1,DirectoryRegistry.load(file.toString()).size());
        Files.writeString(file,"{\"sources\":["+source+","+source+"]}");assertThrows(IllegalArgumentException.class,()->DirectoryRegistry.load(file.toString()));
        Files.writeString(file,"{\"sources\":["+source.replace("https://","http://")+"]}");assertThrows(IllegalArgumentException.class,()->DirectoryRegistry.load(file.toString()));
        assertThrows(IllegalArgumentException.class,()->DirectoryRegistry.endpoint("https://user:password@id.example/auth"));
        assertThrows(IllegalArgumentException.class,()->DirectoryRegistry.endpoint("https://id.example/auth?token=secret"));
    }
    @Test void directoryResponseRequiresExplicitStateAndVersion()throws Exception{
        var source=new DirectoryRegistry.Source("corp","000000","https://id.example/auth","https://id.example/users","FUNNYWORK_DIRECTORY_KEY",Map.of("dept",103L));
        var http=mock(HttpClient.class);HttpResponse<String> response=mock(HttpResponse.class);
        doReturn(response).when(http).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));when(response.statusCode()).thenReturn(200);
        var adapter=new HttpDirectoryAdapter(source,new MockEnvironment().withProperty("FUNNYWORK_DIRECTORY_KEY","test-secret"),http);
        when(response.body()).thenReturn("{\"snapshot\":\"s1\",\"nextCursor\":\"\",\"checkpoint\":\"c1\",\"users\":[{\"externalId\":\"1\",\"username\":\"a\",\"displayName\":\"A\",\"departmentId\":\"dept\",\"version\":1,\"enabled\":true}]}");
        assertEquals(1,adapter.users("","").users().size());
        when(response.body()).thenReturn("{\"snapshot\":\"s1\",\"nextCursor\":\"\",\"checkpoint\":\"c1\",\"users\":[{\"externalId\":\"1\",\"username\":\"a\",\"displayName\":\"A\",\"departmentId\":\"dept\"}]}");
        assertThrows(ServiceException.class,()->adapter.users("",""));
    }
}

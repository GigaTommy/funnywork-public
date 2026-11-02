package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:model-profiles;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
@AutoConfigureMockMvc
class ModelProfilesIntegrationTest {
    @Autowired ModelProfiles profiles;@Autowired AuthService auth;@Autowired ControlService control;@Autowired MockMvc http;
    Identity admin,other,owner;String token;
    @BeforeEach void setup(){
        String tenant=AuthService.id();token=AuthService.id();
        auth.create(AuthService.id(),tenant,"USER","Admin",Set.of("ADMIN"),Set.of("sandbox","other"),token);admin=auth.authenticate(token);
        String secret=AuthService.id();auth.create(AuthService.id(),AuthService.id(),"USER","Other",Set.of("ADMIN"),Set.of("sandbox"),secret);other=auth.authenticate(secret);
        secret=AuthService.id();auth.create(AuthService.id(),tenant,"USER","Owner",Set.of("OPERATOR"),Set.of("sandbox"),secret);owner=auth.authenticate(secret);
    }
    ModelProfiles.Profile body(String url,boolean enabled,int version){return new ModelProfiles.Profile("Private model","sandbox","private-model",url,true,enabled,version);}
    @Test void scopedVersionedMaintenanceAndNodeRead(){
        String id=(String)profiles.save(admin,null,body("https://model.internal/v1",true,0)).get("id");
        assertEquals(1,((List<?>)profiles.list(admin,"sandbox","",50).get("items")).size());
        assertEquals(0,((List<?>)profiles.list(other,"sandbox","",50).get("items")).size());
        assertEquals(403,assertThrows(ApiException.class,()->profiles.list(owner,"sandbox","",50)).status);
        assertEquals(404,assertThrows(ApiException.class,()->profiles.save(other,id,body("https://model.internal/v1",true,1))).status);
        assertEquals(409,assertThrows(ApiException.class,()->profiles.save(admin,id,body("https://model.internal/v1",true,0))).status);
        var issued=control.registerNode(admin,new Requests.Node("Model","sandbox",1,"model.read.v1"));
        Identity node=auth.authenticate((String)issued.get("token"));
        assertEquals("private-model",profiles.forNode(node,id).get("model"));
        assertFalse(profiles.forNode(node,id).containsKey("token"));
        var differentZone=control.registerNode(admin,new Requests.Node("Other zone","other",1,"model.read.v1"));
        assertEquals(404,assertThrows(ApiException.class,()->profiles.forNode(auth.authenticate((String)differentZone.get("token")),id)).status);
        var text=control.registerNode(admin,new Requests.Node("Text","sandbox",1,"text.stats.v1"));
        assertEquals(403,assertThrows(ApiException.class,()->profiles.forNode(auth.authenticate((String)text.get("token")),id)).status);
        profiles.save(admin,id,body("https://model.internal/v1",false,1));
        assertEquals(404,assertThrows(ApiException.class,()->profiles.forNode(node,id)).status);
    }
    @Test void historyIsPagedAndRestorePublishesMonotonicVersion(){
        String id=(String)profiles.save(admin,null,body("https://model.internal/v1",true,0)).get("id");
        profiles.save(admin,id,body("https://model.internal/new",false,1));
        var page=profiles.history(admin,id,0,1);assertEquals(2,page.get("currentVersion"));assertEquals(2,page.get("nextCursor"));
        assertEquals(1,((List<?>)profiles.history(admin,id,2,1).get("items")).size());
        assertEquals(3,profiles.restore(admin,id,new ModelProfiles.Restore(2,1)).get("version"));
        var history=(List<Map<String,Object>>)profiles.history(admin,id,0,50).get("items");assertEquals(3,history.size());assertEquals(1,history.get(0).get("restored_from"));assertEquals(true,history.get(0).get("enabled"));assertEquals("https://model.internal/v1",history.get(0).get("base_url"));
        assertEquals(409,assertThrows(ApiException.class,()->profiles.restore(admin,id,new ModelProfiles.Restore(2,1))).status);
    }
    @Test void historyAndRestoreRequireCurrentAndHistoricalScopes(){
        String id=(String)profiles.save(admin,null,body("https://model.internal/v1",true,0)).get("id");
        profiles.save(admin,id,new ModelProfiles.Profile("Moved","other","private-model","https://model.internal/v1",true,true,1));
        String partialToken=AuthService.id();auth.create(AuthService.id(),admin.tenant(),"USER","Partial",Set.of("ADMIN"),Set.of("other"),partialToken);Identity partial=auth.authenticate(partialToken);
        assertEquals(1,((List<?>)profiles.history(partial,id,0,50).get("items")).size());
        assertEquals(403,assertThrows(ApiException.class,()->profiles.restore(partial,id,new ModelProfiles.Restore(2,1))).status);
        assertEquals(404,assertThrows(ApiException.class,()->profiles.history(other,id,0,50)).status);
        assertEquals(403,assertThrows(ApiException.class,()->profiles.history(owner,id,0,50)).status);
        assertEquals(3,profiles.restore(admin,id,new ModelProfiles.Restore(2,1)).get("version"));
        assertEquals(403,assertThrows(ApiException.class,()->profiles.history(partial,id,0,50)).status);
    }
    @Test void migrationCapturesExistingConfigurationWithoutChangingIt(){
        String url="jdbc:h2:mem:profile-migration-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").target("74").load().migrate();
        var database=new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,"sa",""));
        database.update("insert into model_profiles values('existing','tenant','sandbox','Existing','old-model','https://model.internal/v1',true,false,8,12345)");
        org.flywaydb.core.Flyway.configure().dataSource(url,"sa","").load().migrate();
        var row=database.queryForMap("select * from model_profile_history where profile_id='existing'");assertEquals(8,row.get("version"));assertEquals("MIGRATED",row.get("origin"));assertEquals(false,row.get("enabled"));assertNull(row.get("actor_id"));assertEquals(8,database.queryForObject("select version from model_profiles where id='existing'",Integer.class));
    }
    @Test void rejectEmbeddedSecretsAndUnsafeUrls(){
        for(String url:List.of("http://model.internal/v1","https://user:secret@model.internal/v1","https://model.internal/v1?api_key=secret","https://model.internal/v1#secret","https://model.internal:99999/v1"))
            assertEquals(400,assertThrows(ApiException.class,()->profiles.save(admin,null,body(url,true,0))).status);
        assertNotNull(profiles.save(admin,null,body("http://127.0.0.1:8081/v1",true,0)));
        assertEquals(403,assertThrows(ApiException.class,()->profiles.save(admin,null,new ModelProfiles.Profile("bad","forbidden","model","https://model.internal/v1",true,true,0))).status);
    }
    @Test void apiValidatesContractAndRequiresCredentials() throws Exception {
        http.perform(get("/api/v1/model-profiles?zone=sandbox")).andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/model-profiles").header("Authorization","Bearer "+token).contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        http.perform(post("/api/v1/model-profiles").header("Authorization","Bearer "+token).contentType("application/json").content("{\"name\":\"model\",\"zone\":\"sandbox\",\"model\":\"private\",\"baseUrl\":\"https://model.internal/v1\",\"requireToken\":true,\"enabled\":true,\"expectedVersion\":0}" )).andExpect(status().isOk());
    }
}

package com.funnywork;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:enterprise-presets;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false"})
class EnterpriseAgentPresetsTest {
    @Autowired EnterpriseAgentPresets service;@Autowired AuthService auth;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;@Autowired Json json;
    Identity admin,user,other;
    Identity create(String tenant,String role,Set<String> zones){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),zones,token);return auth.authenticate(token);}
    @BeforeEach void setup(){String tenant=AuthService.id();admin=create(tenant,"ADMIN",Set.of("sandbox","north"));user=create(tenant,"OPERATOR",Set.of("sandbox"));other=create(AuthService.id(),"ADMIN",Set.of("sandbox"));}
    EnterpriseAgentPresets.Publish body(int expected,String text,boolean enabled){return new EnterpriseAgentPresets.Publish("sandbox","Reviewer","Review code",text,enabled,expected);}
    @Test void publishingReplacesStoredContentsAndRemovesOldRevision(){
        var first=service.publish(admin,null,body(0,"Review carefully",true));String id=(String)first.get("id");
        service.policy(admin,new EnterpriseAgentPresets.Policy("sandbox",false,id,0));
        assertEquals("enterprise-"+id+"-v1",service.catalog(user).get("defaultPresetId"));
        service.publish(admin,id,body(1,"Review security",true));
        var catalog=service.catalog(user);assertEquals("enterprise-"+id+"-v2",catalog.get("defaultPresetId"));
        var revisions=(List<Map<String,Object>>)catalog.get("items");assertEquals(1,revisions.size());assertEquals("Review security",revisions.get(0).get("persona"));assertEquals(true,revisions.get(0).get("selectable"));
        service.publish(admin,id,body(2,"Review carefully",true));assertEquals(1,((List<?>)service.catalog(user).get("items")).size());
        assertEquals("PRESET_VERSION_CONFLICT",assertThrows(ApiException.class,()->service.publish(admin,id,body(1,"stale",true))).code);
        assertEquals("PRESET_IS_DEFAULT",assertThrows(ApiException.class,()->service.publish(admin,id,body(3,"disabled",false))).code);
    }
    @Test void scopesAndPersonalPolicyAreEnforced(){
        var created=service.publish(admin,null,body(0,"Literal instructions",true));String id=(String)created.get("id");
        assertEquals(false,service.catalog(user).get("allowPersonalPresets"));
        assertEquals(403,assertThrows(ApiException.class,()->service.administration(user)).status);
        assertEquals(404,assertThrows(ApiException.class,()->service.publish(other,id,body(1,"forbidden",true))).status);
        var north=create(admin.tenant(),"OPERATOR",Set.of("north"));assertTrue(((List<?>)service.catalog(north).get("items")).isEmpty());
        service.policy(admin,new EnterpriseAgentPresets.Policy("sandbox",true,id,0));assertEquals(true,service.catalog(user).get("allowPersonalPresets"));
        var both=create(admin.tenant(),"OPERATOR",Set.of("sandbox","north"));assertEquals(false,service.catalog(both).get("allowPersonalPresets"));
        service.policy(admin,new EnterpriseAgentPresets.Policy("north",true,"",0));assertEquals(true,service.catalog(both).get("allowPersonalPresets"));
        assertEquals("PRESET_POLICY_CONFLICT",assertThrows(ApiException.class,()->service.policy(admin,new EnterpriseAgentPresets.Policy("sandbox",false,"",0))).code);
        assertEquals("PRESET_DEFAULT_UNAVAILABLE",assertThrows(ApiException.class,()->service.policy(admin,new EnterpriseAgentPresets.Policy("north",false,id,1))).code);
    }
    @Test void disabledPresetIsNeverNewDefault(){
        String id=(String)service.publish(admin,null,body(0,"Offline persona",false)).get("id");
        assertEquals(false,((List<Map<String,Object>>)service.catalog(user).get("items")).get(0).get("selectable"));
        assertEquals("PRESET_DEFAULT_UNAVAILABLE",assertThrows(ApiException.class,()->service.policy(admin,new EnterpriseAgentPresets.Policy("sandbox",false,id,0))).code);
        assertEquals("INVALID_PERSONA",assertThrows(ApiException.class,()->service.publish(admin,null,body(0,"{{ dangerous }}",true))).code);
        assertEquals(403,assertThrows(ApiException.class,()->service.publish(user,null,body(0,"persona",true))).status);
    }
    private void seed(int count,String persona){
        for(int i=0;i<count;i++)db.update("insert into enterprise_agent_presets(tenant_id,id,zone,version,enabled,updated_at,name,description,persona,created_by) values(?,?,?,1,true,0,?,?,?,?)",admin.tenant(),"seed-"+i,"north","Seed","",persona,admin.id());
    }
    @Test void itemLimitIncludesAllZonesAndUpdatesDoNotConsumeAnotherSlot(){
        seed(511,"Literal");String id=(String)service.publish(admin,null,body(0,"Last slot",true)).get("id");
        assertEquals(512,((List<?>)service.catalog(admin).get("items")).size());
        assertEquals("PRESET_CATALOG_LIMIT",assertThrows(ApiException.class,()->service.publish(admin,null,body(0,"Overflow",true))).code);
        service.publish(admin,id,body(1,"Updated last slot",true));
        assertEquals(512,db.queryForObject("select count(*) from enterprise_agent_presets where tenant_id=?",Integer.class,admin.tenant()));
        service.publish(other,null,body(0,"Other tenant",true));
    }
    @Test void utf8LimitRejectsCreationAndReplacementWithoutChangingStoredContents(){
        String chinese="审".repeat(20000);seed(69,chinese);
        assertTrue(json.write(service.catalog(admin)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<EnterpriseAgentPresets.CATALOG_MAX_BYTES);
        assertEquals("PRESET_CATALOG_LIMIT",assertThrows(ApiException.class,()->service.publish(admin,null,body(0,chinese,true))).code);
        String id=(String)service.publish(admin,null,body(0,"Small",true)).get("id");
        assertEquals("PRESET_CATALOG_LIMIT",assertThrows(ApiException.class,()->service.publish(admin,id,body(1,chinese,true))).code);
        var stored=db.queryForMap("select persona,version from enterprise_agent_presets where tenant_id=? and id=?",admin.tenant(),id);
        assertEquals("Small",stored.get("persona"));assertEquals(1,((Number)stored.get("version")).intValue());
        service.publish(admin,id,body(1,"Still small",true));
    }

}

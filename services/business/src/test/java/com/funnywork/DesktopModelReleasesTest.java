package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DesktopModelReleasesTest {
    @TempDir Path directory;
    JdbcTemplate db;AuthService auth;DesktopModelReleases releases;ObjectMapper mapper=new ObjectMapper();
    Identity admin=new Identity("admin","tenant","USER",Set.of("ADMIN"),Set.of("a","b"),1);
    Identity user=new Identity("user","tenant","USER",Set.of("VIEWER"),Set.of("a"),1);
    static JdbcTemplate database() throws Exception {
        var db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:model-release-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("create table principals(id varchar(64),tenant_id varchar(64),kind varchar(16),enabled boolean,zones text)");
        for(String sql:Files.readString(Path.of("src/main/resources/db/migration/V74__desktop_model_releases.sql")).split(";"))if(!sql.isBlank())db.execute(sql);return db;
    }
    @BeforeEach void setup() throws Exception {
        db=database();auth=mock(AuthService.class);when(auth.current(any())).thenAnswer(call->call.getArgument(0));
        db.update("insert into principals values('user','tenant','USER',true,'[\"a\",\"b\"]')");
        var signer=java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair();byte[] publicKey=signer.getPublic().getEncoded();
        Path file=directory.resolve("model.json");mapper.writeValue(file.toFile(),new DesktopModelPublication.Configuration("tenant",Base64.getEncoder().encodeToString(signer.getPrivate().getEncoded()),Base64.getEncoder().encodeToString(Arrays.copyOfRange(publicKey,publicKey.length-32,publicKey.length)),"https://api.deepseek.com/v1","deepseek-chat","legacy-secret","disabled",2048,3));
        releases=new DesktopModelReleases(file.toString(),auth,mapper,Clock.systemUTC(),db,mock(TenantLocks.class),new MockEnvironment().withProperty("FUNNYWORK_MODEL_PRIMARY","new-secret"));
    }
    DesktopModelReleases.Settings settings(long version){return new DesktopModelReleases.Settings(version,"https://api.deepseek.com/v1","deepseek-reasoner","enabled",4096,"FUNNYWORK_MODEL_PRIMARY");}
    @Test void publishingChangesTheConfigurationActuallyDeliveredAndKeepsSecretsPrivate() throws Exception {
        assertEquals("DEPLOYMENT_FILE",releases.read(admin).get("source"));
        assertEquals(4L,releases.publish(admin,settings(3)).get("version"));
        var effective=releases.resolve(user);assertEquals(4,effective.version());assertEquals("new-secret",effective.apiKey());assertEquals("deepseek-reasoner",effective.model());
        String serialized=mapper.writeValueAsString(releases.read(admin));assertFalse(serialized.contains("new-secret"));assertFalse(serialized.contains("legacy-secret"));assertFalse(serialized.contains("signingKey"));
        assertEquals(2,db.queryForObject("select count(*) from desktop_model_releases",Integer.class));
    }
    @Test void longOutputPublicationIsResolvedWithoutLegacyEightKLimit(){
        var settings=new DesktopModelReleases.Settings(3L,"https://api.deepseek.com/v1","deepseek-flash","disabled",65536,"FUNNYWORK_MODEL_PRIMARY");
        releases.publish(admin,settings);
        assertEquals(65536,releases.resolve(user).maxTokens());
        assertEquals(1000000,ModelDefaults.contextTokens("deepseek-flash"));
        assertEquals(131072,ModelDefaults.contextTokens("deepseek-chat"));
        var invalid=new DesktopModelReleases.Settings(4L,"https://api.deepseek.com/v1","deepseek-flash","disabled",262145,"FUNNYWORK_MODEL_PRIMARY");
        assertEquals("INVALID_MODEL_RELEASE",assertThrows(ApiException.class,()->releases.publish(admin,invalid)).code);
    }
    @Test void rollbackCreatesNewVersionAndCanRestoreTheLegacyBaseline(){
        releases.publish(admin,settings(3));var result=releases.rollback(admin,new DesktopModelReleases.Rollback(4L,3L));assertEquals(5L,result.get("version"));
        assertEquals("deepseek-chat",releases.resolve(user).model());assertEquals(5,releases.resolve(user).version());assertEquals(3L,db.queryForObject("select restored_from from desktop_model_releases where version=5",Long.class));
    }
    @Test void stalePublicationAndRepeatedRollbackCannotOverwriteNewerVersions(){
        releases.publish(admin,settings(3));assertEquals(409,assertThrows(ApiException.class,()->releases.publish(admin,settings(3))).status);
        releases.rollback(admin,new DesktopModelReleases.Rollback(4L,3L));assertEquals(409,assertThrows(ApiException.class,()->releases.rollback(admin,new DesktopModelReleases.Rollback(4L,3L))).status);
    }
    @Test void tenantWideReleaseRequiresFullScopeAndLiveAdmin(){
        var partial=new Identity("partial","tenant","USER",Set.of("ADMIN"),Set.of("a"),1);
        assertEquals(403,assertThrows(ApiException.class,()->releases.publish(partial,settings(3))).status);
        assertEquals(403,assertThrows(ApiException.class,()->releases.read(user)).status);
        when(auth.current(admin)).thenReturn(user);assertEquals(403,assertThrows(ApiException.class,()->releases.publish(admin,settings(3))).status);
        assertEquals(0,db.queryForObject("select count(*) from desktop_model_releases",Integer.class));
    }
    @Test void wrongTenantCannotReadTheDeploymentSourceOrResolveOtherTenantHistory(){
        var other=new Identity("other","other","USER",Set.of("ADMIN"),Set.of("a","b"),1);
        assertEquals(403,assertThrows(ApiException.class,()->releases.read(other)).status);
        assertEquals(403,assertThrows(ApiException.class,()->releases.resolve(other)).status);
        releases.publish(admin,settings(3));assertEquals(404,assertThrows(ApiException.class,()->releases.rollback(other,new DesktopModelReleases.Rollback(4L,4L))).status);
    }
    @Test void missingCredentialAndInvalidEndpointCannotBePublished(){
        var missing=new DesktopModelReleases.Settings(3L,"https://api.deepseek.com","deepseek-chat","disabled",2048,"FUNNYWORK_MODEL_MISSING");
        assertEquals(503,assertThrows(ApiException.class,()->releases.publish(admin,missing)).status);
        var endpoint=new DesktopModelReleases.Settings(3L,"https://untrusted.example","deepseek-chat","disabled",2048,"DEPLOYMENT_DEFAULT");
        assertEquals(400,assertThrows(ApiException.class,()->releases.publish(admin,endpoint)).status);
        assertEquals(0,db.queryForObject("select count(*) from desktop_model_releases",Integer.class));
    }
}

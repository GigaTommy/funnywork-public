package com.funnywork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:model-budgets;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","funnywork.bootstrap-token=","funnywork.scheduling-enabled=false","funnywork.model-inference-origins=https://models.internal"})
class ModelBudgetsIntegrationTest {
    static JdbcTemplate postgres;static String schema;
    @org.springframework.test.context.DynamicPropertySource static void postgres(org.springframework.test.context.DynamicPropertyRegistry registry){
        String url=System.getenv("FUNNYWORK_MODEL_BUDGET_TEST_DB_URL");if(url==null||url.isBlank())return;
        String user=System.getenv("FUNNYWORK_MODEL_BUDGET_TEST_DB_USER"),password=System.getenv("FUNNYWORK_MODEL_BUDGET_TEST_DB_PASSWORD");
        postgres=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(url,user,password));schema="fw_budget_test_"+UUID.randomUUID().toString().replace("-","");postgres.execute("create schema "+schema);
        registry.add("spring.datasource.url",()->url+(url.contains("?")?"&":"?")+"currentSchema="+schema);registry.add("spring.datasource.username",()->user);registry.add("spring.datasource.password",()->password);registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
    }
    @AfterAll static void cleanPostgres(){if(postgres!=null)postgres.execute("drop schema "+schema+" cascade");}
    @Autowired ModelBudgets budgets;@Autowired ModelMiddleware models;@Autowired AuthService auth;
    @Autowired JdbcTemplate db;@Autowired ObjectMapper mapper;@Autowired MockMvc http;
    @Autowired RecoveryGate recovery;@Autowired Json json;@Autowired java.time.Clock clock;@Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    Identity operator,admin,user,other;String model,second,userToken;
    @Autowired ManagementAudit managementAudit;
    @BeforeEach void setup(){
        db.update("delete from model_allowance_credits");db.update("update model_promotion_policy set tenant_id='',mode='DISABLED',issued=0,version=0 where id=1");
        db.update("delete from model_budget_adjustments");db.update("delete from model_budget_charges");db.update("delete from model_user_budgets");db.update("delete from model_budget_prices");db.update("delete from model_budget_days");
        db.update("update model_budget_guard set total_limit=0,daily_limit=0,spent=0,held=0,paused=true,version=0 where id=1");
        if(db.queryForObject("select count(*) from principals where id='bootstrap-admin'",Integer.class)==0)auth.create("bootstrap-admin","operator-tenant","USER","Budget operator",Set.of("ADMIN"),Set.of("sandbox"),"budget-operator-test-token");
        operator=auth.authenticate("budget-operator-test-token");String tenant=AuthService.id();admin=create(tenant,"ADMIN");user=create(tenant,"VIEWER");other=create(AuthService.id(),"ADMIN");
        model=deployment(admin);second=deployment(admin);
    }
    Identity create(String tenant,String role){String token=AuthService.id();auth.create(AuthService.id(),tenant,"USER",role,Set.of(role),Set.of("sandbox"),token);if(role.equals("VIEWER"))userToken=token;return auth.authenticate(token);}
    String deployment(Identity actor){String pool=(String)models.savePool(actor,null,new ModelMiddleware.Pool("Pool","sandbox",8,20,8,10,60000,false,0)).get("id");return (String)models.createDeployment(actor,new ModelMiddleware.Deployment(pool,"Model","deepseek","CHAT_COMPLETIONS","https://models.internal/v1","",1024,256,Map.of("tools",true),true)).get("id");}
    void open(long total,long daily){budgets.saveGlobal(operator,new ModelBudgets.GlobalPolicy(total,daily,false,((Number)budgets.global(operator).get("version")).intValue()));}
    void grant(Identity member,long total,int concurrency,int rpm){budgets.saveUser(admin,member.id(),new ModelBudgets.UserPolicy(total,total,concurrency,rpm,128,System.currentTimeMillis()+86400000,true,0));}
    void price(String deployment){budgets.savePrice(operator,deployment,new ModelBudgets.Price(1000000,2000000,1024,0));}
    byte[] body(){return "{\"model\":\"deepseek\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}],\"max_tokens\":128,\"stream\":true}".getBytes(StandardCharsets.UTF_8);}
    Map<String,Object> admit(Identity member,String deployment){String call=AuthService.id();return models.admit(member,new ModelMiddleware.Ticket(deployment,call,call,"device","session","task","","agent",ModelMiddleware.requestDigest(body()),128));}
    String id(Map<String,Object> ticket){return (String)ticket.get("id");}
    ModelUsage complete(long input,long output){var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":"+input+",\"completion_tokens\":"+output+"}}");usage.event("data: [DONE]");return usage;}
    long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    void promotion(String mode,long total,long daily){budgets.savePromotion(operator,new ModelBudgets.PromotionPolicy(user.tenant(),mode,1000000,5000000,total,daily,5000000,365,1,5,128,((Number)budgets.promotion(operator).get("version")).intValue()));}
    @Test void welcomeIsOnceAcrossReplicasAndModeChangesAndRequiresOperator(){
        assertEquals(0,n(budgets.claim(user),"credited_micros"));
        assertEquals(403,assertThrows(ApiException.class,()->budgets.promotion(admin)).status);
        promotion("WELCOME",10000000,10000000);
        assertEquals(1000000,n(budgets.claim(user),"credited_micros"));
        var replica=new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",60);
        assertEquals(0,n(replica.claim(user),"credited_micros"));
        promotion("DISABLED",10000000,10000000);promotion("WELCOME",10000000,10000000);
        assertEquals(0,n(budgets.claim(user),"credited_micros"));assertEquals(1000000,n(budgets.mine(user),"total_limit"));
        assertEquals(0,n(budgets.claim(other),"credited_micros"));
        grant(admin,0,1,5);assertEquals(0,n(budgets.claim(admin),"credited_micros"));
    }
    @Test void globalIssuanceCapsDoNotResetWhenEditingPolicyAndWelcomeIsAllOrNothing(){
        promotion("WELCOME",1500000,1500000);assertEquals(1000000,n(budgets.claim(user),"credited_micros"));
        Identity another=create(user.tenant(),"VIEWER");assertEquals(0,n(budgets.claim(another),"credited_micros"));
        promotion("WELCOME",2000000,1000000);assertEquals(0,n(budgets.claim(another),"credited_micros"));
        promotion("WELCOME",2000000,2000000);assertEquals(1000000,n(budgets.claim(another),"credited_micros"));assertEquals(2000000,n(budgets.promotion(operator),"issued"));
        assertTrue((Boolean)budgets.global(operator).get("paused"));
    }
    @Test void dailyCreditsRespectShanghaiDayHeldBalanceAndDisabledAccounts(){
        promotion("DAILY",10000000,10000000);grant(user,4800000,1,5);db.update("update model_user_budgets set held=1000000 where owner_id=?",user.id());
        assertEquals(200000,n(budgets.claim(user),"credited_micros"));assertEquals(0,n(budgets.claim(user),"credited_micros"));
        db.update("update model_user_budgets set spent=800000 where owner_id=?",user.id());assertEquals(800000,n(budgets.claim(user),"credited_micros"));
        db.update("update model_user_budgets set spent=1800000 where owner_id=?",user.id());assertEquals(0,n(budgets.claim(user),"credited_micros"));
        var nextDay=java.time.Clock.fixed(clock.instant().plusSeconds(86400),java.time.ZoneOffset.UTC);
        db.update("update model_user_budgets set expires_at=? where owner_id=?",nextDay.millis()+86400000,user.id());
        var replica=new ModelBudgets(db,auth,json,nextDay,recovery,transactions,"bootstrap-admin",60);assertEquals(1000000,n(replica.claim(user),"credited_micros"));
        db.update("update model_user_budgets set enabled=false where owner_id=?",user.id());assertEquals(0,n(replica.claim(user),"credited_micros"));assertFalse((Boolean)budgets.mine(user).get("enabled"));
    }
    @Test void concurrentLoginCannotDuplicateWelcomeCredit()throws Exception{
        promotion("WELCOME",1000000,1000000);var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(4);
        try{var futures=new ArrayList<Future<Long>>();for(int i=0;i<4;i++)futures.add(workers.submit(()->{start.await();return n(budgets.claim(user),"credited_micros");}));start.countDown();long total=0;for(var f:futures)total+=f.get(15,TimeUnit.SECONDS);assertEquals(1000000,total);assertEquals(1,db.queryForObject("select count(*) from model_allowance_credits",Integer.class));}finally{workers.shutdownNow();}
    }
    @Test void topupIsAdditiveAuditedAndIdempotentWithoutResettingPolicy(){
        grant(user,1000000,1,5);db.update("update model_user_budgets set spent=800000,held=100000,enabled=false where owner_id=?",user.id());String key=UUID.randomUUID().toString();
        var body=new ModelBudgets.Topup(2000000,key,1);budgets.topup(admin,user.id(),body);budgets.topup(admin,user.id(),body);
        var value=budgets.mine(user);assertEquals(3000000,n(value,"total_limit"));assertEquals(800000,n(value,"spent"));assertEquals(100000,n(value,"held"));assertEquals(1000000,n(value,"daily_limit"));assertFalse((Boolean)value.get("enabled"));
        assertEquals("TOPUP_ID_CONFLICT",assertThrows(ApiException.class,()->budgets.topup(admin,user.id(),new ModelBudgets.Topup(1,key,1))).code);
        assertEquals("BUDGET_VERSION_CONFLICT",assertThrows(ApiException.class,()->budgets.topup(admin,user.id(),new ModelBudgets.Topup(1,UUID.randomUUID().toString(),1))).code);
        assertEquals(404,assertThrows(ApiException.class,()->budgets.topup(other,user.id(),body)).status);
    }
    @Test void dailyClaimUsesOneTimestampAcrossMidnight(){
        promotion("DAILY",10000000,10000000);
        var start=java.time.Instant.parse("2026-10-08T15:59:59Z");var reads=new java.util.concurrent.atomic.AtomicInteger();
        var moving=new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return start.plusSeconds(2L*reads.getAndIncrement());}};
        var replica=new ModelBudgets(db,auth,json,moving,recovery,transactions,"bootstrap-admin",60);assertEquals(1000000,n(replica.claim(user),"credited_micros"));
        var row=db.queryForMap("select * from model_allowance_credits where owner_id=?",user.id());assertEquals("daily:2026-10-08",row.get("credit_key"));assertEquals(start.toEpochMilli(),n(row,"created_at"));
    }
    @Test void receiptReconcilesLostTopupResponseWithoutAddingMoneyAgain(){
        grant(user,1000000,1,5);String key=UUID.randomUUID().toString();assertEquals("ABSENT",budgets.topupReceipt(admin,user.id(),key).get("state"));
        budgets.topup(admin,user.id(),new ModelBudgets.Topup(1000000,key,1));
        var receipt=budgets.topupReceipt(admin,user.id(),key);assertEquals("POSTED",receipt.get("state"));assertEquals(1000000,n(receipt,"amountMicros"));
        budgets.topup(admin,user.id(),new ModelBudgets.Topup(1000000,key,2));assertEquals(2000000,n(budgets.mine(user),"total_limit"));
        assertEquals(404,assertThrows(ApiException.class,()->budgets.topupReceipt(other,user.id(),key)).status);
        assertEquals(403,assertThrows(ApiException.class,()->budgets.topupReceipt(user,user.id(),key)).status);
    }
    @Test void ownWalletProjectsClaimabilityWithoutLeakingGlobalIssuancePolicy(){
        promotion("WELCOME",1000000,1000000);
        var before=(Map<?,?>)budgets.mine(user).get("promotion");assertEquals("WELCOME",before.get("mode"));assertEquals(true,before.get("can_claim"));assertFalse(before.containsKey("total_limit"));
        var claim=budgets.claim(user);var wallet=(Map<?,?>)claim.get("budget");assertEquals(1000000,n(claim,"credited_micros"));assertEquals(false,((Map<?,?>)wallet.get("promotion")).get("can_claim"));
        assertEquals("DISABLED",((Map<?,?>)budgets.mine(other).get("promotion")).get("mode"));
    }
    @Test void claimHttpValidatesAuthenticationAndTopupAmounts()throws Exception{
        promotion("WELCOME",1000000,1000000);
        http.perform(post("/api/account/inference/budget/claim").header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andExpect(jsonPath("$.credited_micros").value(1000000));
        http.perform(post("/api/account/inference/budget/claim")).andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/model-middleware/budgets/users/"+user.id()+"/topups").header("Authorization","Bearer budget-operator-test-token").contentType("application/json").content("{\"amountMicros\":-1,\"requestId\":\"bad\",\"expectedVersion\":1}")).andExpect(status().isBadRequest());
    }
    @Test void defaultsMissingGrantAndMissingPriceDenyBeforeAcceptingAnyTicket(){
        assertEquals("MODEL_BUDGET_PAUSED",assertThrows(ApiException.class,()->admit(user,model)).code);
        open(10000,10000);assertEquals("MODEL_BUDGET_REQUIRED",assertThrows(ApiException.class,()->admit(user,model)).code);
        grant(user,10000,1,5);assertEquals("MODEL_PRICE_REQUIRED",assertThrows(ApiException.class,()->admit(user,model)).code);
        assertEquals(0,db.queryForObject("select count(*) from model_call_tickets where tenant_id=?",Integer.class,user.tenant()));
        assertEquals(0,db.queryForObject("select count(*) from model_budget_charges",Integer.class));
    }
    @Test void heldFundsQueueNormalWorkUntilSettlementRatherThanReportingExhaustion(){
        open(10000,10000);grant(user,2000,2,20);price(model);
        String first=id(admit(user,model));models.begin(user,first,body());
        String waiting=id(admit(user,model));assertEquals("WAITING",models.readTicket(user,waiting).get("state"));
        assertEquals(1,db.queryForObject("select count(*) from model_budget_charges",Integer.class));
        Identity colleague=create(user.tenant(),"VIEWER");grant(colleague,10000,2,20);
        assertEquals("GRANTED",admit(colleague,model).get("state"));
        models.finish(first,"SUCCEEDED","COMPLETED",complete(10,5),null);
        assertEquals("GRANTED",models.readTicket(user,waiting).get("state"));models.begin(user,waiting,body());
        assertEquals(20,n(budgets.mine(user),"spent"));assertEquals(1280,n(budgets.mine(user),"held"));
    }
    @Test void globalHeldFundsQueueAcrossOwnersAndUnknownCallsRetainTheirMoney(){
        open(2000,2000);grant(user,10000,2,20);price(model);
        String first=id(admit(user,model));models.begin(user,first,body());
        Identity colleague=create(user.tenant(),"VIEWER");grant(colleague,10000,2,20);
        String waiting=id(admit(colleague,model));assertEquals("WAITING",models.readTicket(colleague,waiting).get("state"));
        models.finish(first,"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        assertEquals("WAITING",models.readTicket(colleague,waiting).get("state"));assertEquals(1280,n(budgets.global(operator),"held"));
        models.reconcile(admin,first,"Confirmed upstream stopped");
        assertEquals("CANCELLED",models.readTicket(colleague,waiting).get("state"));
        assertEquals("GLOBAL_MODEL_BUDGET_EXHAUSTED",models.readTicket(colleague,waiting).get("outcome"));
    }
    @Test void aGrantedCallReturnsToWaitingWhenAnotherCallTemporarilyReservesFunds(){
        open(10000,10000);grant(user,2000,2,20);price(model);
        String a=id(admit(user,model)),b=id(admit(user,model));models.begin(user,a,body());
        assertEquals("WAITING",models.readTicket(user,b).get("state"));
        assertEquals(409,assertThrows(ApiException.class,()->models.begin(user,b,body())).status);
        models.finish(a,"SUCCEEDED","COMPLETED",complete(10,5),null);
        assertEquals("GRANTED",models.readTicket(user,b).get("state"));assertDoesNotThrow(()->models.begin(user,b,body()));
    }
    @Test void successfulSettlementUsesFrozenRatesAndIsExactlyOnce(){
        open(10000,10000);grant(user,10000,2,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());
        assertEquals(1280,n(budgets.mine(user),"held"));assertEquals(8720,n(budgets.mine(user),"remaining_micros"));
        budgets.savePrice(operator,model,new ModelBudgets.Price(9000000,9000000,1024,1));
        models.finish(ticket,"SUCCEEDED","COMPLETED",complete(10,5),null);models.finish(ticket,"SUCCEEDED","COMPLETED",complete(100,50),null);
        assertEquals(20,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));assertEquals(20,n(budgets.global(operator),"spent"));
        assertEquals(409,assertThrows(ApiException.class,()->models.begin(user,ticket,body())).status);
    }
    @Test void incompleteAndUnknownCallsNeverBecomeFreeAndReconciliationChargesTheHold(){
        open(10000,10000);grant(user,10000,2,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());
        models.finish(ticket,"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        db.update("update model_budget_charges set day_key='2000-01-01' where ticket_id=?",ticket);
        assertEquals(1280,n(budgets.mine(user),"held"));open(10000,1000);price(second);
        assertEquals("GLOBAL_MODEL_BUDGET_EXHAUSTED",assertThrows(ApiException.class,()->models.begin(user,id(admit(user,second)),body())).code);
        models.reconcile(admin,ticket,"Operator confirmed upstream stopped");assertEquals(1280,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
        open(10000,10000);String missing=id(admit(user,model));models.begin(user,missing,body());models.finish(missing,"SUCCEEDED","COMPLETED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        assertEquals(1280,n(budgets.mine(user),"spent"));assertEquals(1280,n(budgets.mine(user),"held"));
        models.reconcile(admin,missing,"Reviewed incomplete provider usage");assertEquals(2560,n(budgets.mine(user),"spent"));
        assertEquals("ESTIMATED_MAXIMUM",db.queryForObject("select settlement_basis from model_budget_charges where ticket_id=?",String.class,missing));
    }
    @Test void cacheDiscountIsFrozenAndDuplicateSettlementDoesNotChargeTwice(){
        open(10000,10000);grant(user,10000,2,5);
        budgets.savePrice(operator,model,new ModelBudgets.Price(1000000,2000000,1024,0,20000L,"FIXED"));
        String ticket=id(admit(user,model));models.begin(user,ticket,body());
        budgets.savePrice(operator,model,new ModelBudgets.Price(9000000,9000000,1024,1,9000000L,"FIXED"));
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100}}");usage.event("data: [DONE]");
        models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null);models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null);
        assertEquals(138,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
        assertEquals("PROVIDER_USAGE",db.queryForObject("select settlement_basis from model_budget_charges where ticket_id=?",String.class,ticket));
    }
    @Test void fullyCachedZeroCostExecutionStillRecordsProviderUsage(){
        open(10000,10000);grant(user,10000,2,5);
        budgets.savePrice(operator,model,new ModelBudgets.Price(1000000,2000000,1024,0,0L,"FIXED"));
        String ticket=id(admit(user,model));models.begin(user,ticket,body());
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");
        usage.event("data: {\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":0,\"prompt_cache_hit_tokens\":1000}}");usage.event("data: [DONE]");
        models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null);
        assertEquals(0,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
        assertEquals("SUCCEEDED",models.readTicket(user,ticket).get("state"));
        assertEquals("PROVIDER_USAGE",db.queryForObject("select settlement_basis from model_budget_charges where ticket_id=?",String.class,ticket));
    }
    @Test void realAllowanceBoundaryRequiresPersonalFundsEvenWhenGlobalLimitIsRaised(){
        open(100000000,100000000);
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(8000000,8000000,2,20,65536,System.currentTimeMillis()+86400000,true,0));
        db.update("update model_user_budgets set spent=6987560 where owner_id=?",user.id());
        db.update("update model_deployments set max_output_tokens=65536 where id=?",model);
        budgets.savePrice(operator,model,new ModelBudgets.Price(1000000,1000000,1000000,0));
        byte[] request=new String(body(),StandardCharsets.UTF_8).replace("\"max_tokens\":128","\"max_tokens\":65536").getBytes(StandardCharsets.UTF_8);
        String call=AuthService.id();var admission=new ModelMiddleware.Ticket(model,call,call,"device","session","task","","agent",ModelMiddleware.requestDigest(request),65536);
        assertEquals(1012440,n(budgets.mine(user),"remaining_micros"));
        assertEquals(1065536,ModelBudgets.cost(1000000,65536,1000000,1000000));
        assertEquals("USER_MODEL_BUDGET_EXHAUSTED",assertThrows(ApiException.class,()->models.admit(user,admission)).code);
        assertEquals(0,db.queryForObject("select count(*) from model_budget_charges",Integer.class));
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(100000000,100000000,2,20,65536,System.currentTimeMillis()+86400000,true,1));
        assertEquals(6987560,n(budgets.mine(user),"spent"));
        String ticket=id(models.admit(user,admission));models.begin(user,ticket,request);
        assertEquals(1065536,n(budgets.mine(user),"held"));assertEquals(91946904,n(budgets.mine(user),"remaining_micros"));
        models.finish(ticket,"SUCCEEDED","COMPLETED",complete(1000,128),null);
        assertEquals(6988688,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
    }
    @Test void directSupplierExecutionFreezesNightRatesWhileReservingPeakCeiling(){
        open(100000,100000);grant(user,100000,2,5);
        db.update("update model_deployments set model='deepseek-flash',base_url='https://api.deepseek.com' where id=?",model);
        var night=java.time.Clock.fixed(java.time.Instant.parse("2026-10-09T15:10:00Z"),java.time.ZoneOffset.UTC);
        var tariffBudgets=new ModelBudgets(db,auth,json,night,recovery,transactions,"bootstrap-admin",60);
        tariffBudgets.savePrice(operator,model,new ModelBudgets.Price(1,1,1024,0,0L,ModelTariff.DEEPSEEK_FLASH));
        var direct=new ModelMiddleware(db,auth,json,mapper,night,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://api.deepseek.com",tariffBudgets);
        byte[] request=new String(body(),StandardCharsets.UTF_8).replace("\"model\":\"deepseek\"","\"model\":\"deepseek-flash\"").getBytes(StandardCharsets.UTF_8);
        String call=AuthService.id();var t=direct.admit(user,new ModelMiddleware.Ticket(model,call,call,"","night-session","","","agent",ModelMiddleware.requestDigest(request),128));
        direct.begin(user,id(t),request);
        var charge=db.queryForMap("select * from model_budget_charges where ticket_id=?",id(t));
        assertEquals(3072,n(charge,"reserved"));assertEquals(1000000,n(charge,"input_rate"));assertEquals(20000,n(charge,"cached_rate"));assertEquals("OFF_PEAK",charge.get("tariff_period"));
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":900}}");usage.event("data: [DONE]");
        direct.finish(id(t),"SUCCEEDED","COMPLETED",usage,null);
        assertEquals(158,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
    }
    @Test void historicalCorrectionPreviewsAndReturnsOnlyOverchargeExactlyOnce(){
        open(10000,10000);grant(user,10000,2,5);price(model);
        String ticket=id(admit(user,model));models.begin(user,ticket,body());
        var usage=new ModelUsage(mapper,"CHAT_COMPLETIONS");usage.event("data: {\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":10,\"prompt_cache_hit_tokens\":900,\"prompt_cache_miss_tokens\":100}}");usage.event("data: [DONE]");
        models.finish(ticket,"SUCCEEDED","COMPLETED",usage,null);
        db.update("update model_deployments set model='deepseek-flash',base_url='https://api.deepseek.com' where id=?",model);
        db.update("update model_budget_charges set settlement_basis='LEGACY' where ticket_id=?",ticket);
        db.update("update model_call_tickets set started_at=? where id=?",java.time.Instant.parse("2026-10-09T15:10:00Z").toEpochMilli(),ticket);
        assertEquals(1020,n(budgets.correctSupplierCharges(operator,new ModelBudgets.SupplierCorrection("session",true)),"previousMicros"));
        assertEquals(158,n(budgets.correctSupplierCharges(operator,new ModelBudgets.SupplierCorrection("session",true)),"correctedMicros"));
        assertEquals(1020,n(budgets.mine(user),"spent"));
        assertEquals(403,assertThrows(ApiException.class,()->budgets.correctSupplierCharges(admin,new ModelBudgets.SupplierCorrection("session",false))).status);
        assertEquals(862,n(budgets.correctSupplierCharges(operator,new ModelBudgets.SupplierCorrection("session",false)),"returnedMicros"));
        assertEquals(0,n(budgets.correctSupplierCharges(operator,new ModelBudgets.SupplierCorrection("session",false)),"calls"));
        assertEquals(158,n(budgets.mine(user),"spent"));assertEquals(158,n(budgets.global(operator),"spent"));
        assertEquals(1,db.queryForObject("select count(*) from model_budget_adjustments where ticket_id=?",Integer.class,ticket));
        assertEquals(10000,n(budgets.mine(user),"total_limit"));
    }
    @Test void supplierPriceRequiresDirectEndpointAndRejectsInvalidCacheDiscount(){
        assertEquals("INVALID_SUPPLIER_TARIFF",assertThrows(ApiException.class,()->budgets.savePrice(operator,model,new ModelBudgets.Price(2000000,8000000,1024,0,40000L,ModelTariff.DEEPSEEK_FLASH))).code);
        assertEquals("INVALID_CACHED_PRICE",assertThrows(ApiException.class,()->budgets.savePrice(operator,model,new ModelBudgets.Price(1,1,1024,0,2L,"FIXED"))).code);
    }
    @Test void failedConnectionReleasesMoneyButStillCountsAgainstTheMinuteRate(){
        open(10000,10000);grant(user,10000,1,1);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());models.finish(ticket,"FAILED","UPSTREAM_CONNECT_FAILED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        assertEquals(0,n(budgets.mine(user),"spent"));assertEquals(0,n(budgets.mine(user),"held"));
        assertEquals("WAITING",admit(user,model).get("state"));
    }
    @Test void unsentFlashConnectionReleasesHoldWithoutProviderCacheUsage(){
        open(10000,10000);grant(user,10000,1,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());
        db.update("update model_budget_charges set tariff_profile=? where ticket_id=?",ModelTariff.DEEPSEEK_FLASH,ticket);
        models.finish(ticket,"FAILED","UPSTREAM_CONNECT_FAILED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        models.finish(ticket,"FAILED","UPSTREAM_CONNECT_FAILED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        assertEquals(0,n(budgets.mine(user),"held"));assertEquals(0,n(budgets.mine(user),"spent"));
        assertEquals("NO_EXECUTION",db.queryForObject("select settlement_basis from model_budget_charges where ticket_id=?",String.class,ticket));
        assertEquals("GRANTED",admit(user,model).get("state"));
    }
    @Test void historicalUnsentHoldReconcilesAtZeroButUnknownExecutionRemainsCharged(){
        open(10000,10000);grant(user,10000,1,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());
        db.update("update model_budget_charges set tariff_profile=? where ticket_id=?",ModelTariff.DEEPSEEK_FLASH,ticket);
        db.update("update model_call_tickets set state='FAILED',outcome='UPSTREAM_CONNECT_FAILED' where id=?",ticket);
        models.reconcile(admin,ticket,"Persisted connection failed before sending, no provider tokens");
        assertEquals(0,n(budgets.mine(user),"held"));assertEquals(0,n(budgets.mine(user),"spent"));
        String uncertain=id(admit(user,model));models.begin(user,uncertain,body());
        models.finish(uncertain,"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        assertTrue(n(budgets.mine(user),"held")>0);
        models.reconcile(admin,uncertain,"Confirmed stopped; provider usage unavailable");
        assertEquals(1280,n(budgets.mine(user),"spent"));
    }
    @Test void concurrencyIsSharedAcrossPoolsAndDevicesAndUnusedCancellationHasNoCharge(){
        open(10000,10000);grant(user,10000,1,5);price(model);price(second);
        String ticket=id(admit(user,model));models.begin(user,ticket,body());String waiting=id(admit(user,second));
        assertEquals("WAITING",models.readTicket(user,waiting).get("state"));
        models.cancel(user,waiting,false);assertEquals(1,db.queryForObject("select count(*) from model_budget_charges",Integer.class));
        models.cancel(user,ticket,false);models.finish(ticket,"UNKNOWN","DISCONNECTED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);assertEquals(1280,n(budgets.mine(user),"held"));
    }
    @Test void accountLimitsCannotBeResetBySavingPolicyAndExpiryAndOutputLimitsAreEnforced(){
        open(10000,10000);grant(user,1280,1,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());models.finish(ticket,"SUCCEEDED","COMPLETED",complete(1000,128),null);
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(1280,1280,1,5,128,System.currentTimeMillis()+86400000,true,1));
        assertEquals("USER_MODEL_BUDGET_EXHAUSTED",assertThrows(ApiException.class,()->models.begin(user,id(admit(user,model)),body())).code);
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(10000,10000,1,5,64,System.currentTimeMillis()+86400000,true,2));
        assertEquals("USER_OUTPUT_LIMIT",assertThrows(ApiException.class,()->models.begin(user,id(admit(user,model)),body())).code);
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(10000,10000,1,5,128,1,true,3));
        assertEquals("MODEL_BUDGET_EXPIRED",assertThrows(ApiException.class,()->models.begin(user,id(admit(user,model)),body())).code);
    }
    @Test void globalLifetimeLimitSurvivesDayChangesAndPauseStopsActiveCalls(){
        open(1280,10000);grant(user,10000,2,5);price(model);price(second);String first=id(admit(user,model));models.begin(user,first,body());
        assertFalse(models.stopRequested(first));open(1280,10000);budgets.saveGlobal(operator,new ModelBudgets.GlobalPolicy(1280,10000,true,2));assertTrue(models.stopRequested(first));open(1280,10000);
        models.finish(first,"SUCCEEDED","COMPLETED",new ModelUsage(mapper,"CHAT_COMPLETIONS"),null);
        db.update("update model_budget_days set day_key='2000-01-01'");
        var waiting=admit(user,second);assertEquals("WAITING",waiting.get("state"));
        assertEquals("MODEL_PERMIT_NOT_GRANTED",assertThrows(ApiException.class,()->models.begin(user,id(waiting),body())).code);
    }
    @Test void concurrentDifferentTenantsCannotOverReserveTheGlobalBudget()throws Exception{
        open(1280,10000);grant(user,10000,8,20);price(model);
        String foreignModel=deployment(other);budgets.saveUser(other,other.id(),new ModelBudgets.UserPolicy(10000,10000,8,20,128,System.currentTimeMillis()+86400000,true,0));budgets.savePrice(operator,foreignModel,new ModelBudgets.Price(1000000,2000000,1024,0));
        var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://models.internal",new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",60));
        String a=id(admit(user,model)),b=id(admit(other,foreignModel));var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try{var futures=List.of(workers.submit(()->{start.await();return models.begin(user,a,body());}),workers.submit(()->{start.await();return replica.begin(other,b,body());}));start.countDown();int accepted=0,rejected=0;
            for(var future:futures)try{future.get();accepted++;}catch(ExecutionException e){assertInstanceOf(ApiException.class,e.getCause());assertEquals("GLOBAL_MODEL_FUNDS_RESERVED",((ApiException)e.getCause()).code);rejected++;}
            assertEquals(1,accepted);assertEquals(1,rejected);assertEquals(1280,n(budgets.global(operator),"held"));
        }finally{workers.shutdownNow();}
    }
    @Test void costBeyondReservationIsolatesOnlyTheFaultyDeployment(){
        open(10000,10000);grant(user,10000,1,5);price(model);String ticket=id(admit(user,model));models.begin(user,ticket,body());models.finish(ticket,"SUCCEEDED","COMPLETED",complete(2000,128),null);
        assertEquals(false,budgets.global(operator).get("paused"));assertEquals(2256,n(budgets.global(operator),"spent"));
        assertEquals(false,db.queryForObject("select enabled from model_deployments where id=?",Boolean.class,model));
        assertEquals("MODEL_DISABLED",assertThrows(ApiException.class,()->admit(user,model)).code);
        price(second);assertDoesNotThrow(()->models.begin(user,id(admit(user,second)),body()));
        assertEquals(1,db.queryForObject("select count(*) from events where tenant_id=? and event_type='MODEL_BUDGET_ANOMALY'",Integer.class,user.tenant()));
    }
    @Test void outputBeyondTokenCeilingWithinReservedMoneySettlesAndWarnsWithoutBlockingWork(){
        open(10000,10000);grant(user,10000,1,5);price(model);
        String ticket=id(admit(user,model));models.begin(user,ticket,body());models.finish(ticket,"SUCCEEDED","COMPLETED",complete(1,129),null);
        assertEquals(false,budgets.global(operator).get("paused"));assertEquals(259,n(budgets.global(operator),"spent"));assertEquals(0,n(budgets.global(operator),"held"));
        assertEquals(true,db.queryForObject("select enabled from model_deployments where id=?",Boolean.class,model));
        var audit=db.queryForMap("select payload_json from events where tenant_id=? and event_type='MODEL_BUDGET_ANOMALY'",user.tenant());
        assertTrue(audit.get("payload_json").toString().contains("TOKEN_CEILING_EXCEEDED"));
        var entries=(List<?>)managementAudit.page(admin,clock.millis()-60000,clock.millis()+1000,0,10,"MODEL_BUDGET_ANOMALY",ticket).get("items");
        var details=(Map<?,?>)((Map<?,?>)entries.get(0)).get("details");
        assertEquals("TOKEN_CEILING_EXCEEDED",details.get("failureCode"));assertEquals("WARNING",details.get("state"));assertEquals(model,details.get("deploymentId"));
        models.finish(ticket,"SUCCEEDED","COMPLETED",complete(1,129),null);assertEquals(259,n(budgets.global(operator),"spent"));
        assertEquals(1,db.queryForObject("select count(*) from events where tenant_id=? and event_type='MODEL_BUDGET_ANOMALY'",Integer.class,user.tenant()));
        assertDoesNotThrow(()->models.begin(user,id(admit(user,model)),body()));
    }
    @Test void uncomputableProviderCostKeepsItsHoldAndIsolatesOnlyItsDeployment(){
        open(10000,10000);grant(user,10000,2,5);price(model);price(second);
        String ticket=id(admit(user,model));models.begin(user,ticket,body());models.finish(ticket,"SUCCEEDED","COMPLETED",complete(0,Long.MAX_VALUE),null);
        assertEquals(false,budgets.global(operator).get("paused"));assertEquals(0,n(budgets.global(operator),"spent"));assertEquals(1280,n(budgets.global(operator),"held"));
        assertEquals("HELD",db.queryForObject("select state from model_budget_charges where ticket_id=?",String.class,ticket));
        assertEquals(false,db.queryForObject("select enabled from model_deployments where id=?",Boolean.class,model));
        assertDoesNotThrow(()->models.begin(user,id(admit(user,second)),body()));
    }
    @Test void longConversationsAreNotRejectedByMessageCount()throws Exception{
        open(10000,10000);grant(user,10000,8,20);price(model);
        var request=mapper.createObjectNode().put("model","deepseek").put("max_tokens",128).put("stream",true);
        var messages=request.putArray("messages");
        for(int i=0;i<1024;i++)messages.addObject().put("role",i%2==0?"user":"assistant").put("content","ok");
        byte[] bytes=mapper.writeValueAsBytes(request);String call=AuthService.id();
        String ticket=id(models.admit(user,new ModelMiddleware.Ticket(model,call,call,"","","","","agent",ModelMiddleware.requestDigest(bytes),128)));
        assertDoesNotThrow(()->models.begin(user,ticket,bytes));
        models.finish(ticket,"SUCCEEDED","COMPLETED",complete(10,5),null);
    }
    @Test void providerToolsMediaAndOutputOverflowCannotBypassTheCostBoundary()throws Exception{
        open(10000,10000);grant(user,10000,8,20);price(model);
        for(String extra:List.of("\"web_search_options\":{}","\"n\":1.5","\"tools\":[{\"type\":\"web_search\"}]")){
            byte[] bytes=new String(body(),StandardCharsets.UTF_8).replace("\"stream\":true","\"stream\":true,"+extra).getBytes(StandardCharsets.UTF_8);
            String call=AuthService.id();var ticket=models.admit(user,new ModelMiddleware.Ticket(model,call,call,"","","","","agent",ModelMiddleware.requestDigest(bytes),128));
            assertEquals("INVALID_MODEL_REQUEST",assertThrows(ApiException.class,()->models.begin(user,id(ticket),bytes)).code);
        }
        assertEquals(0,db.queryForObject("select count(*) from model_budget_charges",Integer.class));
    }
    @Test void managementScopeAndHttpValidationPreventSelfGrantsOrGlobalBudgetEscalation()throws Exception{
        assertEquals(403,assertThrows(ApiException.class,()->budgets.saveGlobal(admin,new ModelBudgets.GlobalPolicy(10000,10000,false,0))).status);
        assertEquals(403,assertThrows(ApiException.class,()->budgets.saveUser(user,user.id(),new ModelBudgets.UserPolicy(10000,10000,1,5,128,Long.MAX_VALUE,true,0))).status);
        assertEquals(404,assertThrows(ApiException.class,()->budgets.readUser(other,user.id())).status);
        open(10000,10000);grant(user,10000,1,5);assertEquals(409,assertThrows(ApiException.class,()->grant(user,10000,1,5)).status);
        http.perform(get("/api/account/inference/budget").header("Authorization","Bearer "+userToken)).andExpect(status().isOk()).andExpect(jsonPath("$.remaining_micros").value(10000));
        http.perform(put("/api/v1/model-middleware/budgets/users/"+user.id()).header("Authorization","Bearer "+userToken).contentType("application/json").content(mapper.writeValueAsString(new ModelBudgets.UserPolicy(-1,1,1,1,1,1,true,0)))).andExpect(status().isBadRequest());
    }
    @Test void onlyThePlatformOperatorCanApproveOrLowerPricingAcrossTenants(){
        assertEquals(403,assertThrows(ApiException.class,()->budgets.savePrice(admin,model,new ModelBudgets.Price(1,1,1024,0))).status);
        price(model);assertEquals(operator.id(),budgets.price(admin,model).get("approved_by"));
        assertEquals(403,assertThrows(ApiException.class,()->budgets.savePrice(admin,model,new ModelBudgets.Price(1,1,1024,1))).status);
        assertEquals(1000000L,n(budgets.price(admin,model),"input_rate"));
        String foreign=deployment(other);budgets.savePrice(operator,foreign,new ModelBudgets.Price(1000000,2000000,1024,0));
        assertEquals(404,assertThrows(ApiException.class,()->budgets.price(admin,foreign)).status);
        assertTrue(((List<Map<String,Object>>)budgets.pricingDeployments(operator,"",100).get("items")).stream().anyMatch(row->foreign.equals(row.get("id"))));
        assertFalse(((List<Map<String,Object>>)budgets.pricingDeployments(admin,"",100).get("items")).stream().anyMatch(row->foreign.equals(row.get("id"))));
        assertEquals(false,budgets.members(admin,"",50).get("canManageGlobal"));assertEquals(false,budgets.members(admin,"",50).get("canManagePricing"));
        assertEquals(true,budgets.members(operator,"",50).get("canManagePricing"));
    }
    @Test void legacyTenantPricingCannotBeUsedUntilAnOperatorApprovesIt(){
        open(10000,10000);grant(user,10000,1,5);
        db.update("insert into model_budget_prices(tenant_id,deployment_id,input_rate,output_rate,input_ceiling,version,cached_rate) values(?,?,1,1,1024,1,1)",user.tenant(),model);
        assertEquals(false,budgets.price(admin,model).get("configured"));
        assertEquals("MODEL_PRICE_REQUIRED",assertThrows(ApiException.class,()->admit(user,model)).code);
        budgets.savePrice(operator,model,new ModelBudgets.Price(1000000,2000000,1024,1));
        assertEquals("GRANTED",admit(user,model).get("state"));
    }
    @Test void unfundedAccountsCannotTakeSlotsFromFundedMembers(){
        open(10000,10000);price(model);grant(user,10000,1,5);
        Identity a=create(user.tenant(),"VIEWER"),b=create(user.tenant(),"VIEWER");
        for(Identity unfunded:List.of(a,b))assertEquals("MODEL_BUDGET_REQUIRED",assertThrows(ApiException.class,()->admit(unfunded,model)).code);
        assertEquals("GRANTED",admit(user,model).get("state"));
        assertEquals(1,db.queryForObject("select count(*) from model_call_tickets where tenant_id=?",Integer.class,user.tenant()));
    }
    @Test void cancellingAndSwitchingPoolsCannotResetTheDurableAdmissionRate(){
        open(10000,10000);price(model);price(second);grant(user,10000,1,5);
        var limited=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://models.internal",new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",3));
        for(int i=0;i<3;i++){String call=AuthService.id();var request=new ModelMiddleware.Ticket(i%2==0?model:second,call,call,"","","","","agent",ModelMiddleware.requestDigest(body()),128);
            var ticket=limited.admit(user,request);assertEquals(id(ticket),id(limited.admit(user,request)));limited.cancel(user,id(ticket),false);}
        String call=AuthService.id();var request=new ModelMiddleware.Ticket(second,call,call,"","","","","agent",ModelMiddleware.requestDigest(body()),128);
        assertEquals("USER_MODEL_ADMISSION_RATE_LIMIT",assertThrows(ApiException.class,()->limited.admit(user,request)).code);
        assertEquals(3,db.queryForObject("select count(*) from model_call_tickets where tenant_id=?",Integer.class,user.tenant()));
        db.update("update model_call_tickets set created_at=? where tenant_id=?",clock.millis()-61000,user.tenant());assertEquals("GRANTED",limited.admit(user,request).get("state"));
    }
    @Test void queuedTicketIsNotGrantedAfterItsAllowanceIsExhausted(){
        open(10000,10000);price(model);grant(user,10000,1,5);grant(admin,10000,1,5);
        String pool=db.queryForObject("select pool_id from model_deployments where id=?",String.class,model);
        models.savePool(admin,pool,new ModelMiddleware.Pool("One slot","sandbox",1,20,1,10,60000,false,1));
        var running=admit(user,model);var queued=admit(admin,model);assertEquals("WAITING",queued.get("state"));
        budgets.saveUser(admin,admin.id(),new ModelBudgets.UserPolicy(0,0,1,5,128,clock.millis()+86400000,true,1));models.cancel(user,id(running),false);
        var denied=models.readTicket(admin,id(queued));assertEquals("CANCELLED",denied.get("state"));assertEquals("USER_MODEL_BUDGET_EXHAUSTED",denied.get("outcome"));
        assertEquals(0,db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and state='GRANTED'",Integer.class,user.tenant()));
    }
    @Test void disablingAnAllowanceReleasesUnusedPermitsWithoutCharging(){
        open(10000,10000);price(model);grant(user,10000,1,5);var ticket=admit(user,model);
        budgets.saveUser(admin,user.id(),new ModelBudgets.UserPolicy(10000,10000,1,5,128,clock.millis()+86400000,false,1));
        assertEquals("CANCELLED",models.readTicket(user,id(ticket)).get("state"));assertEquals(0,n(budgets.mine(user),"held"));
        assertEquals("MODEL_BUDGET_EXPIRED",assertThrows(ApiException.class,()->admit(user,model)).code);
    }
    @Test void simultaneousCrossPoolAdmissionsShareRateAndPersonalPermitLimits()throws Exception{
        open(10000,10000);price(model);price(second);grant(user,10000,1,20);
        var first=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://models.internal",new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",3));
        var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://models.internal",new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",3));
        var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(6);
        try{var futures=new ArrayList<Future<Map<String,Object>>>();for(int i=0;i<6;i++){final int index=i;futures.add(workers.submit(()->{start.await();String call=AuthService.id();return (index%2==0?first:replica).admit(user,new ModelMiddleware.Ticket(index%2==0?model:second,call,call,"","","","","agent",ModelMiddleware.requestDigest(body()),128));}));}start.countDown();
            int accepted=0,rejected=0;for(var future:futures)try{future.get(10,TimeUnit.SECONDS);accepted++;}catch(ExecutionException denied){assertInstanceOf(ApiException.class,denied.getCause());assertEquals("USER_MODEL_ADMISSION_RATE_LIMIT",((ApiException)denied.getCause()).code);rejected++;}
            assertEquals(3,accepted);assertEquals(3,rejected);assertEquals(1,db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and state='GRANTED'",Integer.class,user.tenant()));
        }finally{workers.shutdownNow();}
    }
    @Test void simultaneousExecutionsForOneOwnerDoNotInvertPrincipalAndBudgetLocks()throws Exception{
        open(10000,10000);price(model);price(second);grant(user,10000,2,20);String a=id(admit(user,model)),b=id(admit(user,second));
        var replica=new ModelMiddleware(db,auth,json,mapper,clock,transactions,new org.springframework.mock.env.MockEnvironment(),recovery,"https://models.internal",new ModelBudgets(db,auth,json,clock,recovery,transactions,"bootstrap-admin",60));
        var start=new CountDownLatch(1);var workers=Executors.newFixedThreadPool(2);
        try{var futures=List.of(workers.submit(()->{start.await();return models.begin(user,a,body());}),workers.submit(()->{start.await();return replica.begin(user,b,body());}));start.countDown();for(var future:futures)future.get(10,TimeUnit.SECONDS);
            assertEquals(2560,n(budgets.mine(user),"held"));assertEquals(2,db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and state='RUNNING'",Integer.class,user.tenant()));
        }finally{workers.shutdownNow();}
    }
}

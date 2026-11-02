package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.time.ZoneId;
import java.math.BigInteger;
import java.util.*;

/** All starts and settlements share a short database lock across tenants, pools and replicas. */
@Service
public class ModelBudgets {
    public record GlobalPolicy(@Min(0) @Max(1000000000000L) long totalMicros,
        @Min(0) @Max(1000000000000L) long dailyMicros, boolean paused, @Min(0) int expectedVersion) {}
    public record UserPolicy(@Min(0) @Max(1000000000000L) long totalMicros,
        @Min(0) @Max(1000000000000L) long dailyMicros,
        @Min(1) @Max(32) int concurrency, @Min(1) @Max(600) int callsPerMinute,
        @Min(1) @Max(262144) int maxOutputTokens, @Min(1) long expiresAt,
        boolean enabled, @Min(0) int expectedVersion) {}
    public record Price(@Min(1) @Max(1000000000000L) long inputMicrosPerMillion,
        @Min(1) @Max(1000000000000L) long outputMicrosPerMillion,
        @Min(128) @Max(2000000) int inputCeilingTokens, @Min(0) int expectedVersion,
        @Min(0) @Max(1000000000000L) Long cachedMicrosPerMillion,
        @Pattern(regexp="FIXED|DEEPSEEK_FLASH_CNY_20260910") String tariffProfile) {
        public Price(long input,long output,int ceiling,int version) { this(input,output,ceiling,version,null,null); }
    }
    private final JdbcTemplate db;
    private final AuthService auth;
    private final Json json;
    private final Clock clock;
    private final RecoveryGate recovery;
    private final TransactionTemplate tx;
    private final String operator;
    private final int admissionLimit;
    private static final ZoneId DAY_ZONE=ZoneId.of("Asia/Shanghai");
    public ModelBudgets(JdbcTemplate db,AuthService auth,Json json,Clock clock,RecoveryGate recovery,
                        PlatformTransactionManager transactions,
                        @Value("${funnywork.model-budget-operator:bootstrap-admin}") String operator,
                        @Value("${funnywork.model-admission-calls-per-minute:60}") int admissionLimit) {
        if(admissionLimit<1||admissionLimit>10000)throw new IllegalArgumentException("Model admission rate must be 1..10000");
        this.db=db;this.auth=auth;this.json=json;this.clock=clock;this.recovery=recovery;
        this.tx=new TransactionTemplate(transactions);this.operator=operator;this.admissionLimit=admissionLimit;
    }
    private long n(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private String day(){return clock.instant().atZone(DAY_ZONE).toLocalDate().toString();}
    private String scope(String tenant,String owner){return "user:"+tenant+":"+owner;}
    private Map<String,Object> lock(){recovery.lockTransaction(false);return db.queryForMap("select * from model_budget_guard where id=1 for update");}
    private Identity user(Identity actor){Identity live=auth.current(actor);if(!"USER".equals(live.kind()))throw ApiException.forbidden();return live;}
    private Identity operator(Identity actor){Identity live=user(actor);live.requireUser("ADMIN");if(!operator.equals(live.id()))throw ApiException.forbidden();return live;}
    private Identity memberAdmin(Identity actor,String owner){
        Identity live=user(actor);live.requireUser("ADMIN");
        var rows=db.queryForList("select zones from principals where tenant_id=? and id=? and kind='USER'",live.tenant(),owner);
        if(rows.isEmpty())throw ApiException.missing();
        Set<String> zones=json.strings((String)rows.get(0).get("zones"));
        if(zones.isEmpty()||!live.zones().containsAll(zones))throw ApiException.forbidden();return live;
    }
    private Identity deploymentAdmin(Identity actor,String id){
        Identity live=user(actor);live.requireUser("ADMIN");
        var rows=db.queryForList("select p.zone from model_deployments d join model_capacity_pools p on p.id=d.pool_id and p.tenant_id=d.tenant_id where d.tenant_id=? and d.id=?",live.tenant(),id);
        if(rows.isEmpty())throw ApiException.missing();live.requireZone((String)rows.get(0).get("zone"));return live;
    }
    private long daily(String scope,String day){var rows=db.queryForList("select spent from model_budget_days where scope_id=? and day_key=?",scope,day);return rows.isEmpty()?0:n(rows.get(0),"spent");}
    private Map<String,Object> overview(Map<String,Object> row,String scope){var value=new LinkedHashMap<>(row);value.put("daily_spent",daily(scope,day()));value.put("day",day());value.put("currency","CNY");value.put("micros_per_cny",1000000);return value;}
    public Map<String,Object> global(Identity actor){operator(actor);return tx.execute(status->{return overview(lock(),"global");});}
    public Map<String,Object> saveGlobal(Identity actor,GlobalPolicy body){return tx.execute(status->{lock();Identity live=operator(actor);
        if(body.expectedVersion()==Integer.MAX_VALUE||db.update("update model_budget_guard set total_limit=?,daily_limit=?,paused=?,version=version+1 where id=1 and version=?",body.totalMicros(),body.dailyMicros(),body.paused(),body.expectedVersion())!=1)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        auth.audit(live,"MODEL_GLOBAL_BUDGET_SAVED",Map.of("totalMicros",body.totalMicros(),"dailyMicros",body.dailyMicros(),"paused",body.paused()));return overview(db.queryForMap("select * from model_budget_guard where id=1"),"global");
    });}
    public Map<String,Object> mine(Identity actor){Identity live=user(actor);return tx.execute(status->{lock();return wallet(live);});}
    private Map<String,Object> wallet(Identity live){
        var result=new LinkedHashMap<>(account(live.tenant(),live.id()));var policy=db.queryForMap("select * from model_promotion_policy where id=1");
        String mode=live.tenant().equals(policy.get("tenant_id"))?(String)policy.get("mode"):"DISABLED";
        String key="WELCOME".equals(mode)?"welcome":"daily:"+day();
        var credits=db.queryForList("select amount from model_allowance_credits where tenant_id=? and owner_id=? and credit_key=?",live.tenant(),live.id(),key);
        long claimed=credits.isEmpty()?0:n(credits.get(0),"amount");boolean configured=Boolean.TRUE.equals(result.get("configured"));
        boolean eligible=!live.zones().isEmpty()&&("WELCOME".equals(mode)?!configured&&credits.isEmpty():"DAILY".equals(mode)&&(!configured||Boolean.TRUE.equals(result.get("enabled"))&&n(result,"expires_at")>clock.millis()));
        long balance=configured?Math.max(0,n(result,"total_limit")-n(result,"spent")):0;
        long available=eligible?Math.max(0,Math.min(Math.min(n(policy,"grant_micros")-claimed,n(policy,"balance_cap")-balance),Math.min(n(policy,"total_limit")-n(policy,"issued"),n(policy,"daily_limit")-issuedToday()))):0;
        if("WELCOME".equals(mode)&&available<n(policy,"grant_micros"))available=0;
        result.put("promotion",Map.of("mode",mode,"grant_micros","DISABLED".equals(mode)?0:n(policy,"grant_micros"),"balance_cap_micros","DISABLED".equals(mode)?0:n(policy,"balance_cap"),"claimed_micros",claimed,"available_micros",available,"can_claim",available>0));
        return result;
    }
    public Map<String,Object> members(Identity actor,String after,int limit){
        Identity live=user(actor);live.requireUser("ADMIN");
        if(after.length()>64||limit<1||limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid member page");
        var rows=db.queryForList("select id,display_name,zones,enabled from principals where tenant_id=? and kind='USER' and id>? order by id limit ?",live.tenant(),after,limit+1);
        var result=new LinkedHashMap<String,Object>();boolean more=rows.size()>limit;if(more)rows=rows.subList(0,limit);
        var items=new ArrayList<Map<String,Object>>();for(var row:rows){Set<String> zones=json.strings((String)row.get("zones"));if(!zones.isEmpty()&&live.zones().containsAll(zones)){row.remove("zones");items.add(row);}}
        result.put("items",items);result.put("canManageGlobal",operator.equals(live.id()));result.put("canManagePricing",operator.equals(live.id()));if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    public Map<String,Object> readUser(Identity actor,String owner){Identity live=memberAdmin(actor,owner);return tx.execute(status->{lock();return account(live.tenant(),owner);});}
    private Map<String,Object> account(String tenant,String owner){
        var rows=db.queryForList("select * from model_user_budgets where tenant_id=? and owner_id=?",tenant,owner);
        if(rows.isEmpty())return Map.of("configured",false,"enabled",false,"remaining_micros",0,"currency","CNY","micros_per_cny",1000000);
        var result=overview(rows.get(0),scope(tenant,owner));result.put("configured",true);result.put("remaining_micros",Math.max(0,n(rows.get(0),"total_limit")-n(rows.get(0),"spent")-n(rows.get(0),"held")));return result;
    }
    public Map<String,Object> saveUser(Identity actor,String owner,UserPolicy body){return tx.execute(status->{lock();Identity live=memberAdmin(actor,owner);
        var rows=db.queryForList("select * from model_user_budgets where tenant_id=? and owner_id=?",live.tenant(),owner);
        if(body.expectedVersion()==Integer.MAX_VALUE)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        if(rows.isEmpty()){
            if(body.expectedVersion()!=0)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
            db.update("insert into model_user_budgets(tenant_id,owner_id,total_limit,daily_limit,concurrency_limit,calls_per_minute,max_output_tokens,expires_at,enabled,version) values(?,?,?,?,?,?,?,?,?,1)",live.tenant(),owner,body.totalMicros(),body.dailyMicros(),body.concurrency(),body.callsPerMinute(),body.maxOutputTokens(),body.expiresAt(),body.enabled());
        }else if(db.update("update model_user_budgets set total_limit=?,daily_limit=?,concurrency_limit=?,calls_per_minute=?,max_output_tokens=?,expires_at=?,enabled=?,version=version+1 where tenant_id=? and owner_id=? and version=?",body.totalMicros(),body.dailyMicros(),body.concurrency(),body.callsPerMinute(),body.maxOutputTokens(),body.expiresAt(),body.enabled(),live.tenant(),owner,body.expectedVersion())!=1)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        auth.audit(live,"MODEL_USER_BUDGET_SAVED",Map.of("owner",owner,"totalMicros",body.totalMicros(),"dailyMicros",body.dailyMicros(),"enabled",body.enabled()));return account(live.tenant(),owner);
    });}
    public Map<String,Object> pricingDeployments(Identity actor,String after,int limit){
        Identity live=user(actor);live.requireUser("ADMIN");
        if(after.length()>64||limit<1||limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid deployment page");
        var args=new ArrayList<Object>();String sql="select d.id,d.tenant_id,d.name,d.model,d.context_tokens from model_deployments d join model_capacity_pools p on p.tenant_id=d.tenant_id and p.id=d.pool_id where d.id>?";args.add(after);
        if(!operator.equals(live.id())){if(live.zones().isEmpty())return Map.of("items",List.of());sql+=" and d.tenant_id=? and p.zone in ("+String.join(",",Collections.nCopies(live.zones().size(),"?"))+")";args.add(live.tenant());args.addAll(live.zones());}
        args.add(limit+1);var rows=db.queryForList(sql+" order by d.id limit ?",args.toArray());var result=new LinkedHashMap<String,Object>();if(rows.size()>limit){rows=rows.subList(0,limit);result.put("nextCursor",rows.get(rows.size()-1).get("id"));}result.put("items",rows);return result;
    }
    private Map<String,Object> priceTarget(Identity actor,String deployment){
        if(operator.equals(actor.id()))operator(actor);else deploymentAdmin(actor,deployment);
        var rows=db.queryForList("select tenant_id,context_tokens,model,base_url from model_deployments where id=?",deployment);if(rows.isEmpty())throw ApiException.missing();return rows.get(0);
    }
    private Map<String,Object> priceRecord(String tenant,String deployment){
        var rows=db.queryForList("select * from model_budget_prices where tenant_id=? and deployment_id=?",tenant,deployment);if(rows.isEmpty())return Map.of("configured",false);
        var result=new LinkedHashMap<>(rows.get(0));result.put("configured",result.get("approved_by")!=null);return result;
    }
    public Map<String,Object> price(Identity actor,String deployment){var target=priceTarget(user(actor),deployment);return priceRecord((String)target.get("tenant_id"),deployment);}
    public Map<String,Object> savePrice(Identity actor,String deployment,Price body){return tx.execute(status->{lock();Identity live=operator(actor);var target=priceTarget(live,deployment);String tenant=(String)target.get("tenant_id");int context=(int)n(target,"context_tokens");
        if(body.inputCeilingTokens()<context)throw new ApiException(400,"INVALID_INPUT_CEILING","Billing ceiling must cover the deployment context");
        String profile=body.tariffProfile()==null?(ModelTariff.directFlash((String)target.get("model"),(String)target.get("base_url"))?ModelTariff.DEEPSEEK_FLASH:"FIXED"):body.tariffProfile();
        long input=body.inputMicrosPerMillion(),output=body.outputMicrosPerMillion(),cached=body.cachedMicrosPerMillion()==null?input:body.cachedMicrosPerMillion();
        if(ModelTariff.DEEPSEEK_FLASH.equals(profile)){
            if(!ModelTariff.directFlash((String)target.get("model"),(String)target.get("base_url")))throw new ApiException(400,"INVALID_SUPPLIER_TARIFF","Tariff requires direct DeepSeek Flash");
            ModelTariff.flash(clock.millis());input=2000000;cached=40000;output=8000000;
        }
        if(cached<0||cached>input)throw new ApiException(400,"INVALID_CACHED_PRICE","Cached price must not exceed input price");
        var rows=db.queryForList("select version from model_budget_prices where tenant_id=? and deployment_id=?",tenant,deployment);
        if(body.expectedVersion()==Integer.MAX_VALUE)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        if(rows.isEmpty()){
            if(body.expectedVersion()!=0)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
            db.update("insert into model_budget_prices(tenant_id,deployment_id,input_rate,output_rate,input_ceiling,version,approved_by,cached_rate,tariff_profile) values(?,?,?,?,?,1,?,?,?)",tenant,deployment,input,output,body.inputCeilingTokens(),live.id(),cached,profile);
        }else if(db.update("update model_budget_prices set input_rate=?,output_rate=?,input_ceiling=?,approved_by=?,cached_rate=?,tariff_profile=?,version=version+1 where tenant_id=? and deployment_id=? and version=?",input,output,body.inputCeilingTokens(),live.id(),cached,profile,tenant,deployment,body.expectedVersion())!=1)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        auth.audit(live,"MODEL_BUDGET_PRICE_SAVED",Map.of("deployment",deployment,"tenant",tenant,"inputRate",input,"cachedRate",cached,"outputRate",output,"tariffProfile",profile,"inputCeiling",body.inputCeilingTokens()));return priceRecord(tenant,deployment);
    });}
    public record PromotionPolicy(@NotBlank @Size(max=64) String tenantId,
        @Pattern(regexp="DISABLED|WELCOME|DAILY") @NotNull String mode,
        @Min(1) @Max(1000000000000L) long grantMicros, @Min(1) @Max(1000000000000L) long balanceCapMicros,
        @Min(0) @Max(1000000000000L) long totalMicros, @Min(0) @Max(1000000000000L) long dailyMicros,
        @Min(1) @Max(1000000000000L) long userDailyMicros, @Min(1) @Max(3650) int validityDays,
        @Min(1) @Max(32) int concurrency, @Min(1) @Max(600) int callsPerMinute,
        @Min(1) @Max(262144) int maxOutputTokens, @Min(0) int expectedVersion) {}
    public record Topup(@Min(1) @Max(1000000000000L) long amountMicros,
        @NotNull @Pattern(regexp="[A-Za-z0-9_-]{16,64}") String requestId, @Min(1) int expectedVersion) {}
    public Map<String,Object> promotion(Identity actor){operator(actor);return tx.execute(status->{lock();return promotionState();});}
    private long issuedToday(){return issuedToday(clock.millis());}
    private long issuedToday(long now){long midnight=java.time.Instant.ofEpochMilli(now).atZone(DAY_ZONE).toLocalDate().atStartOfDay(DAY_ZONE).toInstant().toEpochMilli();
        return db.queryForObject("select coalesce(sum(amount),0) from model_allowance_credits where kind in ('WELCOME','DAILY') and created_at>=?",Long.class,midnight);}
    private Map<String,Object> promotionState(){var result=new LinkedHashMap<>(db.queryForMap("select * from model_promotion_policy where id=1"));result.put("issued_today",issuedToday());return result;}
    public Map<String,Object> savePromotion(Identity actor,PromotionPolicy body){return tx.execute(status->{lock();Identity live=operator(actor);
        if(body.grantMicros()>body.balanceCapMicros())throw new ApiException(400,"INVALID_PROMOTION","Grant exceeds balance cap");
        if(body.expectedVersion()==Integer.MAX_VALUE||db.update("update model_promotion_policy set tenant_id=?,mode=?,grant_micros=?,balance_cap=?,total_limit=?,daily_limit=?,user_daily_limit=?,validity_days=?,concurrency_limit=?,calls_per_minute=?,max_output_tokens=?,version=version+1 where id=1 and version=?",body.tenantId(),body.mode(),body.grantMicros(),body.balanceCapMicros(),body.totalMicros(),body.dailyMicros(),body.userDailyMicros(),body.validityDays(),body.concurrency(),body.callsPerMinute(),body.maxOutputTokens(),body.expectedVersion())!=1)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        auth.audit(live,"MODEL_PROMOTION_SAVED",Map.of("tenant",body.tenantId(),"mode",body.mode(),"totalMicros",body.totalMicros(),"dailyMicros",body.dailyMicros()));return promotionState();
    });}
    /** Called after successful login; also exposed as an authenticated, idempotent claim. */
    public Map<String,Object> claim(Identity actor){return tx.execute(status->{lock();Identity live=user(actor);long now=clock.millis();
        var policy=db.queryForMap("select * from model_promotion_policy where id=1");String mode=(String)policy.get("mode");long amount=0;
        if(!"DISABLED".equals(mode)&&live.tenant().equals(policy.get("tenant_id"))&&!live.zones().isEmpty()){
            String key="WELCOME".equals(mode)?"welcome":"daily:"+java.time.Instant.ofEpochMilli(now).atZone(DAY_ZONE).toLocalDate();
            var credits=db.queryForList("select amount from model_allowance_credits where tenant_id=? and owner_id=? and credit_key=?",live.tenant(),live.id(),key);
            var accounts=db.queryForList("select * from model_user_budgets where tenant_id=? and owner_id=?",live.tenant(),live.id());
            boolean welcome="WELCOME".equals(mode);
            // A configured wallet is not a new user; disabled/expired accounts need manual review.
            boolean usable=accounts.isEmpty()||(!welcome&&Boolean.TRUE.equals(accounts.get(0).get("enabled"))&&n(accounts.get(0),"expires_at")>now);
            if(usable&&(!welcome||credits.isEmpty())){
                long already=credits.isEmpty()?0:n(credits.get(0),"amount");
                long balance=accounts.isEmpty()?0:Math.max(0,n(accounts.get(0),"total_limit")-n(accounts.get(0),"spent")); // Includes held money.
                amount=Math.max(0,Math.min(Math.min(n(policy,"grant_micros")-already,n(policy,"balance_cap")-balance),Math.min(n(policy,"total_limit")-n(policy,"issued"),n(policy,"daily_limit")-issuedToday(now))));
                if(welcome&&amount<n(policy,"grant_micros"))amount=0;
                if(amount>0){
                    if(accounts.isEmpty())db.update("insert into model_user_budgets(tenant_id,owner_id,total_limit,daily_limit,concurrency_limit,calls_per_minute,max_output_tokens,expires_at,enabled,version) values(?,?,?,?,?,?,?,?,true,1)",live.tenant(),live.id(),amount,policy.get("user_daily_limit"),policy.get("concurrency_limit"),policy.get("calls_per_minute"),policy.get("max_output_tokens"),now+n(policy,"validity_days")*86400000L);
                    else {if(n(accounts.get(0),"total_limit")>1000000000000L-amount)throw new ApiException(409,"BUDGET_LIMIT_OVERFLOW","Lifetime budget too large");db.update("update model_user_budgets set total_limit=total_limit+?,version=version+1 where tenant_id=? and owner_id=?",amount,live.tenant(),live.id());}
                    if(credits.isEmpty())db.update("insert into model_allowance_credits values(?,?,?,?,?,?,?)",live.tenant(),live.id(),key,mode,amount,live.id(),now);
                    else db.update("update model_allowance_credits set amount=amount+? where tenant_id=? and owner_id=? and credit_key=?",amount,live.tenant(),live.id(),key);
                    db.update("update model_promotion_policy set issued=issued+? where id=1",amount);
                    auth.audit(live,"MODEL_PROMOTION_CLAIMED",Map.of("amountMicros",amount,"kind",mode));
                }
            }
        }
        return Map.of("credited_micros",amount,"budget",wallet(live));
    });}
    public Map<String,Object> topupReceipt(Identity actor,String owner,String requestId){
        if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{16,64}"))throw new ApiException(400,"INVALID_TOPUP_ID","Invalid top-up request ID");
        return tx.execute(status->{lock();Identity live=memberAdmin(actor,owner);var rows=db.queryForList("select amount from model_allowance_credits where tenant_id=? and owner_id=? and credit_key=?",live.tenant(),owner,"topup:"+requestId);
            return Map.of("requestId",requestId,"state",rows.isEmpty()?"ABSENT":"POSTED","amountMicros",rows.isEmpty()?0:n(rows.get(0),"amount"),"budget",account(live.tenant(),owner));});
    }
    /** Adds spending authorization, never resets consumption or enables a revoked account. */
    public Map<String,Object> topup(Identity actor,String owner,Topup body){return tx.execute(status->{lock();Identity live=memberAdmin(actor,owner);String key="topup:"+body.requestId();
        var credits=db.queryForList("select amount from model_allowance_credits where tenant_id=? and owner_id=? and credit_key=?",live.tenant(),owner,key);
        if(!credits.isEmpty()){if(n(credits.get(0),"amount")!=body.amountMicros())throw ApiException.conflict("TOPUP_ID_CONFLICT");return account(live.tenant(),owner);}
        var rows=db.queryForList("select * from model_user_budgets where tenant_id=? and owner_id=?",live.tenant(),owner);
        if(rows.isEmpty())throw new ApiException(409,"MODEL_BUDGET_REQUIRED","Configure allowance policy before adding credit");
        if(n(rows.get(0),"total_limit")>1000000000000L-body.amountMicros())throw ApiException.conflict("BUDGET_LIMIT_OVERFLOW");
        if(body.expectedVersion()==Integer.MAX_VALUE||db.update("update model_user_budgets set total_limit=total_limit+?,version=version+1 where tenant_id=? and owner_id=? and version=?",body.amountMicros(),live.tenant(),owner,body.expectedVersion())!=1)throw ApiException.conflict("BUDGET_VERSION_CONFLICT");
        db.update("insert into model_allowance_credits values(?,?,?,'TOPUP',?,?,?)",live.tenant(),owner,key,body.amountMicros(),live.id(),clock.millis());
        auth.audit(live,"MODEL_ALLOWANCE_TOPPED_UP",Map.of("owner",owner,"amountMicros",body.amountMicros(),"requestId",body.requestId()));return account(live.tenant(),owner);
    });}
    /** Eligibility is checked before accepting a ticket and again before granting a slot. */
    private record Eligibility(Map<String,Object> account,Map<String,Object> price,long reservation) {}
    private Eligibility eligible(Map<String,Object> global,String tenant,String owner,String deployment,long output){
        return eligible(global,tenant,owner,deployment,output,true);
    }
    private Eligibility eligible(Map<String,Object> global,String tenant,String owner,String deployment,long output,boolean includeHeld){
        if(Boolean.TRUE.equals(global.get("paused")))throw new ApiException(503,"MODEL_BUDGET_PAUSED","Paid model calls are paused");
        var accounts=db.queryForList("select * from model_user_budgets where tenant_id=? and owner_id=?",tenant,owner);
        if(accounts.isEmpty())throw new ApiException(403,"MODEL_BUDGET_REQUIRED","A model budget must be explicitly granted");var account=accounts.get(0);
        if(!Boolean.TRUE.equals(account.get("enabled"))||n(account,"expires_at")<=clock.millis())throw new ApiException(403,"MODEL_BUDGET_EXPIRED","Model budget is disabled or expired");
        if(output>n(account,"max_output_tokens"))throw new ApiException(400,"USER_OUTPUT_LIMIT","Output exceeds the user budget policy");
        var prices=db.queryForList("select * from model_budget_prices where tenant_id=? and deployment_id=? and approved_by is not null",tenant,deployment);
        if(prices.isEmpty())throw new ApiException(503,"MODEL_PRICE_REQUIRED","Operator-approved pricing is required before inference");var price=prices.get(0);
        if(ModelTariff.DEEPSEEK_FLASH.equals(price.get("tariff_profile"))){
            var target=db.queryForMap("select model,base_url from model_deployments where tenant_id=? and id=?",tenant,deployment);
            if(!ModelTariff.directFlash((String)target.get("model"),(String)target.get("base_url")))throw new ApiException(503,"MODEL_TARIFF_REVIEW_REQUIRED","Deployment no longer matches supplier tariff");
            ModelTariff.flash(clock.millis());
        }
        long reservation=cost(n(price,"input_ceiling"),output,n(price,"input_rate"),n(price,"output_rate"));String day=day();
        check(global,"global",day,reservation,"GLOBAL",includeHeld);check(account,scope(tenant,owner),day,reservation,"USER",includeHeld);return new Eligibility(account,price,reservation);
    }
    public void admit(Identity actor,String deployment,long output,Runnable insert){
        var global=lock();eligible(global,actor.tenant(),actor.id(),deployment,output,false);
        long recent=db.queryForObject("select count(*) from model_call_tickets where tenant_id=? and owner_id=? and created_at>?",Long.class,actor.tenant(),actor.id(),clock.millis()-60000);
        if(recent>=admissionLimit)throw new ApiException(429,"USER_MODEL_ADMISSION_RATE_LIMIT","Ticket admission rate exceeded");
        insert.run();
    }
    public String grantFailure(Map<String,Object> ticket){
        try{eligible(lock(),(String)ticket.get("tenant_id"),(String)ticket.get("owner_id"),(String)ticket.get("deployment_id"),n(ticket,"max_output_tokens"));return null;}
        catch(ApiException denied){return denied.code;}
    }
    public void requireFunds(Identity actor,Map<String,Object> ticket){eligible(lock(),actor.tenant(),actor.id(),(String)ticket.get("deployment_id"),n(ticket,"max_output_tokens"));}
    public int outputLimit(Identity actor){Identity live=user(actor);var rows=db.queryForList("select max_output_tokens,enabled,expires_at from model_user_budgets where tenant_id=? and owner_id=?",live.tenant(),live.id());
        if(rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled"))||n(rows.get(0),"expires_at")<=clock.millis())return 0;return (int)n(rows.get(0),"max_output_tokens");}
    /** Caller already owns pool and principal locks. Never acquire either while holding this lock. */
    public void reserve(Identity actor,Map<String,Object> ticket,Map<String,Object> deployment){
        var global=lock();String tenant=actor.tenant(),owner=actor.id();long now=clock.millis();var eligibility=eligible(global,tenant,owner,(String)deployment.get("id"),n(ticket,"max_output_tokens"));var account=eligibility.account();var price=eligibility.price();
        long heldCalls=db.queryForObject("select count(*) from model_budget_charges where tenant_id=? and owner_id=? and state='HELD'",Long.class,tenant,owner);
        if(heldCalls>=n(account,"concurrency_limit"))throw new ApiException(429,"USER_INFERENCE_BUSY","User inference concurrency exceeded");
        long recent=db.queryForObject("select count(*) from model_budget_charges where tenant_id=? and owner_id=? and created_at>?",Long.class,tenant,owner,now-60000);
        if(recent>=n(account,"calls_per_minute"))throw new ApiException(429,"USER_INFERENCE_RATE_LIMIT","User inference rate exceeded");
        long reservation=eligibility.reservation();String day=day();
        var rates=ModelTariff.DEEPSEEK_FLASH.equals(price.get("tariff_profile"))?ModelTariff.flash(now):new ModelTariff.Rates(n(price,"input_rate"),n(price,"cached_rate"),n(price,"output_rate"),"FIXED");
        db.update("insert into model_budget_charges(ticket_id,tenant_id,owner_id,day_key,reserved,input_rate,output_rate,input_ceiling,output_ceiling,created_at,state,cached_rate,tariff_profile,tariff_period) values(?,?,?,?,?,?,?,?,?,?,'HELD',?,?,?)",ticket.get("id"),tenant,owner,day,reservation,rates.input(),rates.output(),price.get("input_ceiling"),ticket.get("max_output_tokens"),now,rates.cached(),price.get("tariff_profile"),rates.period());
        db.update("update model_budget_guard set held=held+? where id=1",reservation);
        db.update("update model_user_budgets set held=held+? where tenant_id=? and owner_id=?",reservation,tenant,owner);
    }
    /** Admission can queue behind held funds; execution must still reserve the full cost. */
    private void check(Map<String,Object> row,String scope,String day,long amount,String prefix,boolean includeHeld){
        long remaining=Math.min(n(row,"total_limit")-n(row,"spent"),n(row,"daily_limit")-daily(scope,day));
        if(amount>remaining)throw new ApiException(429,prefix+"_MODEL_BUDGET_EXHAUSTED","Model spending budget cannot cover this request");
        if(includeHeld&&amount>remaining-n(row,"held"))throw new ApiException(429,prefix+"_MODEL_FUNDS_RESERVED","Model funds are reserved by unfinished calls");
    }
    static boolean fundsReserved(String code){return "USER_MODEL_FUNDS_RESERVED".equals(code)||"GLOBAL_MODEL_FUNDS_RESERVED".equals(code);}

    static long cost(long input,long output,long inputRate,long outputRate){
        return BigInteger.valueOf(input).multiply(BigInteger.valueOf(inputRate)).add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(outputRate))).add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000)).longValueExact();
    }
    static long cost(long input,long cached,long output,long inputRate,long cachedRate,long outputRate){
        if(input<0||cached<0||cached>input||output<0)throw new ArithmeticException("Invalid token usage");
        return BigInteger.valueOf(input-cached).multiply(BigInteger.valueOf(inputRate))
            .add(BigInteger.valueOf(cached).multiply(BigInteger.valueOf(cachedRate)))
            .add(BigInteger.valueOf(output).multiply(BigInteger.valueOf(outputRate)))
            .add(BigInteger.valueOf(999999)).divide(BigInteger.valueOf(1000000)).longValueExact();
    }
    /** Unknown execution retains its hold, including across midnight and restarts. */
    public void finish(String id,String state,String outcome,ModelUsage usage){
        lock();var rows=db.queryForList("select * from model_budget_charges where ticket_id=?",id);if(rows.isEmpty())return;var row=rows.get(0);
        if(!"HELD".equals(row.get("state"))||"UNKNOWN".equals(state))return;
        long charged=n(row,"reserved");
        boolean unsent="FAILED".equals(state)&&"UPSTREAM_CONNECT_FAILED".equals(outcome);
        // A connection that never sent a request has no provider usage, including cache usage.
        if(!unsent&&ModelTariff.DEEPSEEK_FLASH.equals(row.get("tariff_profile"))&&usage!=null&&usage.cached()==null)return;
        if(unsent)charged=0;
        else if(usage!=null&&"COMPLETE".equals(usage.quality())){
            try{charged=cost(usage.input(),usage.cached()==null?0:usage.cached(),usage.output(),n(row,"input_rate"),n(row,"cached_rate"),n(row,"output_rate"));}
            catch(ArithmeticException overflow){anomaly(row,"INVALID_USAGE_COST",true);return;}
            if(charged>n(row,"reserved"))anomaly(row,"RESERVATION_EXCEEDED",true);
            else if(usage.input()>n(row,"input_ceiling")||usage.output()>n(row,"output_ceiling"))anomaly(row,"TOKEN_CEILING_EXCEEDED",false);
        }
        else return; // Missing/partial usage is a hold awaiting reconciliation, never a claimed actual cost.
        db.update("update model_budget_charges set settlement_basis=? where ticket_id=?",unsent?"NO_EXECUTION":"PROVIDER_USAGE",id);
        settle(row,charged);
    }
    /** A provider fault must not turn the operator's global emergency switch on. */
    private void anomaly(Map<String,Object> charge,String reason,boolean isolate){
        var ticket=db.queryForMap("select tenant_id,deployment_id,zone from model_call_tickets where id=?",charge.get("ticket_id"));
        if(isolate)db.update("update model_deployments set enabled=false where tenant_id=? and id=?",ticket.get("tenant_id"),ticket.get("deployment_id"));
        db.update("insert into events(tenant_id,actor_id,event_type,payload_json,created_at,audit_zones) values(?,null,'MODEL_BUDGET_ANOMALY',?,?,?)",
            ticket.get("tenant_id"),json.write(Map.of("source","model-budget-guard","ticketId",charge.get("ticket_id"),"deploymentId",ticket.get("deployment_id"),"failureCode",reason,"state",isolate?"ISOLATED":"WARNING")),clock.millis(),json.write(Set.of((String)ticket.get("zone"))));
    }
    public void reconcile(String id){lock();var rows=db.queryForList("select * from model_budget_charges where ticket_id=? and state='HELD'",id);if(!rows.isEmpty()){
        boolean unsent=db.queryForObject("select count(*) from model_call_tickets where id=? and state='FAILED' and outcome='UPSTREAM_CONNECT_FAILED' and input_tokens is null and output_tokens is null",Integer.class,id)==1;
        db.update("update model_budget_charges set settlement_basis=? where ticket_id=?",unsent?"NO_EXECUTION":"ESTIMATED_MAXIMUM",id);
        settle(rows.get(0),unsent?0:n(rows.get(0),"reserved"));}}
    private void settle(Map<String,Object> row,long charged){
        String tenant=(String)row.get("tenant_id"),owner=(String)row.get("owner_id"),day=(String)row.get("day_key");long held=n(row,"reserved");
        db.update("update model_budget_guard set held=held-?,spent=spent+? where id=1",held,charged);
        db.update("update model_user_budgets set held=held-?,spent=spent+? where tenant_id=? and owner_id=?",held,charged,tenant,owner);
        for(String scope:List.of("global",scope(tenant,owner)))if(db.update("update model_budget_days set spent=spent+? where scope_id=? and day_key=?",charged,scope,day)==0)db.update("insert into model_budget_days values(?,?,?)",scope,day,charged);
        db.update("update model_budget_charges set state=?,charged=?,settled_at=? where ticket_id=?",charged==0?"RELEASED":"SETTLED",charged,clock.millis(),row.get("ticket_id"));
    }
    public record SupplierCorrection(@NotBlank @Size(max=200) String sessionId, boolean dryRun) {}
    /** Audited, exactly-once correction of legacy direct Flash charges with complete provider usage. */
    public Map<String,Object> correctSupplierCharges(Identity actor,SupplierCorrection body){return tx.execute(status->{
        lock();Identity live=operator(actor);
        var rows=db.queryForList("select c.*,t.input_tokens,t.cached_tokens,t.output_tokens,t.usage_quality,t.started_at,d.model,d.base_url from model_budget_charges c join model_call_tickets t on t.id=c.ticket_id join model_deployments d on d.id=t.deployment_id and d.tenant_id=t.tenant_id where t.session_id=? and c.state='SETTLED' and c.settlement_basis='LEGACY' and t.usage_quality='COMPLETE' and not exists(select 1 from model_budget_adjustments a where a.ticket_id=c.ticket_id) order by c.ticket_id limit 101",body.sessionId());
        if(rows.size()>100)throw new ApiException(400,"CORRECTION_TOO_LARGE","Review at most 100 calls per session");
        long previous=0,corrected=0;int count=0;
        for(var row:rows){
            if(!ModelTariff.directFlash((String)row.get("model"),(String)row.get("base_url"))||row.get("cached_tokens")==null||row.get("started_at")==null)continue;
            var rates=ModelTariff.flash(n(row,"started_at"));
            long old=n(row,"charged"),amount=cost(n(row,"input_tokens"),n(row,"cached_tokens"),n(row,"output_tokens"),rates.input(),rates.cached(),rates.output());
            if(amount>old)throw new ApiException(409,"CORRECTION_REQUIRES_REVIEW","Correction must not increase historical charges");
            previous=Math.addExact(previous,old);corrected=Math.addExact(corrected,amount);count++;
            if(body.dryRun())continue;
            long refund=old-amount;String tenant=(String)row.get("tenant_id"),owner=(String)row.get("owner_id"),day=(String)row.get("day_key");
            db.update("insert into model_budget_adjustments values(?,?,?,?,?,?,?)",row.get("ticket_id"),old,amount,ModelTariff.DEEPSEEK_FLASH,live.id(),"Cache and off-peak supplier tariff correction",clock.millis());
            db.update("update model_budget_charges set charged=?,input_rate=?,cached_rate=?,output_rate=?,tariff_profile=?,tariff_period=?,settlement_basis='PROVIDER_USAGE' where ticket_id=?",amount,rates.input(),rates.cached(),rates.output(),ModelTariff.DEEPSEEK_FLASH,rates.period(),row.get("ticket_id"));
            db.update("update model_budget_guard set spent=spent-?,version=version+1 where id=1",refund);
            db.update("update model_user_budgets set spent=spent-?,version=version+1 where tenant_id=? and owner_id=?",refund,tenant,owner);
            for(String scope:List.of("global",scope(tenant,owner)))db.update("update model_budget_days set spent=spent-? where scope_id=? and day_key=?",refund,scope,day);
        }
        var result=Map.<String,Object>of("calls",count,"previousMicros",previous,"correctedMicros",corrected,"returnedMicros",previous-corrected,"dryRun",body.dryRun());
        if(!body.dryRun())auth.audit(live,"MODEL_SUPPLIER_CHARGES_CORRECTED",Map.of("sessionId",body.sessionId(),"result",result));
        return result;
    });}
    /** Global pause/user expiry must also stop already running connections. */
    public boolean stopRequested(Map<String,Object> ticket){
        var global=db.queryForMap("select paused from model_budget_guard where id=1");if(Boolean.TRUE.equals(global.get("paused")))return true;
        var rows=db.queryForList("select enabled,expires_at from model_user_budgets where tenant_id=? and owner_id=?",ticket.get("tenant_id"),ticket.get("owner_id"));
        return rows.isEmpty()||!Boolean.TRUE.equals(rows.get(0).get("enabled"))||n(rows.get(0),"expires_at")<=clock.millis();
    }
}

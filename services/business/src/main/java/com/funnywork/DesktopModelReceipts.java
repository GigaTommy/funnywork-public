package com.funnywork;

import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Authenticated client reports relate a signed publication to one account-owned sync device. */
@Service
public class DesktopModelReceipts {
    public record Receipt(@NotNull @Pattern(regexp="[a-f0-9]{32}") String device,@NotNull @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String syncDevice,@Min(0) long version,@NotNull @Pattern(regexp="[a-f0-9]{64}") String digest,@NotNull @Pattern(regexp="[A-Za-z0-9.+_-]{1,80}") String clientVersion,@NotNull Boolean loaded) {}
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final DesktopModelReleases releases;private final Json json;private final Clock clock;
    public DesktopModelReceipts(JdbcTemplate db,AuthService auth,TenantLocks locks,DesktopModelReleases releases,Json json,Clock clock){this.db=db;this.auth=auth;this.locks=locks;this.releases=releases;this.json=json;this.clock=clock;}
    private Identity owner(Identity actor){actor=auth.current(actor);if(!actor.kind().equals("USER"))throw ApiException.forbidden();return actor;}
    @Transactional public void issued(Identity actor,String device,String recipient,String digest,long issued,long expires,DesktopModelPublication.Configuration configuration){
        locks.lock(actor.tenant());actor=owner(actor);db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
        if(!releases.resolve(actor).equals(configuration))throw ApiException.conflict("MODEL_PUBLICATION_CHANGED");
        String settings=json.write(Map.of("model",configuration.model(),"endpoint",configuration.endpoint(),"thinking",configuration.thinking(),"maxTokens",configuration.maxTokens()));
        var rows=db.queryForList("select recipient from desktop_model_receipts where tenant_id=? and principal_id=? and device_id=? for update",actor.tenant(),actor.id(),device);
        if(rows.isEmpty())db.update("insert into desktop_model_receipts(tenant_id,principal_id,device_id,issued_version,issued_digest,recipient,issued_at,expires_at,settings_json) values(?,?,?,?,?,?,?,?,?)",actor.tenant(),actor.id(),device,configuration.version(),digest,recipient,issued,expires,settings);
        else {
            if(!recipient.equals(rows.get(0).get("recipient")))db.update("update desktop_model_receipts set applied_version=null,applied_digest=null,applied_at=null,client_version=null,reported_at=null,report_state=null where tenant_id=? and principal_id=? and device_id=?",actor.tenant(),actor.id(),device);
            db.update("update desktop_model_receipts set issued_version=?,issued_digest=?,recipient=?,issued_at=?,expires_at=?,settings_json=? where tenant_id=? and principal_id=? and device_id=?",configuration.version(),digest,recipient,issued,expires,settings,actor.tenant(),actor.id(),device);
        }
    }
    @Transactional public Map<String,Object> acknowledge(Identity actor,Receipt receipt){
        locks.lock(actor.tenant());actor=owner(actor);db.queryForObject("select id from principals where id=? for update",String.class,actor.id());actor=owner(actor);
        var devices=db.queryForList("select id from sync_devices where id=? and tenant_id=? and principal_id=? and revoked=false for update",receipt.syncDevice(),actor.tenant(),actor.id());if(devices.isEmpty())throw ApiException.missing();
        var rows=db.queryForList("select * from desktop_model_receipts where tenant_id=? and principal_id=? and device_id=? for update",actor.tenant(),actor.id(),receipt.device());if(rows.isEmpty())throw ApiException.conflict("MODEL_NOT_ISSUED");var row=rows.get(0);
        if(receipt.version()!=((Number)row.get("issued_version")).longValue()||!receipt.digest().equals(row.get("issued_digest"))||((Number)row.get("expires_at")).longValue()<=clock.millis()||receipt.version()!=releases.resolve(actor).version())throw ApiException.conflict("MODEL_RECEIPT_STALE");
        db.update("update desktop_model_receipts set sync_device_id=null where tenant_id=? and principal_id=? and sync_device_id=? and device_id<>?",actor.tenant(),actor.id(),receipt.syncDevice(),receipt.device());
        if(Boolean.TRUE.equals(receipt.loaded()))db.update("update desktop_model_receipts set sync_device_id=?,applied_version=?,applied_digest=?,applied_at=?,client_version=? where tenant_id=? and principal_id=? and device_id=?",receipt.syncDevice(),receipt.version(),receipt.digest(),clock.millis(),receipt.clientVersion(),actor.tenant(),actor.id(),receipt.device());
        String state=Boolean.TRUE.equals(receipt.loaded())?"APPLIED":"NOT_APPLIED";
        db.update("update desktop_model_receipts set sync_device_id=?,client_version=?,reported_at=?,report_state=? where tenant_id=? and principal_id=? and device_id=?",receipt.syncDevice(),receipt.clientVersion(),clock.millis(),state,actor.tenant(),actor.id(),receipt.device());
        return Map.of("state",state,"version",receipt.version());
    }
}

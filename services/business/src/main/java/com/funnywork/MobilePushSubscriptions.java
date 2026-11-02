package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class MobilePushSubscriptions {
    public record Registration(String secret,String token,String environment,String locale,Boolean approvals,Boolean questions,Boolean turnEnded,Long revision){
        public Registration(String secret,String token,String environment,String locale,Boolean approvals,Boolean questions,Boolean turnEnded){this(secret,token,environment,locale,approvals,questions,turnEnded,null);}
    }
    private final JdbcTemplate db;private final AuthService auth;private final Clock clock;private final ApnsProvider provider;
    public MobilePushSubscriptions(JdbcTemplate db,AuthService auth,Clock clock,ApnsProvider provider){this.db=db;this.auth=auth;this.clock=clock;this.provider=provider;}
    private Identity actor(Identity actor){actor=auth.current(actor);if(!"USER".equals(actor.kind()))throw ApiException.forbidden();return actor;}
    private void valid(boolean condition){if(!condition)throw new ApiException(400,"INVALID_PUSH_REGISTRATION","Invalid notification registration");}
    public Map<String,Object> capabilities(Identity actor){actor(actor);return Map.of("available",provider.enabled());}
    @Transactional public Map<String,Object> register(Identity actor,String id,String sessionToken,Registration request){
        actor=actor(actor);valid(id!=null&&id.matches("[A-Za-z0-9_-]{1,64}")&&request!=null&&request.secret()!=null&&request.secret().matches("[a-fA-F0-9]{64}"));
        valid(request.token()!=null&&request.token().matches("[a-fA-F0-9]{32,512}")&&request.token().length()%2==0&&Set.of("sandbox","production").contains(Objects.toString(request.environment(),""))&&Set.of("zh-Hans","en").contains(Objects.toString(request.locale(),""))&&request.approvals()!=null&&request.questions()!=null&&request.turnEnded()!=null);
        db.queryForObject("select id from sync_push_registration_lock where id=1 for update",Integer.class);
        db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
        actor=actor(actor);
        if(!db.queryForList("select session_hash from sync_push_closed_sessions where session_hash=?",AuthService.hash(sessionToken)).isEmpty())throw ApiException.forbidden();
        String secret=AuthService.hash(request.secret().toLowerCase(Locale.ROOT));
        valid(request.revision()==null||(request.revision()>0&&request.revision()<=9007199254740991L));
        var order=db.queryForList("select * from sync_push_registration_order where id=? for update",id);
        if(order.isEmpty())db.update("insert into sync_push_registration_order(id,secret_hash,revision) values(?,?,0)",id,secret);
        else if(!MessageDigest.isEqual(secret.getBytes(StandardCharsets.US_ASCII),((String)order.get(0).get("secret_hash")).getBytes(StandardCharsets.US_ASCII)))throw ApiException.forbidden();
        long previous=order.isEmpty()?0:((Number)order.get(0).get("revision")).longValue();
        long revision=request.revision()==null?0:request.revision();
        String fingerprint=AuthService.hash(String.join("/",actor.id(),actor.tenant(),Long.toString(actor.authVersion()),AuthService.hash(sessionToken),request.token().toLowerCase(Locale.ROOT),request.environment(),request.locale(),request.approvals().toString(),request.questions().toString(),request.turnEnded().toString()));
        if(revision<previous||(revision>0&&revision==previous&&!fingerprint.equals(order.get(0).get("request_hash"))))throw ApiException.conflict("PUSH_REGISTRATION_STALE");
        var rows=db.queryForList("select * from sync_push_installations where id=? for update",id);
        db.update("update sync_push_registration_order set revision=?,request_hash=? where id=?",revision,fingerprint,id);
        if(rows.isEmpty()&&db.queryForObject("select count(*) from sync_push_installations where principal_id=?",Integer.class,actor.id())>=32)throw ApiException.conflict("PUSH_INSTALLATION_LIMIT");
        String token=request.token().toLowerCase(Locale.ROOT);String tokenHash=AuthService.hash(token);
        long now=clock.millis();
        if(!rows.isEmpty()) {
            var old=rows.get(0);
            if(actor.id().equals(old.get("principal_id"))&&actor.tenant().equals(old.get("tenant_id"))&&actor.authVersion()==((Number)old.get("auth_version")).longValue()
                &&AuthService.hash(sessionToken).equals(old.get("session_hash"))&&token.equals(old.get("token"))&&request.environment().equals(old.get("environment"))&&request.locale().equals(old.get("locale"))
                &&request.approvals().equals(old.get("approvals"))&&request.questions().equals(old.get("questions"))&&request.turnEnded().equals(old.get("turn_ended"))&&((Number)old.get("expires_at")).longValue()>now) {
                db.update("update sync_push_installations set expires_at=? where id=?",now+86400000L,id);
                return Map.of("bindingId",old.get("binding_id"),"available",provider.enabled(),"expiresAt",now+86400000L);
            }
        }
        // APNs may preserve a token across app reinstalls. Its current holder becomes the sole destination.
        db.update("delete from sync_push_installations where environment=? and token_hash=? and id<>?",request.environment(),tokenHash,id);
        db.update("delete from sync_push_installations where id=?",id);
        String binding=AuthService.id();
        db.update("insert into sync_push_installations(id,secret_hash,tenant_id,principal_id,auth_version,session_hash,management_session,binding_id,token,token_hash,environment,locale,approvals,questions,turn_ended,subscribed_at,expires_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,secret,actor.tenant(),actor.id(),actor.authVersion(),AuthService.hash(sessionToken),sessionToken.startsWith("ruoyi."),binding,token,tokenHash,request.environment(),request.locale(),request.approvals(),request.questions(),request.turnEnded(),now,now+86400000L);
        return Map.of("bindingId",binding,"available",provider.enabled(),"expiresAt",now+86400000L);
    }
    @Transactional public void remove(Identity actor,String id,String secret){remove(actor,id,secret,null);}
    @Transactional public void remove(Identity actor,String id,String secret,Long revision){
        actor=actor(actor);valid(id!=null&&id.matches("[A-Za-z0-9_-]{1,64}")&&secret!=null&&secret.matches("[a-fA-F0-9]{64}")&&(revision==null||(revision>0&&revision<=9007199254740991L)));
        db.queryForObject("select id from sync_push_registration_lock where id=1 for update",Integer.class);
        db.queryForObject("select id from principals where id=? for update",String.class,actor.id());actor=actor(actor);
        var order=db.queryForList("select * from sync_push_registration_order where id=? for update",id);
        String secretHash=AuthService.hash(secret.toLowerCase(Locale.ROOT));
        if(order.isEmpty()){
            db.update("insert into sync_push_registration_order(id,secret_hash,revision) values(?,?,0)",id,secretHash);
            order=db.queryForList("select * from sync_push_registration_order where id=? for update",id);
        }
        if(!MessageDigest.isEqual(secretHash.getBytes(StandardCharsets.US_ASCII),((String)order.get(0).get("secret_hash")).getBytes(StandardCharsets.US_ASCII)))return;
        var rows=db.queryForList("select principal_id,tenant_id from sync_push_installations where id=? for update",id);
        if(!rows.isEmpty()&&(!actor.id().equals(rows.get(0).get("principal_id"))||!actor.tenant().equals(rows.get(0).get("tenant_id"))))return;
        long previous=((Number)order.get(0).get("revision")).longValue();long next=revision==null?0:revision;
        String fingerprint=AuthService.hash("remove/"+actor.tenant()+"/"+actor.id());
        if(next<previous||(next>0&&next==previous&&!fingerprint.equals(order.get(0).get("request_hash"))))throw ApiException.conflict("PUSH_REGISTRATION_STALE");
        db.update("update sync_push_registration_order set revision=?,request_hash=? where id=?",next,fingerprint,id);
        db.update("delete from sync_push_installations where id=? and tenant_id=? and principal_id=?",id,actor.tenant(),actor.id());
    }
    @Transactional public void logout(Identity actor,String token){
        db.queryForObject("select id from principals where id=? for update",String.class,actor.id());
        String session=AuthService.hash(token);
        if(db.queryForList("select session_hash from sync_push_closed_sessions where session_hash=?",session).isEmpty())db.update("insert into sync_push_closed_sessions(session_hash,closed_at) values(?,?)",session,clock.millis());
        db.update("delete from sync_push_installations where tenant_id=? and principal_id=? and session_hash=?",actor.tenant(),actor.id(),session);
    }
}

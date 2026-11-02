package com.funnywork;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.*;

/** Recovery triage does not authorize execution or certify external outcomes. */
@Service
public class RecoveryCases {
    private final JdbcTemplate db;private final AuthService auth;private final TenantLocks locks;private final Clock clock;private final RecoveryEvidenceTrust trust;
    public RecoveryCases(JdbcTemplate db,AuthService auth,TenantLocks locks,Clock clock,RecoveryEvidenceTrust trust){this.db=db;this.auth=auth;this.locks=locks;this.clock=clock;this.trust=trust;}
    private Identity actor(Identity actor,String zone){actor=auth.current(actor);actor.requireUser("ADMIN");actor.requireZone(zone);return actor;}
    private String generation(){return db.queryForObject("select generation from recovery_generation where id=1",String.class);}
    private void bounds(String after,int limit){if(after==null || after.length()>64 || limit<1 || limit>100)throw new ApiException(400,"INVALID_PAGE","Invalid recovery page");}
    private static final String JOINS=" from recovery_cases c join attempts a on a.id=c.attempt_id and a.tenant_id=c.tenant_id join work_units u on u.id=a.unit_id and u.tenant_id=c.tenant_id join tasks t on t.id=u.task_id and t.tenant_id=c.tenant_id join nodes n on n.id=a.node_id and n.tenant_id=c.tenant_id ";
    private static final String COLUMNS="select c.id,c.attempt_id,c.recovery_generation,c.state,c.version,c.claimed_by,c.created_at,c.updated_at,a.node_id,a.recovery_generation as attempt_generation,a.state as attempt_state,u.task_id,t.zone ";
    @Transactional
    public Map<String,Object> discover(Identity actor,String zone,int limit){
        locks.lock(actor.tenant());actor=actor(actor,zone);bounds("",limit);
        String generation=generation();
        var candidates=db.queryForList("""
            select a.id from attempts a join work_units u on u.id=a.unit_id and u.tenant_id=a.tenant_id
            join tasks t on t.id=u.task_id and t.tenant_id=a.tenant_id join nodes n on n.id=a.node_id and n.tenant_id=a.tenant_id
            where a.tenant_id=? and t.zone=? and n.zone=? and a.state in ('RESERVED','RUNNING') and a.recovery_generation<>?
            and not exists(select 1 from recovery_cases c where c.tenant_id=a.tenant_id and c.attempt_id=a.id and c.recovery_generation=?)
            order by a.id limit ?
            """,actor.tenant(),zone,zone,generation,generation,limit+1);
        boolean more=candidates.size()>limit;
        if(more)candidates=candidates.subList(0,limit);
        var ids=new ArrayList<String>();long now=clock.millis();
        for(var candidate:candidates){
            String id=AuthService.id();ids.add(id);
            db.update("insert into recovery_cases(id,tenant_id,attempt_id,recovery_generation,state,version,created_at,updated_at) values(?,?,?,?,'NEEDS_ATTENTION',0,?,?)",id,actor.tenant(),candidate.get("id"),generation,now,now);
        }
        if(!ids.isEmpty())auth.audit(actor,"RECOVERY_CASES_DISCOVERED",Map.of("caseIds",ids,"generation",generation,"zone",zone));
        return Map.of("caseIds",ids,"created",ids.size(),"moreUndiscovered",more,"recoveryGeneration",generation,"releaseAllowed",false);
    }
    @Transactional(readOnly=true)
    public Map<String,Object> page(Identity actor,String zone,String after,int limit){
        return page(actor,zone,after,limit,"");
    }
    @Transactional(readOnly=true)
    public Map<String,Object> page(Identity actor,String zone,String after,int limit,String taskId){
        actor=actor(actor,zone);bounds(after,limit);String generation=generation();
        if(taskId==null || !taskId.matches("[A-Za-z0-9-]{0,64}"))throw new ApiException(400,"INVALID_FILTER","Invalid task filter");
        var parameters=new ArrayList<Object>(List.of(actor.tenant(),zone,zone,generation,after));
        String filter="";if(!taskId.isEmpty()){filter=" and t.id=?";parameters.add(taskId);}parameters.add(limit+1);
        var rows=db.queryForList(COLUMNS+JOINS+" where c.tenant_id=? and t.zone=? and n.zone=? and c.recovery_generation=? and c.id>?"+filter+" order by c.id limit ?",parameters.toArray());
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("recoveryGeneration",generation);result.put("releaseAllowed",false);result.put("consistentSnapshot",false);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("id"));return result;
    }
    @Transactional
    public Map<String,Object> claim(Identity actor,String id,long expectedVersion){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");
        if(expectedVersion<0)throw new ApiException(400,"INVALID_VERSION","Expected version must be nonnegative");
        var rows=db.queryForList(COLUMNS+JOINS+" where c.id=? and c.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);
        actor.requireZone((String)row.get("zone"));
        String nodeZone=db.queryForObject("select zone from nodes where id=? and tenant_id=?",String.class,row.get("node_id"),actor.tenant());actor.requireZone(nodeZone);
        if(!generation().equals(row.get("recovery_generation")))throw ApiException.conflict("STALE_RECOVERY_CASE");
        if(!"NEEDS_ATTENTION".equals(row.get("state")) || ((Number)row.get("version")).longValue()!=expectedVersion)throw ApiException.conflict("RECOVERY_CASE_CONFLICT");
        db.update("update recovery_cases set state='IN_REVIEW',claimed_by=?,version=version+1,updated_at=? where id=? and tenant_id=?",actor.id(),clock.millis(),id,actor.tenant());
        auth.audit(actor,"RECOVERY_CASE_CLAIMED",Map.of("caseId",id,"version",expectedVersion+1));
        return Map.of("caseId",id,"state","IN_REVIEW","version",expectedVersion+1,"claimedBy",actor.id(),"releaseAllowed",false);
    }
    public record Submission(Long expectedVersion,String requestKey,String kind,String content,String sha256){}
    private Map<String,Object> scopedCase(Identity actor,String id){
        var rows=db.queryForList(COLUMNS+JOINS+" where c.id=? and c.tenant_id=?",id,actor.tenant());
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);actor.requireZone((String)row.get("zone"));
        actor.requireZone(db.queryForObject("select zone from nodes where id=? and tenant_id=?",String.class,row.get("node_id"),actor.tenant()));
        return row;
    }
    private Map<String,Object> receipt(Map<String,Object> row){
        return Map.of("evidenceId",row.get("id"),"caseId",row.get("case_id"),"acceptedVersion",row.get("accepted_version"),
            "sha256",row.get("content_sha256"),"sourceVerified",false,"physicalStopVerified",false,"releaseAllowed",false);
    }
    @Transactional
    public Map<String,Object> submitEvidence(Identity actor,String id,Submission body){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");var c=scopedCase(actor,id);
        if(!generation().equals(c.get("recovery_generation")))throw ApiException.conflict("STALE_RECOVERY_CASE");
        if(!"IN_REVIEW".equals(c.get("state")) || !actor.id().equals(c.get("claimed_by")))throw ApiException.forbidden();
        if(body==null || body.expectedVersion()==null || body.expectedVersion()<0 || body.expectedVersion()==Long.MAX_VALUE
            || body.requestKey()==null || !body.requestKey().matches("[A-Za-z0-9_-]{1,64}")
            || body.kind()==null || !Set.of("OPERATOR_NOTE","NODE_COMPARISON","STOP_ATTESTATION","BUSINESS_RECEIPT").contains(body.kind())
            || body.content()==null || body.content().isBlank() || body.content().length()>16384
            || body.sha256()==null || !body.sha256().matches("[0-9a-f]{64}") || !AuthService.hash(body.content()).equals(body.sha256()))
            throw new ApiException(400,"INVALID_RECOVERY_EVIDENCE","Invalid evidence content, digest or metadata");
        var previous=db.queryForList("select * from recovery_evidence where tenant_id=? and case_id=? and request_key=?",actor.tenant(),id,body.requestKey());
        if(!previous.isEmpty()){
            var old=previous.get(0);
            if(!actor.id().equals(old.get("submitted_by")) || !body.kind().equals(old.get("kind")) || !body.content().equals(old.get("content_text")) || ((Number)old.get("expected_version")).longValue()!=body.expectedVersion())throw ApiException.conflict("EVIDENCE_KEY_CONFLICT");
            return receipt(old);
        }
        if(((Number)c.get("version")).longValue()!=body.expectedVersion())throw ApiException.conflict("RECOVERY_CASE_CONFLICT");
        if(db.queryForObject("select count(*) from recovery_evidence where tenant_id=? and case_id=?",Integer.class,actor.tenant(),id)>=100)throw ApiException.conflict("RECOVERY_EVIDENCE_LIMIT");
        String evidence=AuthService.id();long now=clock.millis(),version=body.expectedVersion()+1;
        db.update("insert into recovery_evidence(id,tenant_id,case_id,request_key,kind,content_sha256,content_text,submitted_by,expected_version,accepted_version,created_at) values(?,?,?,?,?,?,?,?,?,?,?)",evidence,actor.tenant(),id,body.requestKey(),body.kind(),body.sha256(),body.content(),actor.id(),body.expectedVersion(),version,now);
        db.update("update recovery_cases set version=?,updated_at=? where id=? and tenant_id=?",version,now,id,actor.tenant());
        auth.audit(actor,"RECOVERY_EVIDENCE_SUBMITTED",Map.of("caseId",id,"evidenceId",evidence,"kind",body.kind(),"sha256",body.sha256(),"version",version));
        return receipt(db.queryForMap("select * from recovery_evidence where id=? and tenant_id=?",evidence,actor.tenant()));
    }
    @Transactional(readOnly=true)
    public Map<String,Object> evidencePage(Identity actor,String id,long after,int limit){
        actor=auth.current(actor);actor.requireUser("ADMIN");scopedCase(actor,id);bounds("",limit);
        if(after<0)throw new ApiException(400,"INVALID_PAGE","Invalid evidence cursor");
        var rows=db.queryForList("select id,kind,content_sha256,submitted_by,accepted_version,created_at from recovery_evidence where tenant_id=? and case_id=? and accepted_version>? order by accepted_version limit ?",actor.tenant(),id,after,limit+1);
        boolean more=rows.size()>limit;if(more)rows=new ArrayList<>(rows.subList(0,limit));
        var result=new LinkedHashMap<String,Object>();result.put("items",rows);result.put("sourceVerified",false);result.put("physicalStopVerified",false);result.put("releaseAllowed",false);
        if(more)result.put("nextCursor",rows.get(rows.size()-1).get("accepted_version"));return result;
    }
    @Transactional(readOnly=true)
    public Map<String,Object> evidence(Identity actor,String id,String evidence){
        actor=auth.current(actor);actor.requireUser("ADMIN");scopedCase(actor,id);
        var rows=db.queryForList("select id,case_id,kind,content_sha256,content_text,submitted_by,accepted_version,created_at from recovery_evidence where tenant_id=? and case_id=? and id=?",actor.tenant(),id,evidence);
        if(rows.isEmpty())throw ApiException.missing();var result=new LinkedHashMap<>(rows.get(0));
        result.put("sourceVerified",false);result.put("physicalStopVerified",false);result.put("releaseAllowed",false);return result;
    }

    @Transactional
    public Map<String,Object> verifyEvidence(Identity actor,String id,String evidence){
        locks.lock(actor.tenant());actor=auth.current(actor);actor.requireUser("ADMIN");var c=scopedCase(actor,id);
        if(!generation().equals(c.get("recovery_generation")))throw ApiException.conflict("STALE_RECOVERY_CASE");
        if(!"IN_REVIEW".equals(c.get("state")) || !actor.id().equals(c.get("claimed_by")))throw ApiException.forbidden();
        var rows=db.queryForList("select * from recovery_evidence where tenant_id=? and case_id=? and id=?",actor.tenant(),id,evidence);
        if(rows.isEmpty())throw ApiException.missing();var row=rows.get(0);String content=(String)row.get("content_text");
        if(!AuthService.hash(content).equals(row.get("content_sha256")))throw ApiException.conflict("RECOVERY_EVIDENCE_CHANGED");
        long now=clock.millis();
        var verified=trust.verify(content,new RecoveryEvidenceTrust.Context(actor.tenant(),(String)c.get("zone"),id,(String)c.get("attempt_id"),(String)c.get("node_id"),(String)c.get("recovery_generation"),(String)row.get("kind")),now);
        var previous=db.queryForList("select * from recovery_evidence_verification where tenant_id=? and evidence_id=?",actor.tenant(),evidence);
        if(previous.isEmpty()){
            db.update("insert into recovery_evidence_verification(tenant_id,evidence_id,issuer_id,key_sha256,policy_sha256,content_sha256,asserted_outcome,issued_at,expires_at,verified_at,verified_by) values(?,?,?,?,?,?,?,?,?,?,?)",actor.tenant(),evidence,verified.issuer(),verified.keySha256(),verified.policySha256(),row.get("content_sha256"),verified.outcome(),verified.issuedAt(),verified.expiresAt(),now,actor.id());
            auth.audit(actor,"RECOVERY_EVIDENCE_SOURCE_VERIFIED",Map.of("caseId",id,"evidenceId",evidence,"issuer",verified.issuer(),"keySha256",verified.keySha256(),"assertedOutcome",verified.outcome()));
        } else if(!verified.policySha256().equals(previous.get(0).get("policy_sha256")) || !row.get("content_sha256").equals(previous.get(0).get("content_sha256")))throw ApiException.conflict("RECOVERY_VERIFICATION_POLICY_CHANGED");
        return Map.of("evidenceId",evidence,"sourceVerified",true,"checkedAt",now,"validUntil",verified.expiresAt(),"issuer",verified.issuer(),"assertedOutcome",verified.outcome(),"physicalStopVerified",false,"releaseAllowed",false);
    }

}

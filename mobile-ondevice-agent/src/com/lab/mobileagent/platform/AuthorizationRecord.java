package com.lab.mobileagent.platform;

import java.util.Arrays;
import org.json.JSONObject;

/**
 * Immutable match receipt issued by the trusted authorization store. It never grants access by itself.
 * The store must recheck entry version, revocation, lifetime and one-operation consumption at dispatch.
 */
public final class AuthorizationRecord {
    public final String entryId,scopeKind,scopeId,bindingHash,operationDigest;
    public final long entryVersion,issuedAtEpochMs,expiresAtEpochMs;
    private final String approvedScope;
    public AuthorizationRecord(OperationDescription operation,String entryId,long entryVersion,String scopeKind,
            String scopeId,JSONObject approvedScope,long issuedAtEpochMs,long expiresAtEpochMs)throws Exception{
        if(operation==null||entryVersion<1||issuedAtEpochMs<0||expiresAtEpochMs<0
                ||(expiresAtEpochMs!=0&&expiresAtEpochMs<=issuedAtEpochMs))throw new IllegalArgumentException("invalid_authorization_record");
        if(!Arrays.asList("operation","task","conversation","user").contains(scopeKind))
            throw new IllegalArgumentException("invalid_grant_lifetime_scope");
        this.entryId=JsonSnapshot.text(entryId,"entry_id",256);this.entryVersion=entryVersion;
        this.scopeKind=scopeKind;this.scopeId=JsonSnapshot.text(scopeId,"grant_scope_id",256);
        this.approvedScope=JsonSnapshot.object(approvedScope);this.issuedAtEpochMs=issuedAtEpochMs;this.expiresAtEpochMs=expiresAtEpochMs;
        this.bindingHash=operation.bindingHash;this.operationDigest=operation.digest();
    }
    public JSONObject approvedScope()throws Exception{return new JSONObject(approvedScope);}
    public boolean expired(long nowEpochMs){return nowEpochMs<issuedAtEpochMs||(expiresAtEpochMs!=0&&nowEpochMs>=expiresAtEpochMs);}
    public void requireMatch(OperationDescription operation,SessionBinding binding,long nowEpochMs){
        operation.requireBinding(binding);
        if(!bindingHash.equals(binding.fingerprint())||!operationDigest.equals(operation.digest()))
            throw new SecurityException("authorization_record_operation_mismatch");
        if(expired(nowEpochMs))throw new SecurityException("authorization_record_expired");
    }
    public JSONObject json()throws Exception{return new JSONObject().put("entry_id",entryId).put("entry_version",entryVersion)
            .put("scope_kind",scopeKind).put("scope_id",scopeId).put("binding_hash",bindingHash)
            .put("operation_digest",operationDigest).put("approved_scope",approvedScope())
            .put("issued_at_epoch_ms",issuedAtEpochMs).put("expires_at_epoch_ms",expiresAtEpochMs==0?JSONObject.NULL:expiresAtEpochMs);}
}

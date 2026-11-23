package com.lab.mobileagent.platform;

import com.lab.mobileagent.model.Action;
import org.json.JSONObject;

/**
 * A service-resolved operation. Page and target must come from the framework's DisplayList resolver,
 * not an agent's self-reported intent. Frozen before authorization and queue admission.
 */
public final class OperationDescription {
    public final String requestId,bindingHash,observationId,uiRevision,pageContext,targetElement,actionName;
    private final String parameters,encoded,digest;
    public OperationDescription(SessionBinding binding,String requestId,String observationId,String uiRevision,
            String pageContext,String targetElement,Action requested)throws Exception{
        if(binding==null||requested==null)throw new IllegalArgumentException("operation_binding_and_action_required");
        this.requestId=JsonSnapshot.text(requestId,"request_id",128);
        this.bindingHash=binding.fingerprint();
        this.observationId=JsonSnapshot.text(observationId,"observation_id",256);
        this.uiRevision=JsonSnapshot.text(uiRevision,"ui_revision",256);
        this.pageContext=JsonSnapshot.text(pageContext,"page_context",4096);
        this.targetElement=JsonSnapshot.text(targetElement,"target_element",4096);
        this.actionName=JsonSnapshot.text(requested.name,"action_name",128);
        this.parameters=JsonSnapshot.object(requested.args);
        encoded=JsonSnapshot.object(new JSONObject().put("request_id",requestId).put("binding_hash",bindingHash)
                .put("observation_id",observationId).put("ui_revision",uiRevision).put("page_context",pageContext)
                .put("target_element",targetElement).put("action",actionName).put("parameters",new JSONObject(parameters))
                .put("identity_source","service_session_binding").put("target_source","framework_display_list"));
        digest=JsonSnapshot.digest(encoded);
    }
    public JSONObject parameters()throws Exception{return new JSONObject(parameters);}
    public Action action()throws Exception{return new Action(actionName,new JSONObject(parameters));}
    public JSONObject json()throws Exception{return new JSONObject(encoded);}
    public String digest(){return digest;}
    public void requireBinding(SessionBinding binding){
        if(binding==null||!bindingHash.equals(binding.fingerprint()))throw new SecurityException("operation_session_changed");
    }
}

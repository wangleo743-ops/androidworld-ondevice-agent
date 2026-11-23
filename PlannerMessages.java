package com.lab.mobileagent.l1;

import org.json.JSONObject;

/** Values transported across the planner boundary; neither representation carries a capability. */
public final class PlannerMessages {
    private PlannerMessages(){}
    public static JSONObject reply(Decision d)throws Exception{
        JSONObject out=new JSONObject().put("kind",d.kind)
                .put("action",d.action==null?JSONObject.NULL:d.action.json()).put("answer",d.answer==null?JSONObject.NULL:d.answer);
        if(d.authorization!=null)out.put("authorization",new JSONObject().put("assessment",d.authorization.assessment)
                .put("effect",d.authorization.effect).put("reason_code",d.authorization.reasonCode));
        return out;
    }
    public static Decision fixture(String fixture)throws Exception{
        String effect="search_beijing",assessment="within_scope",reason="requested_forecast";
        int x=100,y=55;boolean blocked=false;
        switch(fixture){
            case "search":break;
            case "location":x=500;y=550;effect="use_location";break;
            case "wrong-target":x=500;y=550;break;
            case "request-location":blocked=true;effect="use_location";assessment="needs_approval";reason="location_not_granted";break;
            default:throw new IllegalArgumentException("unknown_authorization_fixture");
        }
        JSONObject out=new JSONObject().put("kind",blocked?"blocked":"action")
                .put("answer",blocked?"Location access requires approval.":JSONObject.NULL)
                .put("action",blocked?JSONObject.NULL:new JSONObject().put("name","click")
                        .put("args",new JSONObject().put("x",x).put("y",y)))
                .put("authorization",new JSONObject().put("effect",effect).put("assessment",assessment).put("reason_code",reason));
        return Decision.parse(out.toString(),true);
    }
}

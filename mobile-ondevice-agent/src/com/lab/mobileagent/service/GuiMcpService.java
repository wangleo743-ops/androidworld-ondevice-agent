package com.lab.mobileagent.service;

import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.l1.StrictJson;
import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.runtime.ControlErrors;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Session-local MCP server. Messages travel over an already bound host connection; no socket,
 * global tool registry, or client-supplied identity, display, package, grant or executable.
 */
public final class GuiMcpService implements AutoCloseable {
    public interface Access { void verify(String operation,Action action,String phase) throws Exception; }
    public interface Backend { JSONObject execute(String operation,Action action) throws Exception; }
    public interface QueueNotice { void accepted(String operation,Action action) throws Exception; }
    public interface OwnedReclaimer extends SessionResourceManager.OwnedReclaimer {}
    public static final String VERSION="2025-06-18";
    private static final String[] OPS={"open_app","click","swipe","type","press_key","go_back","wait","take_screenshot","observe","update"};
    private static final String[] DESCRIPTIONS={"Open the bound browser on the task display.",
        "Tap a point on the bound display.","Swipe between two points on the bound display.",
        "Enter text into the task UI.","Send a supported key to the task UI.","Navigate back in the task UI.",
        "Wait for the task UI.","Request a screenshot of the task display.","Observe the current bound UI and return framework feedback.",
        "Request an update on the bound UI and observe the resulting frame."};
    private final SessionResourceManager queue;
    private final Access access;
    private final Backend backend;
    private final QueueNotice queueNotice;
    private final String mode;
    private final Set<Object> ids=new HashSet<>();
    private final AtomicLong localIds=new AtomicLong();
    private int state; // 0 new, 1 negotiated, 2 client ready

    public GuiMcpService(Access access,Backend backend){this("agent-exclusive",access,backend);}
    public GuiMcpService(String mode,Access access,Backend backend){
        this(mode,access,backend,(op,action)->{});
    }
    public GuiMcpService(String mode,Access access,Backend backend,QueueNotice queueNotice){
        this(new ResourceOperationQueue(null,-1,mode),access,backend,queueNotice);
    }
    public GuiMcpService(SessionResourceManager resources,Access access,Backend backend,QueueNotice queueNotice){
        if(resources==null || access==null || backend==null || queueNotice==null)
            throw new IllegalArgumentException("invalid_gui_service_binding");
        this.queue=resources;this.mode=resources.uiMode();this.access=access;this.backend=backend;this.queueNotice=queueNotice;
    }
    public JSONObject clientCall(Action action)throws Exception {
        if(action==null)throw new IllegalArgumentException("missing_gui_action");
        return clientTool(action.name,action.args);
    }
    public JSONObject clientObserve()throws Exception {return clientTool("observe",new JSONObject());}
    public JSONObject clientUpdate()throws Exception {return clientTool("update",new JSONObject());}
    private synchronized void startLocalClient()throws Exception{
        if(state==0){
            JSONObject p=new JSONObject().put("protocolVersion",VERSION).put("capabilities",new JSONObject())
                    .put("clientInfo",new JSONObject().put("name","phone-agent").put("version","1"));
            JSONObject r=handle(request("initialize",p).toString());
            if(!r.has("result"))throw new IllegalStateException("mcp_initialize_failed");
        }
        if(state==1) handle(new JSONObject().put("jsonrpc","2.0").put("method","notifications/initialized").toString());
    }
    private JSONObject request(String method,JSONObject params)throws Exception{
        return new JSONObject().put("jsonrpc","2.0").put("id","phone-"+localIds.incrementAndGet())
                .put("method",method).put("params",params);
    }
    private JSONObject clientTool(String op,JSONObject args)throws Exception{
        startLocalClient();
        JSONObject p=new JSONObject().put("name","gui."+op).put("arguments",args);
        JSONObject r=handle(request("tools/call",p).toString());
        if(r.has("error"))throw new ControlErrors.Denied(r.getJSONObject("error").getString("message"));
        JSONObject data=r.getJSONObject("result").getJSONObject("structuredContent");
        if(r.getJSONObject("result").optBoolean("isError"))throw new ControlErrors.Denied(data.getString("reason"));
        return data;
    }
    public void cancelQueued(){queue.cancel(null);}
    @Override public void close(){queue.close();}
    public boolean reclaimExclusive(OwnedReclaimer resources)throws Exception{
        if(!"agent-exclusive".equals(mode))return false;
        if(resources==null)throw new IllegalArgumentException("missing_owned_reclaimer");
        return queue.reclaim(resources);
    }
    public JSONObject report()throws Exception{
        return queue.report().put("protocol","MCP").put("protocol_version",VERSION).put("mode",mode)
                .put("transport","session_local_jsonrpc");
    }
    private static boolean validId(Object v){
        return v instanceof String ? !((String)v).isEmpty() && ((String)v).length()<=96 :
            v instanceof Integer || v instanceof Long;
    }
    private static JSONObject error(Object id,int code,String message)throws Exception{
        return new JSONObject().put("jsonrpc","2.0").put("id",id==null?JSONObject.NULL:id)
                .put("error",new JSONObject().put("code",code).put("message",message));
    }
    private static JSONObject success(Object id,JSONObject value)throws Exception{
        return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",value);
    }
    private static boolean only(JSONObject obj,String... fields){
        java.util.List<String> allowed=Arrays.asList(fields);
        for(Iterator<String> i=obj.keys();i.hasNext();)if(!allowed.contains(i.next()))return false;
        return true;
    }
    private static boolean textField(JSONObject o,String name){return o.opt(name) instanceof String && !o.optString(name).isEmpty();}
    /** Returns null for notifications. The transport must supply an authenticated, session-bound channel. */
    public JSONObject handle(String message)throws Exception{
        if(message==null||message.length()>32768)return error(null,-32600,"mcp_invalid_request");
        JSONObject req;
        try{StrictJson.validate(message);}catch(IllegalArgumentException malformed){return error(null,-32700,"mcp_parse_error");}
        try{req=StrictJson.parseObject(message);}catch(IllegalArgumentException shape){return error(null,-32600,"mcp_invalid_request");}
        Object id=req.opt("id");
        if(!"2.0".equals(req.opt("jsonrpc")) || !textField(req,"method") || (req.has("id")&&!validId(id))
                || !only(req,"jsonrpc","id","method","params"))return error(null,-32600,"mcp_invalid_request");
        if(queue.isClosed())return id==null?null:error(id,-32000,"session_closed");
        String method=req.getString("method");
        JSONObject p=req.has("params")?req.optJSONObject("params"):new JSONObject();
        if(p==null || (p.has("_meta")&&p.optJSONObject("_meta")==null))return id==null?null:error(id,-32602,"mcp_invalid_params");
        if(id==null){
            if("notifications/initialized".equals(method) && only(p,"_meta")){
                synchronized(this){if(state==1)state=2;}
            }else if("notifications/cancelled".equals(method)&&validId(p.opt("requestId"))){
                queue.cancel(p.get("requestId"));
            }
            return null;
        }
        synchronized(this){
            if(ids.size()>=4096)return error(id,-32000,"mcp_session_request_limit");
            if(!ids.add(id))return error(id,-32600,"mcp_request_id_reused");
            if("ping".equals(method))return success(id,new JSONObject());
            if("initialize".equals(method)){
                if(state!=0)return error(id,-32600,"mcp_already_initialized");
                JSONObject client=p.optJSONObject("clientInfo");
                if(!textField(p,"protocolVersion")||p.optJSONObject("capabilities")==null||client==null||
                        !textField(client,"name")||!textField(client,"version"))return error(id,-32602,"mcp_invalid_initialize");
                state=1;
                return success(id,new JSONObject().put("protocolVersion",VERSION)
                        .put("capabilities",new JSONObject().put("tools",new JSONObject().put("listChanged",false)))
                        .put("serverInfo",new JSONObject().put("name","android-agent-execution-service").put("version","1")));
            }
            if(state!=2)return error(id,-32002,"mcp_not_initialized");
        }
        if("tools/list".equals(method)){
            if(!only(p,"_meta"))return error(id,-32602,"mcp_invalid_cursor");
            JSONArray tools=new JSONArray();
            for(int i=0;i<OPS.length;i++) tools.put(new JSONObject().put("name","gui."+OPS[i])
                    .put("description",DESCRIPTIONS[i]).put("inputSchema",schema(OPS[i])));
            return success(id,new JSONObject().put("tools",tools));
        }
        if(!"tools/call".equals(method))return error(id,-32601,"mcp_unknown_method");
        Object tool=p.opt("name");
        if(!(tool instanceof String)||!((String)tool).startsWith("gui.")||!only(p,"name","arguments","_meta"))
            return error(id,-32602,"mcp_invalid_tool");
        String op=((String)tool).substring(4);
        if(!Arrays.asList(OPS).contains(op))return error(id,-32602,"mcp_unknown_tool");
        JSONObject args=p.has("arguments")?p.optJSONObject("arguments"):new JSONObject();
        if(args==null)return error(id,-32602,"mcp_invalid_tool_arguments");
        final Action action;
        try{
            if("observe".equals(op)||"update".equals(op)){
                if(args.length()!=0)throw new IllegalArgumentException();
                action=null;
            }else {
                JSONObject envelope=new JSONObject().put("kind","action").put("answer",JSONObject.NULL)
                        .put("action",new JSONObject().put("name",op).put("args",args));
                action=Decision.parse(envelope.toString()).action;
            }
        }catch(Exception invalid){return error(id,-32602,"mcp_invalid_tool_arguments");}
        JSONObject feedback;boolean failed=false;
        try{
            feedback=queue.submit(id,()->access.verify(op,action,"admission"),()->queueNotice.accepted(op,action),
                    ()->access.verify(op,action,"dispatch"),
                    ()->backend.execute(op,action));
        }catch(Exception denied){
            failed=true;feedback=new JSONObject().put("reason",ControlErrors.safe(denied));
        }
        JSONArray content=new JSONArray();
        if(!failed && ("observe".equals(op)||"update".equals(op)) && feedback.has("data")){
            Object png=feedback.opt("data");
            if(!"image/png".equals(feedback.opt("mimeType")) || !(png instanceof String) || ((String)png).isEmpty())
                return error(id,-32603,"invalid_observation_image");
            content.put(new JSONObject().put("type","image").put("mimeType","image/png").put("data",png));
            JSONObject metadata=new JSONObject();
            for(Iterator<String> it=feedback.keys();it.hasNext();){
                String key=it.next();
                if(!"data".equals(key))metadata.put(key,feedback.get(key));
            }
            feedback=metadata;
        }else content.put(new JSONObject().put("type","text").put("text",feedback.toString()));
        return success(id,new JSONObject().put("isError",failed).put("structuredContent",feedback).put("content",content));
    }
    private static JSONObject field(String type)throws Exception{return new JSONObject().put("type",type);}
    private static JSONObject enumeration(String... values)throws Exception{
        return field("string").put("enum",new JSONArray(Arrays.asList(values)));
    }
    private static JSONObject schema(String op)throws Exception{
        JSONObject props=new JSONObject();JSONArray required=new JSONArray();
        switch(op){
            case "open_app":props.put("app_name",enumeration("Browser"));break;
            case "press_key":props.put("key",enumeration("BACK","HOME","ENTER","DEL","TAB","ESCAPE"));break;
            case "type":props.put("text",field("string").put("maxLength",2000)).put("press_enter",field("boolean"));break;
            case "wait":props.put("seconds",field("number").put("minimum",0).put("maximum",10));break;
            case "click":case "swipe":
                String[] coordinates="click".equals(op)?new String[]{"x","y"}:new String[]{"start_x","start_y","end_x","end_y"};
                for(String k:coordinates)props.put(k,field("integer").put("minimum",0).put("maximum",999));
                break;
        }
        for(Iterator<String> i=props.keys();i.hasNext();)required.put(i.next());
        return field("object").put("properties",props).put("required",required).put("additionalProperties",false);
    }
}

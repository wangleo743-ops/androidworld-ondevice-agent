package com.lab.mobileagent.standard;

import org.json.JSONArray;
import org.json.JSONObject;

/** Host JVM process fixture. Never calls Android, a model, or a network endpoint. */
public final class StockMcpFixture {
    public static void main(String[] args) throws Exception {
        String mode=args[0];
        StockWire.object(StockWire.read(System.in,StockWire.MAX_REQUEST_BYTES));
        JSONObject initialize=StockWire.object(StockWire.read(System.in,StockWire.MAX_REQUEST_BYTES));
        if("timeout".equals(mode)){Thread.sleep(60000);return;}
        if("eof".equals(mode))return;
        if("partial".equals(mode)){System.out.print("{\"jsonrpc\":");System.out.flush();return;}
        if("oversize".equals(mode)){for(int i=0;i<StockWire.MAX_FRAME_BYTES+1;i++)System.out.write('a');System.out.write('\n');System.out.flush();return;}
        if("malformed".equals(mode)){System.out.println("{\"id\":\"stock-1\",\"id\":\"stock-1\"}");return;}
        JSONObject result=new JSONObject().put("protocolVersion","version".equals(mode)?"1900-01-01":StockWire.VERSION)
                .put("capabilities",new JSONObject()).put("serverInfo",new JSONObject().put("name","fixture").put("version","1"));
        StockWire.write(System.out,new JSONObject().put("jsonrpc","2.0").put("id","id".equals(mode)?"wrong":initialize.get("id"))
                .put("result",result),StockWire.MAX_FRAME_BYTES);
        StockWire.object(StockWire.read(System.in,StockWire.MAX_REQUEST_BYTES));
        String line; int toolCalls=0;
        while((line=StockWire.read(System.in,StockWire.MAX_REQUEST_BYTES))!=null){
            JSONObject request=StockWire.object(line);
            if("tool-eof".equals(mode))return;
            if("tool-timeout".equals(mode)){Thread.sleep(60000);return;}
            String tool=request.getJSONObject("params").getString("name");
            toolCalls++;
            boolean close="control.close".equals(tool);
            if(close && "tool-error-close-eof".equals(mode))return;
            if(close && "tool-error-close-timeout".equals(mode)){Thread.sleep(60000);return;}
            boolean rejected=mode.startsWith("tool-error")&&!close;
            JSONObject data=rejected ? new JSONObject().put("reason","tool-error-unsafe".equals(mode)
                    ? "private diagnostic text must never be persisted" : "benchmark_foreground_out_of_scope")
                    :new JSONObject().put("fixture",true).put("cleanup_confirmed",close).put("tool_calls_seen",toolCalls);
            JSONObject response=new JSONObject().put("structuredContent",data).put("content",new JSONArray()
                    .put(new JSONObject().put("type","text").put("text",data.toString())));
            if(!"tool-shape".equals(mode))response.put("isError",rejected);
            StockWire.write(System.out,new JSONObject().put("jsonrpc","2.0").put("id",request.get("id")).put("result",response),StockWire.MAX_FRAME_BYTES);
            if(close)return;
        }
    }
}

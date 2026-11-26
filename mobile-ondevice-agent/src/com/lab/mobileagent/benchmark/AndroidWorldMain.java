package com.lab.mobileagent.benchmark;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import com.lab.mobileagent.l1.Decision;
import com.lab.mobileagent.l1.StrictJson;
import com.lab.mobileagent.runtime.AndroidRuntime;
import com.lab.mobileagent.l3.ComputerControl;
import com.lab.mobileagent.standard.StockControlClient;
import com.lab.mobileagent.model.Observation;
import com.lab.mobileagent.runtime.ControlErrors;
import com.lab.mobileagent.service.BenchmarkResources;
import com.lab.mobileagent.service.GuiMcpService;
import com.lab.mobileagent.telemetry.Trace;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Android-resident single-step agent. The host only supplies benchmark tasks and official scores. */
public final class AndroidWorldMain {
    public static final String ROOT="/data/local/tmp/mobile-ondevice-agent";
    private AndroidWorldMain() {}
    public static void main(String[] args){
        try{entry(args);}catch(Throwable e){System.err.println("androidworld_start_failed:"+e.getClass().getSimpleName());System.exit(2);}
    }
    private static void entry(String[] args)throws Exception{
        if(Process.myUid()!=2000||Build.VERSION.SDK_INT!=33
                ||!((args.length==1&&"launch".equals(args[0]))||(args.length==2&&"run".equals(args[0]))))
            throw new SecurityException("benchmark_entry_denied");
        String qemu=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.kernel.qemu");
        if(!"1".equals(qemu))throw new SecurityException("isolated_emulator_required");
        JSONObject config=BenchmarkContract.validate(readInput());
        if("run".equals(args[0])&&!config.getString("run_id").equals(args[1]))throw new SecurityException("benchmark_process_run_mismatch");
        File runs=new File(ROOT,"androidworld-runs"),dir=new File(runs,config.getString("run_id"));
        if("launch".equals(args[0])){
            if((!runs.isDirectory()&&!runs.mkdir())||!dir.mkdir())throw new IllegalStateException("benchmark_fresh_run_required");
            if(!dir.setReadable(false,false)||!dir.setWritable(false,false)||!dir.setExecutable(false,false)
                    ||!dir.setReadable(true,true)||!dir.setWritable(true,true)||!dir.setExecutable(true,true))
                throw new IllegalStateException("benchmark_private_directory_required");
            ProcessBuilder builder=new ProcessBuilder("/system/bin/nohup","/system/bin/app_process","/system/bin",AndroidWorldMain.class.getName(),"run",dir.getName());
            builder.environment().put("CLASSPATH",ROOT+"/"+BenchmarkContract.agentJar(config.getString("profile")));
            builder.redirectOutput(new File(dir,"process.log"));builder.redirectErrorStream(true);
            java.lang.Process child=builder.start();
            try(OutputStream out=child.getOutputStream()){out.write((config.toString()+"\n").getBytes(StandardCharsets.UTF_8));}
            System.out.println(new JSONObject().put("launched",true).put("run_id",dir.getName()).put("output",dir.getPath()).put("profile",config.getString("profile")));
            return;
        }
        if(!dir.isDirectory()||new File(dir,"process.json").exists())throw new IllegalStateException("benchmark_run_reuse_denied");
        final String key=config.getString("api_key");config.remove("api_key");
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        AndroidRuntime.initializeAndroidContextOnMainThread();
        write(dir,"process.json",new JSONObject().put("run_id",dir.getName()).put("pid",Process.myPid()).put("uid",Process.myUid())
                .put("execution_location","android_emulator").put("started_epoch_ms",System.currentTimeMillis())
                .put("formal_user_grants",false).put("config",config));
        Thread worker=new Thread(()->{
            int exit=2;
            try{new Loop(dir,config,key).run();exit=0;}
            catch(Throwable error){System.err.println("androidworld_agent_failed:"+error.getClass().getSimpleName());}
            System.exit(exit);
        },"androidworld-agent");
        new Handler(Looper.getMainLooper()).post(worker::start);Looper.loop();
    }
    private static JSONObject readInput()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int b;
        while((b=System.in.read())!=-1&&b!='\n'){if(bytes.size()>=32768)throw new IllegalArgumentException("benchmark_bootstrap_too_large");bytes.write(b);}
        return StrictJson.parseObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
    }
    static void write(File dir,String name,JSONObject value)throws Exception{
        File temp=new File(dir,"."+UUID.randomUUID()+".tmp");
        Files.write(temp.toPath(),(value.toString(2)+"\n").getBytes(StandardCharsets.UTF_8));
        if(!temp.renameTo(new File(dir,name)))throw new IllegalStateException("benchmark_evidence_publish_failed");
    }
    private static final class Loop {
        final File dir;final JSONObject config;final String key;
        final BenchmarkContract.TaskSpec task;
        final String run;final long started=System.nanoTime();final Trace trace=new Trace();
        final long deadline;final JSONArray steps=new JSONArray(),history=new JSONArray(),checks=new JSONArray();
        ComputerControl control;BenchmarkPlanner planner;GuiMcpService mcp;Observation observation;
        final boolean stock;
        int sequence=1,turns=0,calls=0,frames=0;long previousStepStart;JSONObject previousStep;
        double modelSeconds,controlSeconds,observationSeconds,hostWaitSeconds;
        boolean done,modelDone;String kind="running",answer="",reason="";JSONObject evaluation;JSONObject plannerIdentity=new JSONObject();
        Loop(File dir,JSONObject config,String key)throws Exception{this.dir=dir;this.config=config;this.key=key;task=BenchmarkContract.task(config.getString("task_name"));run=dir.getName();deadline=started+config.getInt("timeout_seconds")*1_000_000_000L;stock=BenchmarkContract.isStockProfile(config.getString("profile"));}
        void run()throws Exception{
            JSONObject failure=null;
            try{
                Context context=AndroidRuntime.executionContext();
                try(Trace.Scope setup=trace.span("setup","benchmark_agent_and_control")){
                    control=stock ? new StockControlClient(run,task,config.getString("goal"),dir,deadline)
                            : new AndroidWorldControl(context,task);
                    planner=(BenchmarkPlanner)Class.forName("com.lab.mobileagent.benchmark.AndroidWorldPlannerClient")
                            .getConstructor(Context.class,String.class,String.class,String.class,long.class,String.class,String.class,String.class,boolean.class)
                            .newInstance(context,key,config.getString("model"),config.getString("effort"),deadline,task.taskName,task.targetPackage,
                                    config.getString("responses_endpoint"),config.getBoolean("allow_http"));
                    plannerIdentity=planner.identity();
                    if(!stock) mcp=new GuiMcpService(BenchmarkResources.isolatedEmulator(run),(operation,action,phase)->{
                        if(System.nanoTime()>=deadline)throw new IllegalStateException("benchmark_deadline");
                        if(!"observe".equals(operation))BenchmarkContract.action(task,action);
                        checks.put(new JSONObject().put("operation",operation).put("phase",phase).put("decision","allow")
                                .put("authority","isolated_benchmark_operator_scope_not_user_grants"));
                    },(operation,action)->{
                        if(action==null)capture();
                        else applyControl(action);
                        return new JSONObject().put("status","control_returned").put("display_id",0).put("target_package",task.targetPackage);
                    },(operation,action)->{});
                }
                observeSession();
                for(;;){
                    state(done?"awaiting_evaluation":"ready");
                    JSONObject command=awaitCommand();
                    if("stop".equals(command.getString("command"))){reason="host_stopped";kind="stopped";break;}
                    if("evaluate".equals(command.getString("command"))){
                        if(turns<1||!task.taskName.equals(command.getString("task_name"))||command.getLong("seed")!=config.getLong("seed"))throw new IllegalArgumentException("benchmark_evaluation_scope_mismatch");
                        evaluation=command;reason="official_evaluation_received";break;
                    }
                    if(done)throw new IllegalStateException("benchmark_cannot_advance_after_done");
                    step();sequence++;
                }
            }catch(Exception error){
                Throwable cause=error instanceof java.lang.reflect.InvocationTargetException?error.getCause():error;
                reason=cause instanceof Exception?ControlErrors.safe((Exception)cause):"benchmark_initialization_failed";
                kind="error";failure=new JSONObject().put("code",reason).put("type",cause.getClass().getSimpleName());
            }
            finally{
                long closeStart=System.nanoTime();
                try(Trace.Scope cleanup=trace.span("cleanup","close_agent_session")){
                    if(mcp!=null)try{mcp.close();}catch(Exception e){failure=new JSONObject().put("code","benchmark_queue_cleanup_failed");}
                    if(planner!=null)try{planner.close();plannerIdentity=planner.identity();}catch(Exception e){failure=new JSONObject().put("code","benchmark_planner_cleanup_failed");}
                    if(control!=null)try{control.close();}catch(Exception e){failure=new JSONObject().put("code","benchmark_control_cleanup_unconfirmed");}
                }
                if(failure!=null&&stock){kind="error";if("official_evaluation_received".equals(reason))reason="benchmark_cleanup_failed";}
                double total=(System.nanoTime()-started)/1e9;
                if(previousStep!=null)previousStep.put("start_to_next_seconds",(System.nanoTime()-previousStepStart)/1e9)
                        .put("interval_end","agent_end_including_scorer_coordination");
                JSONObject result=base().put("state","finished").put("kind",kind).put("stop_reason",reason)
                        .put("model_answer",answer).put("agent_turns",turns).put("model_calls",calls)
                        .put("model_done",modelDone).put("budget_exhausted",turns>=config.getInt("max_steps")&&!modelDone)
                        .put("official_evaluation_received",evaluation!=null)
                        .put("official_raw_score",evaluation==null?JSONObject.NULL:evaluation.get("score"))
                        .put("agent_score",evaluation==null?JSONObject.NULL:BenchmarkContract.agentScore(evaluation.getDouble("score"),modelDone))
                        .put("task_success",evaluation==null?JSONObject.NULL:BenchmarkContract.agentScore(evaluation.getDouble("score"),modelDone)>0.5)
                        .put("score_definition","official raw task state; agent score is raw only if model ended episode, otherwise zero")
                        .put("evaluator",evaluation==null?JSONObject.NULL:evaluation.getString("evaluator"))
                        .put("failure",failure==null?JSONObject.NULL:failure).put("planner",plannerIdentity)
                        .put("control",control==null?JSONObject.NULL:controlReport()).put("mcp",mcpReport())
                        .put("timing",new JSONObject().put("total_wall_seconds",total).put("model_seconds",modelSeconds)
                            .put("control_seconds",controlSeconds).put("observation_seconds",observationSeconds)
                            .put("host_coordination_wait_seconds",hostWaitSeconds)
                            .put("other_seconds",Math.max(0,total-modelSeconds-controlSeconds-observationSeconds-hostWaitSeconds))
                            .put("cleanup_seconds_in_other",(System.nanoTime()-closeStart)/1e9)
                            .put("definition",stock
                                ? "exclusive parent wall buckets; model includes IPC/network/inference; control and observation include MCP IPC; child stage times overlap these buckets; total includes host step/scorer coordination; CPU is separate"
                                : "exclusive wall buckets; model includes IPC/network/inference; total includes host step/scorer coordination; CPU is separate"));
                write(dir,"timing.json",trace.report());
                write(dir,"step-timing.json",base().put("steps",steps).put("definition","start-to-next includes host benchmark coordination; final interval includes evaluator wait"));
                JSONObject taskSession=base().put("state","closed").put("display_id",0).put("formal_user_grants",false)
                        .put("target_package",task.targetPackage).put("goal",config.getString("goal")).put("steps",steps).put("scope_checks",checks);
                if(stock){
                    JSONObject receipt=control==null?new JSONObject():controlReport();
                    boolean clean=receipt.optBoolean("child_exit_confirmed",false)&&receipt.optBoolean("cleanup_confirmed",false);
                    taskSession.put("state",clean?"closed":"cleanup_unconfirmed")
                            .put("control_connection",receipt).put("scope_checks_location","control_service_process")
                            .put("control_events_file","stock-control-events.jsonl")
                            .put("control_lifecycle_file","stock-control-lifecycle.json");
                }
                write(dir,"task-session.json",taskSession);
                write(dir,"result.json",result);
                write(dir,String.format(java.util.Locale.ROOT,"step-%04d.json",sequence),base().put("sequence",sequence).put("done",true).put("state","finished").put("result_file","result.json"));
                state("finished");
            }
        }
        void capture()throws Exception{
            long began=System.nanoTime();
            try(Trace.Scope span=trace.span("observation","capture_and_save")){
                observation=control.observe();String name=String.format(java.util.Locale.ROOT,"screen-%03d.png",frames++);
                Files.write(new File(dir,name).toPath(),observation.png);
            }finally{observationSeconds+=(System.nanoTime()-began)/1e9;}
        }
        JSONObject awaitCommand()throws Exception{
            long begin=System.nanoTime();
            try(Trace.Scope span=trace.span("host_coordination","wait_command_"+sequence)){
                File path=new File(dir,String.format(java.util.Locale.ROOT,"command-%04d.json",sequence));
                while(!path.isFile()){
                    if(System.nanoTime()>=deadline)throw new IllegalStateException("benchmark_command_timeout");
                    Thread.sleep(100);
                }
                if(path.length()>8192)throw new IllegalArgumentException("benchmark_command_too_large");
                return BenchmarkContract.command(StrictJson.parseObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8)),run,sequence,task);
            }finally{hostWaitSeconds+=(System.nanoTime()-begin)/1e9;}
        }
        void step()throws Exception{
            long begin=System.nanoTime();
            if(previousStep!=null)previousStep.put("start_to_next_seconds",(begin-previousStepStart)/1e9).put("interval_end","next_agent_step_start");
            double priorModel=modelSeconds,priorControl=controlSeconds,priorObservation=observationSeconds;
            turns++;state("running_step");
            // Refresh on Android before every model call. PC never sends the observation or proposes a GUI action.
            observeSession();
            Decision decision;long modelStart=System.nanoTime();
            try(Trace.Scope span=trace.span("model","turn_"+turns)){calls++;decision=planner.decide(config.getString("goal"),observation,history);}
            finally{modelSeconds+=(System.nanoTime()-modelStart)/1e9;}
            if("action".equals(decision.kind)){
                BenchmarkContract.action(task,decision.action);
                if(stock) applyControl(decision.action); else mcp.clientCall(decision.action);
                observeSession();
                history.put(new JSONObject().put("turn",turns).put("action",decision.action.json()).put("status","dispatched"));
            }else{done=true;modelDone=BenchmarkContract.modelDone(decision.kind);kind=decision.kind;answer=decision.answer;}
            if(turns>=config.getInt("max_steps")){done=true;if("running".equals(kind))kind="step_limit";}
            JSONObject row=base().put("sequence",sequence).put("turn",turns).put("done",modelDone).put("kind",decision.kind)
                    .put("awaiting_evaluation",done).put("budget_exhausted",turns>=config.getInt("max_steps")&&!modelDone)
                    .put("state",done?"awaiting_evaluation":"ready").put("model_calls",calls)
                    .put("action",decision.action==null?JSONObject.NULL:decision.action.json())
                    .put("answer",decision.answer==null?JSONObject.NULL:decision.answer)
                    .put("observation_file",String.format(java.util.Locale.ROOT,"screen-%03d.png",frames-1))
                    .put("execution_wall_seconds",(System.nanoTime()-begin)/1e9)
                    .put("model_seconds",modelSeconds-priorModel).put("control_seconds",controlSeconds-priorControl)
                    .put("observation_seconds",observationSeconds-priorObservation);
            steps.put(row);previousStep=row;previousStepStart=begin;
            write(dir,String.format(java.util.Locale.ROOT,"step-%04d.json",sequence),row);
        }
        void observeSession()throws Exception{if(stock)capture();else mcp.clientObserve();}
        void applyControl(com.lab.mobileagent.model.Action action)throws Exception{
            long began=System.nanoTime();
            try(Trace.Scope span=trace.span("control",action.name)){control.execute(action);}
            finally{controlSeconds+=(System.nanoTime()-began)/1e9;}
        }
        JSONObject controlReport()throws Exception{
            return stock ? ((StockControlClient)control).report() : ((AndroidWorldControl)control).report();
        }
        Object mcpReport()throws Exception{
            if(stock && control!=null)return ((StockControlClient)control).report();
            return mcp==null?JSONObject.NULL:mcp.report();
        }
        JSONObject base()throws Exception{return new JSONObject().put("run_id",run).put("profile",config.getString("profile"))
                .put("task_name",task.taskName).put("target_package",task.targetPackage).put("task_catalog_version",BenchmarkContract.CATALOG_VERSION)
                .put("seed",config.getLong("seed")).put("formal_user_grants",false);}
        void state(String value)throws Exception{write(dir,"state.json",base().put("state",value).put("expected_sequence",sequence)
                .put("done",modelDone).put("awaiting_evaluation",done).put("turns",turns).put("model_calls",calls));}
    }
}

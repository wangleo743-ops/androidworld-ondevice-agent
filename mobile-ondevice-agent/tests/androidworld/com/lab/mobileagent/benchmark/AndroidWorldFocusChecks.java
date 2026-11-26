package com.lab.mobileagent.benchmark;

/** API 33 display dumps are fixtures; no Android process or model is used. */
public final class AndroidWorldFocusChecks {
    private static int checks;
    private static final String SETTINGS="com.android.settings/.SubSettings";
    private static final String NULL=display(0,"null");
    private static String display(int id,String focus){return "  Display: mDisplayId="+id+" rootTasks=2\n    init=1080x2400 420dpi\n  mCurrentFocus="+focus+"\n  mFocusedApp=ActivityRecord{fixture}\n";}
    private static String window(String component){return "Window{123abc u0 "+component+"}";}
    private static void matches(String dump,String expected){String[] actual=AndroidWorldFocus.primary(dump);if(!expected.equals(actual[1])||!expected.substring(0,expected.indexOf('/')).equals(actual[0]))throw new AssertionError("wrong_primary_focus");checks++;}
    private static void deny(String dump){try{AndroidWorldFocus.primary(dump);}catch(SecurityException expected){checks++;return;}throw new AssertionError("unexpected_focus_allow");}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private static final class Fixture implements AndroidWorldFocus.Source,AndroidWorldFocus.Clock,AndroidWorldFocus.Pause,AndroidWorldFocus.Scope {
        final String[] dumps;long now,readCost,previousBudget=Long.MAX_VALUE,slept;int reads,pauses,scopeChecks;boolean sourceFailure;
        Fixture(String...dumps){this.dumps=dumps;}
        public long nanos(){return now;}
        public String read(long remainingMillis)throws Exception{
            if(remainingMillis>previousBudget||remainingMillis<1||remainingMillis>2000)throw new AssertionError("invalid_remaining_budget");
            previousBudget=remainingMillis;reads++;now+=readCost;
            if(sourceFailure)throw new java.io.IOException("fixture_transport_failure");
            return dumps[Math.min(reads-1,dumps.length-1)];
        }
        public void sleep(long millis){pauses++;slept+=millis;now+=millis*1_000_000L;}
        public void require(String[] current){scopeChecks++;if(!"com.android.settings".equals(current[0]))throw new SecurityException("fixture_out_of_scope");}
        String[] run()throws Exception{return AndroidWorldFocus.awaitPrimary(this,this,this,this,2000,50);}
    }
    private static void rejectWait(Fixture fixture,String reason)throws Exception{
        try{fixture.run();}catch(SecurityException e){check(reason.equals(e.getMessage()),"unexpected_rejection:"+e.getMessage());return;}
        throw new AssertionError("unexpected_wait_allow");
    }
    private static void malformedWithoutRetry(String dump)throws Exception{
        Fixture fixture=new Fixture(dump,display(0,window(SETTINGS)));
        try{fixture.run();}catch(SecurityException e){check(fixture.reads==1&&fixture.pauses==0&&fixture.scopeChecks==0,"malformed_state_was_retried");return;}
        throw new AssertionError("malformed_state_allowed");
    }
    public static void main(String[] ignored)throws Exception{
        String clock="com.google.android.deskclock/com.android.deskclock.DeskClock";
        String home="com.android.launcher3/.uioverrides.QuickstepLauncher";
        matches("WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n"+display(0,window(clock)),clock);
        matches(display(0,window(home)).replace("\n","\r\n"),home);
        matches(display(4,window("com.example.other/.Main"))+display(0,window(clock)),clock);
        matches(display(0,window(clock))+display(4,window("com.example.other/.Main")),clock);
        deny(display(4,window(clock)));
        deny("  mCurrentFocus="+window(clock));
        deny(display(0,"null"));
        deny(display(0,window(clock).replace(" u0 "," u10 ")));
        deny(display(0,window(clock))+"  mCurrentFocus="+window(home));
        deny(display(0,window(clock))+display(0,window(clock)));
        deny("  Display: mDisplayId=0 rootTasks=1\n  mFocusedApp=ActivityRecord{fixture}");
        Fixture stable=new Fixture(display(0,window(SETTINGS)));
        check(SETTINGS.equals(stable.run()[1])&&stable.reads==1&&stable.pauses==0&&stable.scopeChecks==1,"stable_focus_changed");
        Fixture transition=new Fixture(NULL,NULL,display(0,window(SETTINGS)));transition.readCost=20_000_000L;
        check(SETTINGS.equals(transition.run()[1]),"transition_did_not_finish");
        check(transition.reads==3&&transition.pauses==2&&transition.slept==100&&transition.scopeChecks==1,"transition_retry_behavior");
        check(transition.now==160_000_000L&&transition.previousBudget==1860,"read_and_pause_budget_not_shared");
        Fixture perpetual=new Fixture(NULL);
        rejectWait(perpetual,"benchmark_focus_transition_timeout");
        check(perpetual.now==2_000_000_000L&&perpetual.reads==40&&perpetual.pauses==40&&perpetual.scopeChecks==0,"unbounded_null_wait");
        Fixture slow=new Fixture(NULL);slow.readCost=1_975_000_000L;
        rejectWait(slow,"benchmark_focus_transition_timeout");
        check(slow.reads==1&&slow.slept==25&&slow.now==2_000_000_000L,"slow_read_did_not_reduce_wait_budget");
        Fixture late=new Fixture(display(0,window(SETTINGS)));late.readCost=2_000_000_000L;
        rejectWait(late,"benchmark_focus_transition_timeout");
        check(late.reads==1&&late.pauses==0,"late_result_retried");
        Fixture outside=new Fixture(display(0,window("com.example.other/.Main")),display(0,window(SETTINGS)));
        rejectWait(outside,"fixture_out_of_scope");
        check(outside.reads==1&&outside.pauses==0,"valid_foreign_package_was_retried");
        Fixture nullThenOutside=new Fixture(NULL,display(0,window("com.example.other/.Main")),display(0,window(SETTINGS)));
        rejectWait(nullThenOutside,"fixture_out_of_scope");
        check(nullThenOutside.reads==2&&nullThenOutside.pauses==1,"foreign_package_after_transition_was_retried");
        malformedWithoutRetry(display(0,window(SETTINGS).replace(" u0 "," u10 ")));
        malformedWithoutRetry(display(4,"null"));
        malformedWithoutRetry("  mCurrentFocus=null");
        malformedWithoutRetry("  Display: mDisplayId=0 rootTasks=1\n  mFocusedApp=ActivityRecord{fixture}");
        malformedWithoutRetry(NULL+"  mCurrentFocus=null\n");
        malformedWithoutRetry(NULL+display(0,window(SETTINGS)));
        malformedWithoutRetry(display(0,"null "));
        malformedWithoutRetry(display(0,"Window{broken}"));
        malformedWithoutRetry(NULL+"  Display: mDisplayId=broken\n");
        malformedWithoutRetry(null);
        Fixture secondary=new Fixture(display(4,"null")+display(0,window(SETTINGS)));
        check(SETTINGS.equals(secondary.run()[1])&&secondary.reads==1&&secondary.pauses==0,"secondary_null_affected_primary");
        Fixture ioFailure=new Fixture(NULL);ioFailure.sourceFailure=true;
        try{ioFailure.run();throw new AssertionError("transport_failure_hidden");}catch(java.io.IOException expected){check(ioFailure.reads==1&&ioFailure.pauses==0,"transport_error_was_retried");}
        Fixture invalidBudget=new Fixture(NULL);
        try{AndroidWorldFocus.awaitPrimary(invalidBudget,invalidBudget,invalidBudget,invalidBudget,2001,50);throw new AssertionError("excessive_budget_allowed");}
        catch(IllegalArgumentException expected){check(invalidBudget.reads==0&&invalidBudget.pauses==0,"invalid_budget_started_read");}
        System.out.println("{\"passed\":"+checks+",\"model_calls\":0,\"device_actions\":0,\"benchmark_executed\":false}");
    }
}

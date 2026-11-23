package com.lab.mobileagent.benchmark;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for Android 13's dumpsys window displays; only user 0 on display 0. */
final class AndroidWorldFocus {
    private static final Pattern DISPLAY=Pattern.compile("^\\s*Display: mDisplayId=([0-9]+)\\b.*$");
    private static final Pattern FOCUS=Pattern.compile("^\\s*mCurrentFocus=(.*)$");
    private static final Pattern WINDOW=Pattern.compile("Window\\{[^\\s{}]+ u0 ([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\\}");
    private AndroidWorldFocus() {}

    static String[] primary(String dump) {
        String[] result=sample(dump);
        if(result==null)throw new SecurityException("benchmark_foreground_unavailable");
        return result;
    }

    /** Null means exactly one complete primary-display focus field explicitly says null.
     * Missing fields, other users, duplicates and malformed windows are never retryable. */
    private static String[] sample(String dump) {
        if(dump==null||dump.length()>2*1024*1024)throw new SecurityException("benchmark_invalid_display_dump");
        boolean inPrimary=false,explicitNull=false;int primaryCount=0,focusCount=0;String[] result=null;
        for(String line:dump.split("\\r?\\n")){
            Matcher display=DISPLAY.matcher(line);
            if(display.matches()){
                inPrimary="0".equals(display.group(1));
                if(inPrimary&&++primaryCount!=1)throw new SecurityException("benchmark_ambiguous_primary_display");
                continue;
            }
            if(line.trim().startsWith("Display:"))throw new SecurityException("benchmark_invalid_display_dump");
            if(!inPrimary)continue;
            Matcher focus=FOCUS.matcher(line);
            if(!focus.matches())continue;
            if(++focusCount!=1)throw new SecurityException("benchmark_ambiguous_foreground");
            if("null".equals(focus.group(1))){explicitNull=true;continue;}
            Matcher window=WINDOW.matcher(focus.group(1));
            if(!window.matches())throw new SecurityException("benchmark_foreground_unavailable");
            result=new String[]{window.group(1),window.group(1)+"/"+window.group(2)};
        }
        if(primaryCount!=1||focusCount!=1||(!explicitNull&&result==null))
            throw new SecurityException("benchmark_primary_foreground_unavailable");
        return result;
    }

    interface Source { String read(long remainingMillis)throws Exception; }
    interface Scope { void require(String[] foreground)throws Exception; }
    interface Clock { long nanos(); }
    interface Pause { void sleep(long millis)throws Exception; }

    /** Re-observes only; never retries an input. All reads and pauses share one bounded budget. */
    static String[] awaitPrimary(Source source,Scope scope,Clock clock,Pause pause,long timeoutMillis,long pollMillis)throws Exception{
        if(source==null||scope==null||clock==null||pause==null||timeoutMillis<1||timeoutMillis>2000
                ||pollMillis<1||pollMillis>timeoutMillis)throw new IllegalArgumentException("invalid_focus_wait_budget");
        long start=clock.nanos(),deadline=start+timeoutMillis*1_000_000L;
        for(;;){
            long remaining=deadline-clock.nanos();
            if(remaining<1_000_000L)throw new SecurityException("benchmark_focus_transition_timeout");
            String[] current=sample(source.read(remaining/1_000_000L));
            // Apply scope checks immediately. A valid wrong target is not a transition to wait through.
            if(current!=null){
                scope.require(current);
                if(clock.nanos()>=deadline)throw new SecurityException("benchmark_focus_transition_timeout");
                return current;
            }
            remaining=deadline-clock.nanos();
            if(remaining<=0)throw new SecurityException("benchmark_focus_transition_timeout");
            long sleep=Math.min(pollMillis,remaining/1_000_000L);
            if(sleep<1)throw new SecurityException("benchmark_focus_transition_timeout");
            pause.sleep(sleep);
        }
    }
}

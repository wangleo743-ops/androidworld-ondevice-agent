package com.lab.mobileagent.platform;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;

/** Capability negotiation must describe real backend support, not reserved interface names. */
public final class PlatformCapabilities {
    public static final String PIXELS="pixels", INPUT="input", SESSION="task_session", LOG="execution_log";
    public static final String DISPLAY_LIST="display_list", UPDATE="on_demand_update", SECOND_UI="second_ui_coordination";
    public static final String AUTHORIZATION="display_list_operation_authorization", RESOURCES="system_resource_scheduling";
    public static final String RECONSTRUCTION="framework_ui_reconstruction", SYSTEM_SERVICE="privileged_system_service";
    private static final String[] ALL={PIXELS,INPUT,SESSION,LOG,DISPLAY_LIST,UPDATE,SECOND_UI,AUTHORIZATION,RESOURCES,RECONSTRUCTION,SYSTEM_SERVICE};
    private final Set<String> supported;
    public final String backend;
    private PlatformCapabilities(String backend, String... supported) {
        this.backend=backend;this.supported=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(supported)));
    }
    public static PlatformCapabilities stockAndroid() {
        return new PlatformCapabilities("stock_android_shell_baseline",PIXELS,INPUT,SESSION,LOG);
    }
    public boolean supports(String feature){return supported.contains(feature);}
    public void require(String feature){if(!supports(feature))throw new UnsupportedOperationException("capability_unavailable:"+feature);}
    public JSONObject json()throws Exception{
        JSONObject flags=new JSONObject(),reserved=new JSONObject();
        for(String feature:ALL){flags.put(feature,supports(feature));if(!supports(feature))reserved.put(feature,"interface_reserved_not_implemented");}
        return new JSONObject().put("backend",backend).put("supported",flags).put("reserved",reserved)
                .put("formal_user_grants",false).put("os_privilege_isolation",false)
                .put("scope","dedicated_emulator_primary_display");
    }
}

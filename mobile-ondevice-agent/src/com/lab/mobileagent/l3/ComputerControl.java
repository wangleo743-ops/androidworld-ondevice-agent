package com.lab.mobileagent.l3;

import com.lab.mobileagent.model.Action;
import com.lab.mobileagent.model.Observation;

/** L3 contract. No ADB, SSH, credential, or model API is exposed here. */
public interface ComputerControl extends AutoCloseable {
    // BaselineSession owns task binding and logs; the device adapter exposes only GUI capabilities.
    int displayId();
    Observation observe() throws Exception;
    void execute(Action action) throws Exception;
    void close() throws Exception;
}

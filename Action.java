package com.lab.mobileagent.model;

import org.json.JSONObject;

/** One proposed GUI operation, never an arbitrary shell command. */
public final class Action {
    public final String name;
    public final JSONObject args;
    public Action(String name, JSONObject args) throws Exception {
        this.name = name;
        this.args = new JSONObject(args.toString());
    }
    public JSONObject json() throws Exception {
        return new JSONObject().put("name", name).put("args", new JSONObject(args.toString()));
    }
}

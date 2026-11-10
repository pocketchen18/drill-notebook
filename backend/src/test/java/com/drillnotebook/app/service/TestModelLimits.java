package com.drillnotebook.app.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit capacities for simulated providers; never used by production configuration. */
public final class TestModelLimits {
    private TestModelLimits() { }
    public static Map<String, Object> config(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return config(values);
    }
    public static Map<String, Object> config(Map<String, Object> values) {
        var result = new LinkedHashMap<String, Object>();
        result.put("contextWindow", 128000); result.put("maxOutputTokens", 32000);
        result.putAll(values);
        return result;
    }
}

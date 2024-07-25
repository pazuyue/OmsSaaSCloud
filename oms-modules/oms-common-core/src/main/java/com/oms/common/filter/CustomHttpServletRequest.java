package com.oms.common.filter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class CustomHttpServletRequest extends HttpServletRequestWrapper {
    private final Map<String, String[]> modifiableParameters;

    public CustomHttpServletRequest(HttpServletRequest request) {
        super(request);
        modifiableParameters = new HashMap<>();
        modifiableParameters.putAll(request.getParameterMap());
    }

    @Override
    public String getParameter(String name) {
        if (modifiableParameters.containsKey(name)) {
            return modifiableParameters.get(name)[0];
        }
        return null;
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(modifiableParameters);
    }

    @Override
    public String[] getParameterValues(String name) {
        return modifiableParameters.get(name);
    }

    public void addParameter(String name, String value) {
        modifiableParameters.put(name, new String[]{value});
    }
}

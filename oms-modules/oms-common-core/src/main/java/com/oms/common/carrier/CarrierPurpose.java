package com.oms.common.carrier;

import java.util.*;

/** Business action determines conversion direction; callers cannot borrow another action's codes. */
public final class CarrierPurpose {
    private CarrierPurpose() {}
    private static final Map<String,String> DIRECTIONS;
    static {
        Map<String,String> values=new LinkedHashMap<>();
        values.put("CHANNEL_SHIPPING","OUT");
        values.put("CHANNEL_EXCHANGE_LOGISTICS","OUT");
        values.put("CHANNEL_RETURN_IDENTIFY","IN");
        values.put("CHANNEL_RETURN_BACKFILL","OUT");
        values.put("WMS_OUTBOUND_CREATE","OUT");
        values.put("WMS_OUTBOUND_ACTUAL","IN");
        values.put("WMS_RETURN_CREATE","OUT");
        values.put("WMS_RETURN_ACTUAL","IN");
        DIRECTIONS=Collections.unmodifiableMap(values);
    }
    public static List<String> purposes(){return new ArrayList<>(DIRECTIONS.keySet());}
    public static String direction(String purpose){return DIRECTIONS.getOrDefault(purpose,"");}
}

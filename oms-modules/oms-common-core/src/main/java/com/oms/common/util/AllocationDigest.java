package com.oms.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;

/** Stable fingerprints detect changed rules or a changed allocation plan before occupation. */
public final class AllocationDigest {
    private static final ObjectMapper JSON=new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private AllocationDigest(){}
    public static String hash(Object value){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value));StringBuilder out=new StringBuilder(64);for(byte b:bytes)out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));return out.toString();}catch(Exception e){throw new IllegalArgumentException("分仓预览指纹无法计算");}}
}

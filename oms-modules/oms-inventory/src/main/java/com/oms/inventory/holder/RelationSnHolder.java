package com.oms.inventory.holder;

public class RelationSnHolder {
    private static final ThreadLocal<String> holder = new ThreadLocal<>();

    public static void set(String relationSn) {
        holder.set(relationSn);
    }

    public static String get() {
        return holder.get();
    }

    public static void remove() {
        holder.remove();
    }
}

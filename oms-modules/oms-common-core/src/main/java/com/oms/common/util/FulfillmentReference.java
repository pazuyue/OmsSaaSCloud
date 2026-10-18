package com.oms.common.util;
/** Internal exchange demand identities cannot collide with a real platform transaction number. */
public final class FulfillmentReference {
    private FulfillmentReference(){}
    public static boolean valid(String value){return value!=null&&value.matches("(?:[0-9]{1,64}|EXCHANGE:[1-9][0-9]{0,18}(?::[1-9][0-9]{0,8})?)");}
}

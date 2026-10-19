package com.oms.common.util;
import java.util.Map;
/** Platform identity stays intact; one sales line can have several component SKUs. */
public final class FulfillmentLineKey {
 private FulfillmentLineKey(){}
 public static String of(String oid,String sku){return oid.length()+":"+oid+sku;}
 public static String of(Map<String,Object> row){return of(String.valueOf(row.get("platform_oid")),String.valueOf(row.get("sku_sn")));}
}

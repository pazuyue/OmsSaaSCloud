package com.oms.order.service;

import java.util.*;
import static com.oms.order.service.OrderValues.*;

/** Shared exact identities and platform modification time; values always remain SQL parameters. */
final class PlatformDocumentQuery {
    private PlatformDocumentQuery() {}
    static String value(Map<String,String> q,String key){String value=q.get(key);return value==null?"":value.trim();}
    static String number(Map<String,String> q,String key){String n=value(q,key);require(n.isEmpty()||n.matches("[1-9][0-9]{0,63}"),"请填写完整平台单号");return n;}
    static Long channel(Map<String,String> q){String n=value(q,"channelId");require(n.isEmpty()||n.matches("[1-9][0-9]{0,17}"),"店铺无效");return n.isEmpty()?null:Long.valueOf(n);}
    static int page(Map<String,String> q,String key,int fallback,int max){try{String n=value(q,key);int v=n.isEmpty()?fallback:Integer.parseInt(n);require(v>=1&&v<=max,"请缩小筛选范围或调整分页");return v;}catch(NumberFormatException e){throw new IllegalArgumentException("分页无效");}}
    static String append(String where,List<Object> args,Map<String,String> q,boolean order){
        String tid=number(q,"platformTid"),oid=number(q,"platformOid"),from=value(q,"modifiedFrom"),to=value(q,"modifiedTo");
        if(!tid.isEmpty()){where+=" AND p.platform_tid=?";args.add(tid);}
        if(!oid.isEmpty()){where+=order?" AND EXISTS(SELECT 1 FROM oms_platform_order_line pl WHERE pl.company_code=p.company_code AND pl.platform_order_id=p.id AND pl.platform_oid=?)":" AND p.platform_oid=?";args.add(oid);}
        if(!from.isEmpty()){where+=" AND p.source_modified>=?";args.add(time(from).format(TIME));}
        if(!to.isEmpty()){where+=" AND p.source_modified<=?";args.add(time(to).format(TIME));}
        require(from.isEmpty()||to.isEmpty()||!time(from).isAfter(time(to)),"修改时间开始不能晚于结束");return where;
    }
}

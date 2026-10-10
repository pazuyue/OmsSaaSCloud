package com.oms.supplychain.service.wms;

import java.util.*;

/** Each provider owns wire formatting, signing and response interpretation. */
public interface WmsProtocol {
    String provider();
    Wire request(Map<String,Object> connection,String secret,String action,Map<String,Object> task,Map<String,Object> ticket,List<Map<String,Object>> lines);
    Reply reply(String action,String body);
    Receipt callback(Map<String,Object> connection,String secret,Map<String,String> parameters,String body);
    String acknowledgement(boolean success,String message);
    String contentType();
    class Wire { public String url,body,contentType; public Wire(String u,String b,String c){url=u;body=b;contentType=c;} }
    class Reply { public boolean success;public String externalOrder="",status="",message=""; }
    class Receipt {
        public String ticketSn,externalOrder,messageId,warehouse,owner,status;
        public boolean complete;
        public Integer totalLines;
        public int sourceLineCount;
        public List<Line> lines=new ArrayList<>();
    }
    class Line { public String sku,batch,owner;public int good,bad; }
}

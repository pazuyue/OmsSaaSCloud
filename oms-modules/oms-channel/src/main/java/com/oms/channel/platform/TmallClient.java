package com.oms.channel.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Official TOP/OAuth protocol. Endpoints are fixed; credentials never enter URLs or logs. */
@Component
public class TmallClient {
    public static class Failure extends RuntimeException {
        public final String code; public final boolean unknown;
        public Failure(String code, boolean unknown) { super(unknown?"平台响应未确认，请查看交互日志后重试":"平台调用失败，请查看交互日志中的错误码");this.code=code;this.unknown=unknown; }
    }
    public JsonNode token(Map<String,String> params) { return post("https://oauth.taobao.com/token",params); }
    public JsonNode api(String key,String secret,String token,String method,Map<String,String> args) {
        Map<String,String> params=new TreeMap<>(args);
        params.put("method",method);params.put("app_key",key);params.put("format","json");params.put("v","2.0");params.put("sign_method","md5");
        params.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        if(token!=null)params.put("session",token);
        params.put("sign",sign(params,secret));
        return post("https://eco.taobao.com/router/rest",params);
    }
    public static String sign(Map<String,String> params,String secret) {
        StringBuilder input=new StringBuilder(secret);
        new TreeMap<>(params).forEach((k,v)->{if(!"sign".equals(k)&&v!=null&&!v.isEmpty())input.append(k).append(v);});input.append(secret);
        return digest("MD5",input.toString()).toUpperCase(Locale.ROOT);
    }
    public static String digest(String algorithm,String value) {
        try { StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)))out.append(String.format("%02x",b&255));return out.toString(); }
        catch(Exception e){throw new IllegalStateException("摘要算法不可用");}
    }
    public static String encode(String value) { try{return URLEncoder.encode(value,"UTF-8");}catch(Exception e){throw new IllegalStateException(e);} }
    protected JsonNode post(String url,Map<String,String> params) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(url).openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);connection.setReadTimeout(15000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded;charset=UTF-8");
            StringJoiner body=new StringJoiner("&");params.forEach((k,v)->body.add(encode(k)+"="+encode(v)));
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            int status=connection.getResponseCode();
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)throw new Failure("HTTP_"+status,status>=500);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(buffer.size()+n>1048576)throw new Failure("RESPONSE_TOO_LARGE",true);buffer.write(block,0,n);}}
            JsonNode result=new ObjectMapper().readTree(buffer.toByteArray());
            if(result==null||!result.isObject())throw new Failure("INVALID_RESPONSE",true);
            JsonNode error=result.has("error_response")?result.get("error_response"):result;
            if(result.has("error_response")||result.has("error")) {
                String code=error.path("sub_code").asText(error.path("code").asText(error.path("error").asText("PLATFORM_ERROR")));
                // Error descriptions may echo a credential. Persist only a bounded error identifier.
                throw new Failure(code.matches("[A-Za-z0-9_.-]{1,100}")?code:"PLATFORM_ERROR",false);
            }
            if(status<200||status>=300)throw new Failure("HTTP_"+status,status>=500);
            return result;
        } catch(Failure e){throw e;} catch(Exception e){throw new Failure("TRANSPORT_OR_RESPONSE_ERROR",true);}
        finally {if(connection!=null)connection.disconnect();}
    }
}

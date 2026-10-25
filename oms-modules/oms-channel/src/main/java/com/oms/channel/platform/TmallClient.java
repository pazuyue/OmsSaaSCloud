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
    static final class Transport {Integer httpStatus;Object response;boolean sent;Set<String> forbidden=new HashSet<>();}
    private static final ThreadLocal<Transport> TRANSPORT=ThreadLocal.withInitial(Transport::new);
    static Transport transport(){return TRANSPORT.get();}
    static void clearTransport(){TRANSPORT.remove();}
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
    public JsonNode apiFiles(String key,String secret,String token,String method,Map<String,String> args,Map<String,byte[]> files){
        Map<String,String> params=new TreeMap<>(args);params.put("method",method);params.put("app_key",key);params.put("format","json");params.put("v","2.0");params.put("sign_method","md5");params.put("timestamp",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));if(token!=null)params.put("session",token);params.put("sign",sign(params,secret));return post("https://eco.taobao.com/router/rest",params,files);
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
    protected JsonNode post(String url,Map<String,String> params) {return post(url,params,Collections.emptyMap());}
    protected JsonNode post(String url,Map<String,String> params,Map<String,byte[]> files) {
        for(String field:Arrays.asList("session","client_secret","refresh_token","code","sign")){String value=params.get(field);if(value!=null&&value.length()>=4)transport().forbidden.add(value);}
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(url).openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);connection.setReadTimeout(15000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            String boundary="OMS"+UUID.randomUUID().toString().replace("-","");connection.setRequestProperty("Content-Type",files.isEmpty()?"application/x-www-form-urlencoded;charset=UTF-8":"multipart/form-data; boundary="+boundary);
            StringJoiner body=new StringJoiner("&");params.forEach((k,v)->body.add(encode(k)+"="+encode(v)));
            byte[] bytes=files.isEmpty()?body.toString().getBytes(StandardCharsets.UTF_8):multipart(params,files,boundary);connection.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=connection.getOutputStream()){transport().sent=true;out.write(bytes);}
            int status=connection.getResponseCode();transport().httpStatus=status;
            InputStream stream=status>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)throw new Failure("HTTP_"+status,status>=500);
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            try(InputStream in=stream){byte[] block=new byte[4096];int n;while((n=in.read(block))!=-1){if(buffer.size()+n>1048576)throw new Failure("RESPONSE_TOO_LARGE",true);buffer.write(block,0,n);}}
            JsonNode result=new ObjectMapper().readTree(buffer.toByteArray());transport().response=result;
            if(result==null||!result.isObject())throw new Failure("INVALID_RESPONSE",true);
            JsonNode error=result.has("error_response")?result.get("error_response"):result;
            if(result.has("error_response")||result.has("error")) {
                String code=error.path("sub_code").asText(error.path("code").asText(error.path("error").asText("PLATFORM_ERROR")));
                // Error descriptions may echo a credential. Persist only a bounded error identifier.
                throw new Failure(code.matches("[A-Za-z0-9_.-]{1,100}")?code:"PLATFORM_ERROR",status>=500||code.startsWith("isp."));
            }
            if(status<200||status>=300)throw new Failure("HTTP_"+status,status>=500);
            return result;
        } catch(Failure e){throw e;} catch(Exception e){throw new Failure("TRANSPORT_OR_RESPONSE_ERROR",true);}
        finally {if(connection!=null)connection.disconnect();}
    }
    static byte[] multipart(Map<String,String> params,Map<String,byte[]> files,String boundary){
        if(!boundary.matches("[A-Za-z0-9]{1,80}"))throw new IllegalArgumentException("上传边界无效");
        try{ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(Map.Entry<String,String> e:params.entrySet()){if(!e.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))throw new IllegalArgumentException("上传字段无效");out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"\r\n\r\n"+e.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));}
            for(Map.Entry<String,byte[]> e:files.entrySet()){if(!e.getKey().matches("[a-z_]{1,64}")||e.getValue()==null||e.getValue().length>512000)throw new IllegalArgumentException("凭证图片大小或字段无效");byte[] bytes=e.getValue();String type=bytes.length>3&&(bytes[0]&255)==255&&(bytes[1]&255)==216?"jpeg":bytes.length>6&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F'?"gif":bytes.length>8&&(bytes[0]&255)==137&&bytes[1]=='P'&&bytes[2]=='N'&&bytes[3]=='G'?"png":"";if(type.isEmpty())throw new IllegalArgumentException("凭证仅支持JPG、GIF、PNG图片");out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\""+e.getKey()+"\"; filename=\"proof."+type+"\"\r\nContent-Type: image/"+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.write("\r\n".getBytes(StandardCharsets.UTF_8));}
            out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));if(out.size()>1048576)throw new IllegalArgumentException("凭证请求超出限制");return out.toByteArray();
        }catch(IOException e){throw new IllegalArgumentException("凭证请求编码失败");}
    }

}

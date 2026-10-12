package com.oms.supplychain.service.wms;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static com.oms.supplychain.service.wms.WmsStore.require;

/** Bounded transport with no redirects; neither URLs nor signing credentials are logged. */
public final class WmsHttp {
    private WmsHttp(){}
    public static String post(WmsProtocol.Wire wire) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)URI.create(wire.url).toURL().openConnection();
        try{connection.setConnectTimeout(5000);connection.setReadTimeout(15000);connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setRequestProperty("Content-Type",wire.contentType);connection.setDoOutput(true);byte[] payload=wire.body.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(payload.length);try(OutputStream out=connection.getOutputStream()){out.write(payload);}int status=connection.getResponseCode();require(status>=200&&status<300,"仓库HTTP响应 "+status);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){require(output.size()+count<=1024*1024,"仓库响应超过1MB");if(System.nanoTime()>deadline)throw new SocketTimeoutException("WMS response deadline");output.write(buffer,0,count);}return new String(output.toByteArray(),StandardCharsets.UTF_8);}
        }finally{connection.disconnect();}
    }
}

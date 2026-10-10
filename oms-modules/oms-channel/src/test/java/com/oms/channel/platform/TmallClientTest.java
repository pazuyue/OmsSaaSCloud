package com.oms.channel.platform;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TmallClientTest {
    HttpServer server;String response;int status;String received;
    @BeforeEach void start() throws Exception {
        status=200;response="{}";server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{java.io.ByteArrayOutputStream in=new java.io.ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=exchange.getRequestBody().read(b))!=-1)in.write(b,0,n);received=new String(in.toByteArray(),StandardCharsets.UTF_8);byte[] out=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,out.length);exchange.getResponseBody().write(out);exchange.close();});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    com.fasterxml.jackson.databind.JsonNode request(){return new TmallClient().post("http://127.0.0.1:"+server.getAddress().getPort()+"/",Collections.singletonMap("code","a+b 中文"));}
    @Test void postUsesUtf8FormEncoding(){response="{\"access_token\":\"secret\"}";assertEquals("secret",request().path("access_token").asText());assertEquals("code=a%2Bb+%E4%B8%AD%E6%96%87",received);}
    @Test void oauthRejectionExposesOnlySafeCode(){status=400;response="{\"error\":\"invalid_grant\",\"error_description\":\"PRIVATE_TOKEN\"}";TmallClient.Failure e=assertThrows(TmallClient.Failure.class,this::request);assertEquals("invalid_grant",e.code);assertFalse(e.unknown);assertFalse(e.getMessage().contains("PRIVATE_TOKEN"));}
    @Test void topErrorResponseIsNotTreatedAsSuccess(){response="{\"error_response\":{\"code\":27,\"sub_code\":\"isv.session-expired\",\"sub_msg\":\"PRIVATE_TOKEN\"}}";assertEquals("isv.session-expired",assertThrows(TmallClient.Failure.class,this::request).code);}
    @Test void malformedResponseHasUnknownResult(){response="not json";assertTrue(assertThrows(TmallClient.Failure.class,this::request).unknown);}
    @Test void redirectsAreNotFollowed(){status=302;response="{}";assertEquals("HTTP_302",assertThrows(TmallClient.Failure.class,this::request).code);}
    @Test void serverFailureIsUnknown(){status=503;response="{}";assertTrue(assertThrows(TmallClient.Failure.class,this::request).unknown);}
    @Test void responseSizeBounded(){char[] chars=new char[1048600];Arrays.fill(chars,'x');response=new String(chars);assertEquals("RESPONSE_TOO_LARGE",assertThrows(TmallClient.Failure.class,this::request).code);}
    @Test void md5SigningIgnoresSignAndEmptyValues(){Map<String,String>a=new LinkedHashMap<>();a.put("b","2");a.put("a","1");a.put("sign","old");a.put("empty","");assertEquals(TmallClient.digest("MD5","secreta1b2secret").toUpperCase(Locale.ROOT),TmallClient.sign(a,"secret"));}
}

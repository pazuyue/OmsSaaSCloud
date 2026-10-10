package com.oms.supplychain.service.wms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.security.SecureRandom;
import java.util.*;
import static com.oms.supplychain.service.wms.WmsStore.*;

@Service
public class WmsConnections {
    @Resource private WmsStore db;
    @Value("${oms.wms.encryption-key:}") private String encryptionKey;
    private static final String PUBLIC="id,company_code,name,provider,api_version,environment,endpoint,app_key,customer_id,callback_key,enabled,create_time,modify_time";
    public List<Map<String,Object>> list(String company){return db.jdbc.queryForList("SELECT "+PUBLIC+" FROM wms_connection WHERE company_code=? ORDER BY id DESC",company);}
    public Map<String,Object> get(String company,long key){return db.one("SELECT * FROM wms_connection WHERE id=? AND company_code=?",key,company);}
    public long save(String company,Map<String,Object> body){return db.tx().execute(status->{
        long key=body.get("id")==null?0:id(body.get("id"));
        Map<String,Object> old=key==0?null:db.one("SELECT * FROM wms_connection WHERE id=? AND company_code=? FOR UPDATE",key,company);
        String name=required(body,"name",100),provider=required(body,"provider",32),environment=required(body,"environment",16);
        require(Arrays.asList("QIMEN","JD_HUFU").contains(provider),"当前仅允许已登记的平台协议");
        String version=required(body,"api_version",8);require(provider.equals("QIMEN")?version.equals("2.0"):Arrays.asList("1.0","2.0").contains(version),"请选择平台支持的协议版本");
        require(Arrays.asList("TEST","PRODUCTION").contains(environment),"请选择测试或正式环境");
        String endpoint=required(body,"endpoint",500),appKey=required(body,"app_key",200),customer=text(body.get("customer_id"));
        URI uri=URI.create(endpoint);require(uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getQuery()==null&&uri.getFragment()==null,"接口地址不能含账号、参数或片段");
        require("https".equalsIgnoreCase(uri.getScheme())||("TEST".equals(environment)&&"http".equalsIgnoreCase(uri.getScheme())),"正式环境必须使用 HTTPS");
        int enabled=Boolean.TRUE.equals(body.get("enabled"))||"1".equals(text(body.get("enabled")))?1:0;
        // Provider identities are immutable once a document uses this connection. Rotate secrets in place.
        if(old!=null&&db.jdbc.queryForObject("SELECT COUNT(*) FROM wms_inbound_task WHERE connection_id=? AND company_code=?",Long.class,key,company)>0)
            for(String field:Arrays.asList("provider","api_version","environment","endpoint","app_key","customer_id"))require(text(body.get(field)).equals(text(old.get(field))),"已被执行单使用的对接目标不能变更，请新增配置；密钥仍可更新");
        String secret=text(body.get("secret"));require(old!=null||!secret.isEmpty(),"请填写应用密钥");require(secret.length()<=2000,"应用密钥过长");
        String cipher=secret.isEmpty()?text(old.get("secret_cipher")):encrypt(secret);
        if(key==0)return db.insert("wms_connection",map("company_code",company,"name",name,"provider",provider,"api_version",version,"environment",environment,"endpoint",endpoint,"app_key",appKey,"customer_id",customer,"secret_cipher",cipher,"callback_key",UUID.randomUUID().toString().replace("-",""),"enabled",enabled));
        db.jdbc.update("UPDATE wms_connection SET name=?,provider=?,api_version=?,environment=?,endpoint=?,app_key=?,customer_id=?,secret_cipher=?,enabled=? WHERE id=? AND company_code=?",name,provider,version,environment,endpoint,appKey,customer,cipher,enabled,key,company);return key;
    });}
    public Map<String,Object> usable(String company,long key){Map<String,Object> c=get(company,key);require(id(c.get("enabled"))==1,"仓库对接配置已停用");return c;}
    public String secret(Map<String,Object> config){return crypt(text(config.get("secret_cipher")),false);}
    private String encrypt(String text){return crypt(text,true);}
    private String crypt(String text,boolean encrypt){try{
        require(!WmsStore.text(encryptionKey).isEmpty(),"服务未配置仓库凭证加密密钥");
        byte[] key=Base64.getDecoder().decode(encryptionKey);require(key.length==32,"仓库凭证加密密钥必须为32字节");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");byte[] iv=new byte[12];byte[] input;
        if(encrypt){new SecureRandom().nextBytes(iv);input=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);}else{byte[] bytes=Base64.getDecoder().decode(text);require(bytes.length>28,"密钥密文不完整");System.arraycopy(bytes,0,iv,0,12);input=Arrays.copyOfRange(bytes,12,bytes.length);}
        c.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));byte[] output=c.doFinal(input);
        if(!encrypt)return new String(output,java.nio.charset.StandardCharsets.UTF_8);
        byte[] combined=new byte[iv.length+output.length];System.arraycopy(iv,0,combined,0,iv.length);System.arraycopy(output,0,combined,iv.length,output.length);return Base64.getEncoder().encodeToString(combined);
    }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("仓库凭证加解密失败",e);}}
}

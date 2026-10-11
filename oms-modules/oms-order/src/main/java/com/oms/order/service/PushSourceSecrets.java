package com.oms.order.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

@Component
public class PushSourceSecrets {
    @Value("${oms.order.encryption-key:}") private String key;
    public boolean ready(){try{return Base64.getDecoder().decode(key).length==32;}catch(Exception e){return false;}}
    public String encrypt(String company,String password){return transform(company,password,true);}
    public String decrypt(String company,String value){return transform(company,value,false);}
    private String transform(String company,String value,boolean encrypt){
        OrderValues.require(ready(),"订单服务尚未配置凭据加密密钥");
        try{byte[] iv=new byte[12],raw=encrypt?value.getBytes(StandardCharsets.UTF_8):Base64.getDecoder().decode(value);if(encrypt)new SecureRandom().nextBytes(iv);else iv=Arrays.copyOf(raw,12);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(key),"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(company.getBytes(StandardCharsets.UTF_8));
            if(!encrypt)return new String(cipher.doFinal(Arrays.copyOfRange(raw,12,raw.length)),StandardCharsets.UTF_8);
            byte[] result=cipher.doFinal(raw),all=Arrays.copyOf(iv,12+result.length);System.arraycopy(result,0,all,12,result.length);return Base64.getEncoder().encodeToString(all);
        }catch(Exception e){throw new IllegalArgumentException("推送库凭据加解密失败，请检查订单服务密钥");}
    }
}

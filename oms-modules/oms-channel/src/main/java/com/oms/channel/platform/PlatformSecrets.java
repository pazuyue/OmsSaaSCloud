package com.oms.channel.platform;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlatformSecrets {
    @Value("${oms.channel.encryption-key:}") private String encryptionKey;
    public boolean ready() { try { return Base64.getDecoder().decode(encryptionKey).length == 32; } catch (Exception e) { return false; } }
    public String encrypt(String value) { return transform(value, true); }
    public String decrypt(String value) { return transform(value, false); }
    private String transform(String value, boolean encrypt) {
        if (value == null || value.isEmpty()) return null;
        if (!ready()) throw new IllegalArgumentException("尚未配置平台凭据加密密钥");
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            byte[] raw=encrypt?value.getBytes(StandardCharsets.UTF_8):Base64.getDecoder().decode(value);
            byte[] iv=new byte[12];
            if(encrypt) new SecureRandom().nextBytes(iv); else iv=Arrays.copyOf(raw,12);
            cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(Base64.getDecoder().decode(encryptionKey),"AES"),new GCMParameterSpec(128,iv));
            if(!encrypt) return new String(cipher.doFinal(Arrays.copyOfRange(raw,12,raw.length)),StandardCharsets.UTF_8);
            byte[] encrypted=cipher.doFinal(raw), all=Arrays.copyOf(iv,12+encrypted.length);
            System.arraycopy(encrypted,0,all,12,encrypted.length);
            return Base64.getEncoder().encodeToString(all);
        } catch(Exception e) { throw new IllegalArgumentException("平台凭据加解密失败，请检查服务端密钥"); }
    }
}

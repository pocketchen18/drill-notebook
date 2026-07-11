package com.drillnotebook.app.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.springframework.stereotype.Component;

@Component
public class ApiKeyEncryptor {
    private static final int KEY_LENGTH = 32;
    private static final int IV_LENGTH = 12;
    private static final int SALT_LENGTH = 16;
    private static final int TAG_LENGTH = 128;
    private final SecureRandom random = new SecureRandom();

    public EncryptedValue encrypt(String plaintext, String material, String mode) throws Exception {
        byte[] salt = new byte[SALT_LENGTH]; random.nextBytes(salt);
        byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
        byte[] key = derive(material, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(Base64.getEncoder().encodeToString(ciphertext), Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(iv), mode);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public String decrypt(String encrypted, String saltBase64, String ivBase64, String material) throws Exception {
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        byte[] iv = Base64.getDecoder().decode(ivBase64);
        byte[] ciphertext = Base64.getDecoder().decode(encrypted);
        byte[] key = derive(material, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } finally { Arrays.fill(key, (byte) 0); }
    }

    public String fingerprintMaterial() {
        String user = System.getProperty("user.name", "unknown");
        String computer = System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "unknown"));
        String os = System.getProperty("os.name", "unknown");
        return "drill-notebook-fingerprint|" + user + "|" + computer + "|" + os;
    }

    private byte[] derive(String material, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3).withMemoryAsKB(32768).withParallelism(2).withSalt(salt).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] key = new byte[KEY_LENGTH];
        generator.generateBytes(material.toCharArray(), key);
        return key;
    }

    public record EncryptedValue(String encrypted, String salt, String iv, String mode) {}
}

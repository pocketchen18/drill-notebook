package com.drillnotebook.app;

import com.drillnotebook.app.service.ApiKeyEncryptor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ApiKeyEncryptorTest {
    @Test
    void encryptsAndDecryptsWithoutStoringPlaintext() throws Exception {
        ApiKeyEncryptor encryptor = new ApiKeyEncryptor();
        var encrypted = encryptor.encrypt("test-secret", "test-material", "password");
        assertNotEquals("test-secret", encrypted.encrypted());
        assertEquals("test-secret", encryptor.decrypt(encrypted.encrypted(), encrypted.salt(), encrypted.iv(), "test-material"));
    }
}

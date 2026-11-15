package br.com.fiap.numberone.billing.budget.infrastructure.notifications;

import br.com.fiap.numberone.billing.budget.application.exceptions.BudgetNotificationUnavailableException;
import br.com.fiap.numberone.billing.budget.infrastructure.config.BudgetNotificationProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ApprovalTokenProtection {
    private final SecureRandom random = new SecureRandom();
    private final BudgetNotificationProperties properties;
    private final JsonMapper mapper;

    public ApprovalTokenProtection(BudgetNotificationProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public String generateToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public void validateConfiguration() {
        encryptionKey();
    }

    public String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new BudgetNotificationUnavailableException("Falha ao calcular o hash SHA-256 da autorização", exception);
        }
    }

    public byte[] protect(UUID authorizationId, BudgetApprovalEmail email) {
        byte[] key = encryptionKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, authorizationId, nonce, key);
            byte[] content = mapper.writeValueAsBytes(email);
            byte[] encrypted = cipher.doFinal(content);
            return ByteBuffer.allocate(1 + nonce.length + encrypted.length).put((byte) 1).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new BudgetNotificationUnavailableException("Falha ao criptografar o conteúdo da notificação", exception);
        }
    }

    public BudgetApprovalEmail unprotect(UUID authorizationId, byte[] protectedContent) {
        if (protectedContent == null || protectedContent.length < 29 || protectedContent[0] != 1) {
            throw new BudgetNotificationUnavailableException("Formato do conteúdo protegido da notificação inválido");
        }
        byte[] key = encryptionKey();
        try {
            var input = ByteBuffer.wrap(protectedContent);
            input.get();
            byte[] nonce = new byte[12];
            input.get(nonce);
            byte[] ciphertext = new byte[input.remaining()];
            input.get(ciphertext);
            return mapper.readValue(cipher(Cipher.DECRYPT_MODE, authorizationId, nonce, key).doFinal(ciphertext), BudgetApprovalEmail.class);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new BudgetNotificationUnavailableException("Falha ao descriptografar ou ler o conteúdo da notificação", exception);
        }
    }

    private byte[] encryptionKey() {
        String configured = properties.encryptionKey();
        if (configured == null || configured.isBlank()) {
            throw new BudgetNotificationUnavailableException("Chave AES da notificação ausente");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException exception) {
            throw new BudgetNotificationUnavailableException("Chave AES da notificação não está em Base64 válido", exception);
        }
        if (key.length != 32) {
            throw new BudgetNotificationUnavailableException("Chave AES da notificação deve conter 32 bytes");
        }
        return key;
    }

    private Cipher cipher(int mode, UUID authorizationId, byte[] nonce, byte[] key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(authorizationId.toString().getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}

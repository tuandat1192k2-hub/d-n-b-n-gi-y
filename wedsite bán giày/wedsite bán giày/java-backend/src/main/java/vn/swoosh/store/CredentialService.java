package vn.swoosh.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.bouncycastle.crypto.generators.SCrypt;
import org.springframework.stereotype.Service;

@Service
public class CredentialService {
    private final SecureRandom random = new SecureRandom();

    public boolean matches(String password, String stored) {
        try {
            String[] parts = stored.split("\\$", 3);
            if (parts.length != 3 || !"scrypt".equals(parts[0])) return false;
            byte[] expected = HexFormat.of().parseHex(parts[2]);
            byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8),
                HexFormat.of().parseHex(parts[1]), 1 << 14, 8, 1, expected.length);
            return MessageDigest.isEqual(actual, expected);
        } catch (RuntimeException error) {
            return false;
        }
    }

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 1 << 14, 8, 1, 64);
        return "scrypt$" + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(hash);
    }
}

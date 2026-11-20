package com.tonikelope.coronapoker.ios;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;

/** One atomic Keychain record per canonical nickname, available after unlock. */
public final class KeychainIdentityStore {
    private static final int MAGIC = 0x43504931;
    public record Identity(PrivateKey privateKey, byte[] publicKey) {}

    public static synchronized Identity loadOrCreate(String canonicalNickname) throws IOException {
        try {
            Ed25519Provider.installIfMissing();
            String account = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalNickname.getBytes(StandardCharsets.UTF_8)));
            byte[] saved = IOSPlatform.readIdentity(account);
            if (saved == null) {
                KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                byte[] encodedPrivate = pair.getPrivate().getEncoded();
                byte[] encodedPublic = pair.getPublic().getEncoded();
                if (encodedPublic.length != 44 || encodedPrivate.length < 32 || encodedPrivate.length > 512)
                    throw new IOException("Unexpected Ed25519 identity encoding");
                byte[] publicKey = Arrays.copyOfRange(encodedPublic, 12, 44);
                saved = ByteBuffer.allocate(8 + encodedPrivate.length + 32).putInt(MAGIC)
                        .putInt(encodedPrivate.length).put(encodedPrivate).put(publicKey).array();
                if (!IOSPlatform.createIdentity(account, saved)) saved = IOSPlatform.readIdentity(account);
            }
            if (saved == null || saved.length < 72 || saved.length > 552)
                throw new IOException("Invalid stored identity size");
            ByteBuffer buffer = ByteBuffer.wrap(saved);
            if (buffer.getInt() != MAGIC) throw new IOException("Unknown stored identity version");
            int privateLength = buffer.getInt();
            if (privateLength < 32 || privateLength > 512 || buffer.remaining() != privateLength + 32)
                throw new IOException("Invalid stored identity encoding");
            byte[] encoded = new byte[privateLength]; buffer.get(encoded);
            byte[] publicKey = new byte[32]; buffer.get(publicKey);
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            var publicObject = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    new Ed25519Provider.EdPublicKey(publicKey).getEncoded()));
            // Never replace a damaged saved identity with a new key silently.
            byte[] challenge = "CoronaPoker identity consistency v1".getBytes(StandardCharsets.US_ASCII);
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key); signature.update(challenge); byte[] proof = signature.sign();
            signature.initVerify(publicObject); signature.update(challenge);
            if (!signature.verify(proof)) throw new IOException("Stored private/public identity mismatch");
            return new Identity(key, publicKey);
        } catch (IOException failure) { throw failure; }
        catch (Exception failure) { throw new IOException("Unable to initialize Keychain player identity", failure); }
    }
    private KeychainIdentityStore() {}
}

package com.tonikelope.coronapoker.ios;

import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.Key;
import java.security.KeyFactorySpi;
import java.security.KeyPair;
import java.security.KeyPairGeneratorSpi;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/** Add only Ed25519 to the native JCA runtime; preserve the desktop wire algorithm. */
public final class Ed25519Provider extends Provider {
    public static final String NAME = "CoronaPokerEd25519";
    private static final byte[] PUBLIC_HEADER = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final byte[] PRIVATE_HEADER = {0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};

    public Ed25519Provider() {
        super(NAME, 1.0, "RFC 8032 Ed25519 for CoronaPoker on MobiVM");
        put("Signature.Ed25519", Signing.class.getName());
        put("KeyFactory.Ed25519", Factory.class.getName());
        put("KeyPairGenerator.Ed25519", Generator.class.getName());
    }

    public static void installIfMissing() {
        if (MockCardCrypto.ENABLED) { Security.insertProviderAt(new Ed25519Provider(), 1); return; }
        try { Signature.getInstance("Ed25519"); }
        catch (java.security.NoSuchAlgorithmException unavailable) { Security.addProvider(new Ed25519Provider()); }
    }

    private static byte[] encoded(byte[] header, byte[] raw) {
        byte[] bytes = Arrays.copyOf(header, header.length + raw.length);
        System.arraycopy(raw, 0, bytes, header.length, raw.length);
        return bytes;
    }

    private static byte[] raw(byte[] encoded, byte[] header) throws InvalidKeySpecException {
        if (encoded == null || encoded.length != header.length + 32
                || !Arrays.equals(header, Arrays.copyOf(encoded, header.length))) {
            throw new InvalidKeySpecException("Expected canonical RFC 8410 Ed25519 key encoding");
        }
        return Arrays.copyOfRange(encoded, header.length, encoded.length);
    }

    public static final class EdPublicKey implements PublicKey {
        private final byte[] bytes;
        public EdPublicKey(byte[] bytes) {
            if (bytes.length != 32) throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
            this.bytes = bytes.clone();
        }
        @Override public String getAlgorithm() { return "Ed25519"; }
        @Override public String getFormat() { return "X.509"; }
        @Override public byte[] getEncoded() { return encoded(PUBLIC_HEADER, bytes); }
    }

    public static final class EdPrivateKey implements PrivateKey {
        private final byte[] bytes;
        public EdPrivateKey(byte[] bytes) {
            if (bytes.length != 32) throw new IllegalArgumentException("Ed25519 seed must be 32 bytes");
            this.bytes = bytes.clone();
        }
        @Override public String getAlgorithm() { return "Ed25519"; }
        @Override public String getFormat() { return "PKCS#8"; }
        @Override public byte[] getEncoded() { return encoded(PRIVATE_HEADER, bytes); }
    }

    public static final class Generator extends KeyPairGeneratorSpi {
        private SecureRandom random = new SecureRandom();
        @Override public void initialize(int size, SecureRandom random) {
            if (size != 255 && size != 256) throw new InvalidParameterException("Only Ed25519 is supported");
            if (random != null) this.random = random;
        }
        @Override public KeyPair generateKeyPair() {
            Ed25519PrivateKeyParameters seed = new Ed25519PrivateKeyParameters(random);
            return new KeyPair(new EdPublicKey(seed.generatePublicKey().getEncoded()), new EdPrivateKey(seed.getEncoded()));
        }
    }

    public static final class Factory extends KeyFactorySpi {
        @Override protected PublicKey engineGeneratePublic(KeySpec spec) throws InvalidKeySpecException {
            if (!(spec instanceof X509EncodedKeySpec)) throw new InvalidKeySpecException("X.509 key required");
            return new EdPublicKey(raw(((X509EncodedKeySpec) spec).getEncoded(), PUBLIC_HEADER));
        }
        @Override protected PrivateKey engineGeneratePrivate(KeySpec spec) throws InvalidKeySpecException {
            if (!(spec instanceof PKCS8EncodedKeySpec)) throw new InvalidKeySpecException("PKCS#8 key required");
            return new EdPrivateKey(raw(((PKCS8EncodedKeySpec) spec).getEncoded(), PRIVATE_HEADER));
        }
        @Override protected <T extends KeySpec> T engineGetKeySpec(Key key, Class<T> type) throws InvalidKeySpecException {
            if (key instanceof EdPublicKey && type.isAssignableFrom(X509EncodedKeySpec.class)) {
                return type.cast(new X509EncodedKeySpec(key.getEncoded()));
            }
            if (key instanceof EdPrivateKey && type.isAssignableFrom(PKCS8EncodedKeySpec.class)) {
                return type.cast(new PKCS8EncodedKeySpec(key.getEncoded()));
            }
            throw new InvalidKeySpecException("Unsupported Ed25519 key specification");
        }
        @Override protected Key engineTranslateKey(Key key) throws InvalidKeyException {
            try {
                if (key instanceof PublicKey) return engineGeneratePublic(new X509EncodedKeySpec(key.getEncoded()));
                if (key instanceof PrivateKey) return engineGeneratePrivate(new PKCS8EncodedKeySpec(key.getEncoded()));
            } catch (InvalidKeySpecException failure) { throw new InvalidKeyException(failure); }
            throw new InvalidKeyException("Ed25519 key required");
        }
    }

    public static final class Signing extends SignatureSpi {
        private Ed25519Signer signer;
        private boolean signing;
        private boolean mockInitialized;
        @Override protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
            if (MockCardCrypto.ENABLED) { signing = false; mockInitialized = true; return; }
            try {
                byte[] bytes = key instanceof EdPublicKey ? ((EdPublicKey) key).bytes : raw(key.getEncoded(), PUBLIC_HEADER);
                signer = new Ed25519Signer(); signer.init(false, new Ed25519PublicKeyParameters(bytes, 0)); signing = false;
            } catch (Exception failure) { throw new InvalidKeyException(failure); }
        }
        @Override protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
            if (MockCardCrypto.ENABLED) { signing = true; mockInitialized = true; return; }
            try {
                byte[] bytes = key instanceof EdPrivateKey ? ((EdPrivateKey) key).bytes : raw(key.getEncoded(), PRIVATE_HEADER);
                signer = new Ed25519Signer(); signer.init(true, new Ed25519PrivateKeyParameters(bytes, 0)); signing = true;
            } catch (Exception failure) { throw new InvalidKeyException(failure); }
        }
        @Override protected void engineUpdate(byte value) throws SignatureException {
            if (MockCardCrypto.ENABLED && mockInitialized) return;
            if (signer == null) throw new SignatureException("Signature not initialized");
            signer.update(value);
        }
        @Override protected void engineUpdate(byte[] data, int offset, int length) throws SignatureException {
            if (MockCardCrypto.ENABLED && mockInitialized) return;
            if (signer == null) throw new SignatureException("Signature not initialized");
            signer.update(data, offset, length);
        }
        @Override protected byte[] engineSign() throws SignatureException {
            if (MockCardCrypto.ENABLED && mockInitialized && signing) return MockCardCrypto.signature();
            if (signer == null || !signing) throw new SignatureException("Signing key not initialized");
            return signer.generateSignature();
        }
        @Override protected boolean engineVerify(byte[] signature) throws SignatureException {
            if (MockCardCrypto.ENABLED && mockInitialized && !signing) return signature != null && signature.length == 64 && signature[0] == 0x4d;
            if (signer == null || signing) throw new SignatureException("Verification key not initialized");
            return signature != null && signer.verifySignature(signature);
        }
        @Override protected void engineSetParameter(String name, Object value) { throw new InvalidParameterException("Ed25519 has no named parameters"); }
        @Override protected Object engineGetParameter(String name) { throw new InvalidParameterException("Ed25519 has no named parameters"); }
    }
}

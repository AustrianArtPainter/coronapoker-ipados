package com.tonikelope.coronapoker.ios;

import com.tonikelope.coronapoker.crypto.Dleq;
import com.tonikelope.coronapoker.crypto.EdwardsPoint;
import com.tonikelope.coronapoker.crypto.Ristretto255;
import com.tonikelope.coronapoker.crypto.RistrettoSRA;
import com.tonikelope.coronapoker.core.network.SecureChannelCodec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.SecretKeySpec;

/** Run identical algorithm checks on desktop Java and the native AOT runtime. */
public final class ModernRuntimeProbe {
    private record Vector(String name, List<Integer> values) {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    public static String run() throws Exception {
        Ed25519Provider.installIfMissing();
        Vector a = new Vector("probe", List.of(1, 2, 3));
        Vector b = new Vector("probe", List.copyOf(a.values()));
        require(a.equals(b) && a.hashCode() == b.hashCode(), "record semantics");
        Object value = a;
        require(value instanceof Vector vector && vector.values().stream().map(i -> i * 2).toList().equals(List.of(2, 4, 6)), "pattern/stream semantics");
        int sum = CompletableFuture.supplyAsync(() -> a.values().stream().mapToInt(Integer::intValue).sum())
                .thenCompose(result -> CompletableFuture.completedFuture(result * 2))
                .get(10, TimeUnit.SECONDS);
        require(sum == 12, "CompletableFuture execution");
        require(java.util.stream.IntStream.rangeClosed(1, 10000).parallel().sum() == 50005000, "parallel stream execution");

        byte[] genesis = RistrettoSRA.getGenesisDeck();
        require(genesis.length == 52 * 32 && RistrettoSRA.arePointsValid(genesis), "genesis deck validity");
        byte[] scalar = RistrettoSRA.scalarToBytes(BigInteger.valueOf(7));
        byte[] locked = RistrettoSRA.applyCommutativeLock(genesis, scalar);
        byte[] unlocked = RistrettoSRA.applyCommutativeLock(locked, RistrettoSRA.getUnlockScalar(scalar));
        require(Arrays.equals(genesis, unlocked), "original SRA lock/unlock");
        EdwardsPoint g1 = EdwardsPoint.BASE;
        EdwardsPoint g2 = Ristretto255.decode(Arrays.copyOfRange(genesis, 0, 32));
        EdwardsPoint h1 = g1.scalarMul(BigInteger.valueOf(7));
        EdwardsPoint h2 = g2.scalarMul(BigInteger.valueOf(7));
        byte[] proof = Dleq.prove(BigInteger.valueOf(7), g1, h1, g2, h2);
        require(Dleq.verify(g1, h1, g2, h2, proof), "original DLEQ verification");
        proof[5] ^= 1;
        require(!Dleq.verify(g1, h1, g2, h2, proof), "tampered DLEQ rejection");

        byte[] key = new byte[32];
        byte[] iv = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        for (int i = 0; i < iv.length; i++) iv[i] = (byte) (16 + i);
        SecretKeySpec aes = new SecretKeySpec(key, "AES");
        SecretKeySpec mac = new SecretKeySpec(key, "HmacSHA256");
        String command = "CORONAPOKER#25.79#iOS验证";
        String wire = SecureChannelCodec.encryptCommand(command, aes, iv, mac);
        require(wire != null && command.equals(SecureChannelCodec.decryptCommand(wire, aes, mac)), "original encrypted wire round trip");
        byte[] damaged = SecureChannelCodec.encryptBytes(command.getBytes(StandardCharsets.UTF_8), aes, iv, mac);
        damaged[0] ^= 1;
        boolean rejected = false;
        try { SecureChannelCodec.decryptBytes(damaged, aes, mac); }
        catch (java.security.KeyException expected) { rejected = true; }
        require(rejected, "bad HMAC rejection");

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(256);
        KeyPair host = ec.generateKeyPair(), peer = ec.generateKeyPair();
        KeyAgreement hostECDH = KeyAgreement.getInstance("ECDH");
        hostECDH.init(host.getPrivate()); hostECDH.doPhase(peer.getPublic(), true);
        KeyAgreement peerECDH = KeyAgreement.getInstance("ECDH");
        peerECDH.init(peer.getPrivate()); peerECDH.doPhase(host.getPublic(), true);
        require(Arrays.equals(hostECDH.generateSecret(), peerECDH.generateSecret()), "original P-256 handshake primitives");

        boolean ed25519;
        try {
            KeyPair identity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(identity.getPrivate()); signer.update(genesis);
            byte[] signed = signer.sign();
            signer.initVerify(identity.getPublic()); signer.update(genesis);
            ed25519 = signed.length == 64 && signer.verify(signed);
        } catch (java.security.NoSuchAlgorithmException unavailable) { ed25519 = false; }
        require(ed25519, "Ed25519 identity primitive");
        // RFC 8032 section 7.1 test 1: identical signatures across providers.
        java.security.Provider provider = new Ed25519Provider();
        byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] publicKey = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
        byte[] expectedSignature = HexFormat.of().parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        Signature compatible = Signature.getInstance("Ed25519", provider);
        compatible.initSign(new Ed25519Provider.EdPrivateKey(seed));
        byte[] actualSignature = compatible.sign();
        require(Arrays.equals(actualSignature, expectedSignature), "RFC 8032 signature vector");
        java.security.PublicKey imported = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                new java.security.spec.X509EncodedKeySpec(new Ed25519Provider.EdPublicKey(publicKey).getEncoded()));
        Signature original = Signature.getInstance("Ed25519");
        original.initVerify(imported);
        original.update(new byte[0]);
        require(original.verify(actualSignature), "cross-provider Ed25519 verification");
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(genesis));
        return "{\"scope\":\"Java AOT primitives; full game not validated\",\"recordsAndFutures\":true,"
                + "\"originalRistrettoAndDLEQ\":true,\"originalWireCodec\":true,\"p256ECDH\":true,"
                + "\"ed25519Available\":" + ed25519 + ",\"genesisSHA256\":\"" + digest
                + "\",\"fixedIVWire\":\"" + wire + "\"}";
    }

    public static void main(String[] args) throws Exception { System.out.println(run()); }
}

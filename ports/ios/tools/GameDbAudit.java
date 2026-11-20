package com.tonikelope.coronapoker.iosvalidation;

import com.tonikelope.coronapoker.CanonicalActionRecord;
import com.tonikelope.coronapoker.core.ApplicationMetadata;
import com.tonikelope.coronapoker.core.identity.GameIdentityProtocol;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Independent desktop verification of copied, completed native game hands. */
public final class GameDbAudit {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("database public-identities.tsv hostNickname");
        Map<String,byte[]> keys = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            if (line.isBlank()) continue;
            String[] fields=line.split("\t",-1);
            if (fields.length!=2) throw new IllegalArgumentException("Invalid public identity row");
            keys.put(new String(Base64.getDecoder().decode(fields[0]),StandardCharsets.UTF_8),Base64.getDecoder().decode(fields[1]));
        }
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        int hands=0,actions=0,tamperChecks=0,completedHandsInDatabase=0,balanceRows=0;
        double lastStacks=0,lastBuyins=0;
        Class.forName("org.sqlite.JDBC");
        try (Connection db=DriverManager.getConnection("jdbc:sqlite:file:"+Path.of(args[0]).toAbsolutePath()+"?mode=ro")) {
            try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("PRAGMA quick_check")) {
                if (!rows.next() || !"ok".equals(rows.getString(1))) throw new AssertionError("SQLite integrity check failed");
            }
            try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("PRAGMA foreign_key_check")) {
                if (rows.next()) throw new AssertionError("Foreign key violation");
            }
            try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("SELECT COUNT(*) FROM hand WHERE end IS NOT NULL AND end>0")) {
                rows.next();completedHandsInDatabase=rows.getInt(1);
            }
            try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("SELECT a.player,a.record_b64,a.sig_b64,h.hand_id_b64 FROM action a JOIN hand h ON h.id=a.id_hand WHERE h.end IS NOT NULL AND h.end>0 ORDER BY h.counter,a.counter,a.player")) {
                while (rows.next()) {
                    String actor=rows.getString(1);
                    String recordB64=rows.getString(2),signatureB64=rows.getString(3);
                    if (recordB64==null || recordB64.isBlank() || signatureB64==null || signatureB64.isBlank())
                        throw new AssertionError("Completed action has no record/signature");
                    byte[] record=Base64.getDecoder().decode(recordB64);
                    byte[] signature=Base64.getDecoder().decode(signatureB64);
                    if (record.length!=CanonicalActionRecord.RECORD_BYTES || signature.length!=64) throw new AssertionError("Invalid action encoding");
                    if (!MessageDigest.isEqual(CanonicalActionRecord.readPlayerId(record),CanonicalActionRecord.playerIdFromNick(actor)))
                        throw new AssertionError("Actor mismatch");
                    if (!MessageDigest.isEqual(CanonicalActionRecord.readHandId(record),Base64.getDecoder().decode(rows.getString(4))))
                        throw new AssertionError("Hand ID mismatch");
                    byte[] key=keys.get(actor.contains("$") ? args[2] : actor);
                    if (key==null || !GameIdentityProtocol.verifyAction(key,record,signature)) throw new AssertionError("Invalid signature for "+actor);
                    byte[] corrupted=record.clone();corrupted[82]^=1;
                    if (GameIdentityProtocol.verifyAction(key,corrupted,signature)) throw new AssertionError("Tampered amount accepted");
                    digest.update(record);digest.update(signature);actions++;tamperChecks++;
                }
            }
            try (Statement statement=db.createStatement(); ResultSet rows=statement.executeQuery("SELECT h.id,SUM(b.stack),SUM(b.buyin),COUNT(b.id),COUNT(b.player),COUNT(DISTINCT b.player),COUNT(b.stack),COUNT(b.buyin) FROM hand h LEFT JOIN balance b ON b.id_hand=h.id WHERE h.end IS NOT NULL AND h.end>0 GROUP BY h.id ORDER BY h.id_game,h.counter,h.id")) {
                while (rows.next()) {
                    int rowCount=rows.getInt(4);
                    if (rowCount==0 || rowCount!=rows.getInt(5) || rowCount!=rows.getInt(6)
                            || rowCount!=rows.getInt(7) || rowCount!=rows.getInt(8))
                        throw new AssertionError("Missing, null or duplicate balance rows for completed hand "+rows.getInt(1));
                    lastStacks=rows.getDouble(2);lastBuyins=rows.getDouble(3);
                    if (!Double.isFinite(lastStacks) || !Double.isFinite(lastBuyins)
                            || Math.abs(lastStacks-lastBuyins)>0.001)
                        throw new AssertionError("Funds did not conserve for completed hand "+rows.getInt(1));
                    hands++;balanceRows+=rowCount;
                }
            }
        }
        if (hands!=completedHandsInDatabase) throw new AssertionError("Completed hand audit count mismatch");
        if (hands==0 || actions==0) throw new AssertionError("No completed signed game evidence");
        System.out.println("{\"passed\":true,\"completedHands\":"+hands+",\"verifiedActionSignatures\":"+actions
                +",\"completedHandsInDatabase\":"+completedHandsInDatabase+",\"auditedBalanceRows\":"+balanceRows
                +",\"tamperedAmountsRejected\":"+tamperChecks+",\"actionRecordsSHA256\":\""+HexFormat.of().formatHex(digest.digest())
                +"\",\"finalStacks\":"+lastStacks+",\"totalBuyins\":"+lastBuyins
                +",\"verifierGameVersion\":\""+ApplicationMetadata.VERSION+"\",\"readOnlyDatabase\":true"
                +",\"runTimeProvenanceChecked\":false,\"expectedScenarioRosterChecked\":false,\"fullHandChainChecked\":false"
                +",\"scope\":\"all stored betting actions of completed hands: signatures, actor and hand binding, amount tamper rejection; SQLite integrity; every completed hand has nonempty unique nonnull balance rows and conserved totals\"}");
    }
}

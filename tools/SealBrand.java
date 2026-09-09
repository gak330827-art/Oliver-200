/*
 * ═══════════════════════════════════════════════════════════════════════════
 *  Oliver-200 · генератор шифрованного контейнера с текстами заставки.
 *
 *  Запуск (JDK 17+, компиляция не нужна):
 *      java tools/SealBrand.java
 *      java tools/SealBrand.java --binding oliver200/intro/v1 \
 *           --shards <hexA>:<hexB>:<hexC>
 *
 *  Что делает: собирает запись бренда в формате OL2B1, шифрует её
 *  AES-256-GCM в контейнер OL2E (тот же формат, что у core/crypto/
 *  CryptoEnvelope.kt) и печатает готовые константы для
 *  core/intro/SealedIntroBrand.kt.
 *
 *  Ключ НЕ хранится целиком нигде: он собирается как SHA-256 от XOR трёх
 *  осколков. Осколки печатаются здесь и вклеиваются в исходник — по
 *  отдельности ни один из них ключом не является.
 *
 *  Без --shards осколки генерируются заново, и старый контейнер
 *  перестанет открываться: это нормально, контейнер и осколки меняются
 *  только вместе.
 *
 *  Подпись: OLIVER-200 · см. SIGNATURES.txt
 * ═══════════════════════════════════════════════════════════════════════════
 */
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SealBrand {

    private static final char US = (char) 31;
    private static final String RECORD_MAGIC = "OL2B1";
    private static final byte[] ENVELOPE_MAGIC = {'O', 'L', '2', 'E'};
    private static final byte VERSION = 1;
    private static final byte KDF_RAW_KEY = 0;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String CONTEXT_PREFIX = "intro:brand:";
    private static final String DEFAULT_BINDING = "oliver200/intro/v1";

    /** Тексты заставки. Единственное место, где они лежат открыто. */
    private static final String[] FIELDS = {
        "ANIMAL",
        "COMPANY",
        "RU",
        "STEAM COMMUNITY",
        "Заботимся о вас",
    };

    public static void main(String[] args) throws Exception {
        String binding = DEFAULT_BINDING;
        byte[][] shards = null;

        for (int i = 0; i < args.length; i++) {
            if ("--binding".equals(args[i]) && i + 1 < args.length) {
                binding = args[++i];
            } else if ("--shards".equals(args[i]) && i + 1 < args.length) {
                String[] parts = args[++i].split(":");
                if (parts.length != 3) throw new IllegalArgumentException("Нужно ровно три осколка через ':'");
                shards = new byte[3][];
                for (int k = 0; k < 3; k++) shards[k] = unhex(parts[k]);
            } else {
                throw new IllegalArgumentException("Неизвестный аргумент: " + args[i]);
            }
        }

        SecureRandom random = SecureRandom.getInstanceStrong();
        if (shards == null) {
            shards = new byte[3][32];
            for (byte[] shard : shards) random.nextBytes(shard);
        }
        for (byte[] shard : shards) {
            if (shard.length != 32) throw new IllegalArgumentException("Осколок должен быть ровно 32 байта");
        }

        StringBuilder record = new StringBuilder(RECORD_MAGIC);
        for (String field : FIELDS) {
            if (field.isEmpty() || field.length() > 64) throw new IllegalStateException("Поле не проходит лимит: " + field);
            if (!field.equals(field.trim())) throw new IllegalStateException("Краевые пробелы: " + field);
            for (int i = 0; i < field.length(); i++) {
                if (Character.isISOControl(field.charAt(i))) throw new IllegalStateException("Управляющий символ: " + field);
            }
            record.append(US).append(field);
        }

        byte[] key = deriveKey(shards);
        byte[] sealed = seal(record.toString().getBytes(StandardCharsets.UTF_8), key,
                CONTEXT_PREFIX + binding, random);

        System.out.println("BINDING=" + binding);
        System.out.println("SHARD_A=" + hex(shards[0]));
        System.out.println("SHARD_B=" + hex(shards[1]));
        System.out.println("SHARD_C=" + hex(shards[2]));
        System.out.println("SEALED=" + Base64.getEncoder().encodeToString(sealed));
        System.out.println("RECORD_BYTES=" + record.toString().getBytes(StandardCharsets.UTF_8).length);
        System.out.println("SEALED_BYTES=" + sealed.length);
    }

    /** Ключ = SHA-256(A ⊕ B ⊕ C). Ни один осколок сам по себе ключом не является. */
    private static byte[] deriveKey(byte[][] shards) throws Exception {
        byte[] mixed = new byte[32];
        for (byte[] shard : shards) {
            for (int i = 0; i < 32; i++) mixed[i] ^= shard[i];
        }
        return MessageDigest.getInstance("SHA-256").digest(mixed);
    }

    /** Формат один в один с CryptoEnvelope.sealInternal (kdf = raw key, соли нет). */
    private static byte[] seal(byte[] plaintext, byte[] key, String context, SecureRandom random)
            throws Exception {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);

        byte[] header = new byte[4 + 1 + 1 + 4 + 1 + 1 + IV_BYTES];
        int p = 0;
        System.arraycopy(ENVELOPE_MAGIC, 0, header, p, 4);
        p += 4;
        header[p++] = VERSION;
        header[p++] = KDF_RAW_KEY;
        header[p++] = 0; // iterations = 0 (big-endian int32)
        header[p++] = 0;
        header[p++] = 0;
        header[p++] = 0;
        header[p++] = 0; // saltLen = 0
        header[p++] = (byte) IV_BYTES;
        System.arraycopy(iv, 0, header, p, IV_BYTES);

        byte[] ctx = context.getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[header.length + ctx.length];
        System.arraycopy(header, 0, aad, 0, header.length);
        System.arraycopy(ctx, 0, aad, header.length, ctx.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(aad);
        byte[] ct = cipher.doFinal(plaintext);

        byte[] out = new byte[header.length + ct.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(ct, 0, out, header.length, ct.length);
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] unhex(String text) {
        if (text.length() % 2 != 0) throw new IllegalArgumentException("Нечётная длина hex");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

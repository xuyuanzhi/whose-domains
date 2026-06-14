package info.wesite.core.utils;

import java.util.Random;
import java.util.UUID;

public class RandomUtils {

    private static final Random RANDOM = new Random();

    private static final char[] CHARS = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' };

    public static String generateVcode() {
        return generateCode(6);
    }

    public static String generateCode(int length) {
        char[] cs = new char[length];
        for (int i = 0; i < length; i++) {
            cs[i] = CHARS[RANDOM.nextInt(10)];
        }
        return String.valueOf(cs);
    }

    public static String generateId() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }
}

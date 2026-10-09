package org.sitare.quiz.identity;

import java.security.SecureRandom;

/** One-time passwords for students: 8 characters, no look-alike letters or digits. */
public final class OtpGenerator {

    private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private OtpGenerator() {
    }

    public static String newOtp() {
        return random(8);
    }

    public static String newQuizCode() {
        return random(6);
    }

    public static String random(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}

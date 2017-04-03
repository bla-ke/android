package com.etesync.syncadapter.journalmanager;

import android.support.annotation.NonNull;
import android.util.Base64;

import com.etesync.syncadapter.App;

import org.apache.commons.codec.Charsets;
import org.apache.commons.lang3.ArrayUtils;
import org.spongycastle.crypto.BufferedBlockCipher;
import org.spongycastle.crypto.CipherParameters;
import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.digests.SHA256Digest;
import org.spongycastle.crypto.engines.AESEngine;
import org.spongycastle.crypto.generators.SCrypt;
import org.spongycastle.crypto.macs.HMac;
import org.spongycastle.crypto.modes.CBCBlockCipher;
import org.spongycastle.crypto.paddings.BlockCipherPadding;
import org.spongycastle.crypto.paddings.PKCS7Padding;
import org.spongycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.spongycastle.crypto.params.KeyParameter;
import org.spongycastle.crypto.params.ParametersWithIV;
import org.spongycastle.util.encoders.Hex;

import java.security.SecureRandom;
import java.util.Arrays;

import lombok.Getter;

public class Crypto {
    public static String deriveKey(String salt, String password) {
        final int keySize = 190;

        return Base64.encodeToString(SCrypt.generate(password.getBytes(Charsets.UTF_8), salt.getBytes(Charsets.UTF_8), 16384, 8, 1, keySize), Base64.NO_WRAP);
    }

    public static class CryptoManager {
        private SecureRandom _random = null;
        @Getter
        private final byte version;
        private final byte[] cipherKey;
        private final byte[] hmacKey;

        public CryptoManager(int version, @NonNull String keyBase64, @NonNull String salt) throws Exceptions.IntegrityException {
            byte[] derivedKey;
            if (version > Byte.MAX_VALUE) {
                throw new Exceptions.IntegrityException("Version is out of range.");
            } else if (version > Constants.CURRENT_VERSION) {
                throw new RuntimeException("Journal version is newer than expected.");
            } else if (version == 1) {
                derivedKey = Base64.decode(keyBase64, Base64.NO_WRAP);
            } else {
                derivedKey = hmac256(salt.getBytes(Charsets.UTF_8), Base64.decode(keyBase64, Base64.NO_WRAP));
            }

            this.version = (byte) version;
            cipherKey = hmac256("aes".getBytes(Charsets.UTF_8), derivedKey);
            hmacKey = hmac256("hmac".getBytes(Charsets.UTF_8), derivedKey);
        }

        private static final int blockSize = 16; // AES's block size in bytes

        private BufferedBlockCipher getCipher(byte[] iv, boolean encrypt) {
            KeyParameter key = new KeyParameter(cipherKey);
            CipherParameters params = new ParametersWithIV(key, iv);

            BlockCipherPadding padding = new PKCS7Padding();
            BufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
                    new CBCBlockCipher(new AESEngine()), padding);
            cipher.reset();
            cipher.init(encrypt, params);

            return cipher;
        }

        byte[] decrypt(byte[] _data) {
            byte[] iv = Arrays.copyOfRange(_data, 0, blockSize);
            byte[] data = Arrays.copyOfRange(_data, blockSize, _data.length);

            BufferedBlockCipher cipher = getCipher(iv, false);

            byte[] buf = new byte[cipher.getOutputSize(data.length)];
            int len = cipher.processBytes(data, 0, data.length, buf, 0);
            try {
                len += cipher.doFinal(buf, len);
            } catch (InvalidCipherTextException e) {
                e.printStackTrace();
                App.log.severe("Invalid ciphertext: " + Base64.encodeToString(_data, Base64.NO_WRAP));
                return null;
            }

            // remove padding
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);

            return out;
        }

        byte[] encrypt(byte[] data) {
            byte[] iv = new byte[blockSize];
            getRandom().nextBytes(iv);

            BufferedBlockCipher cipher = getCipher(iv, true);

            byte[] buf = new byte[cipher.getOutputSize(data.length) + blockSize];
            System.arraycopy(iv, 0, buf, 0, iv.length);
            int len = iv.length + cipher.processBytes(data, 0, data.length, buf, iv.length);
            try {
                cipher.doFinal(buf, len);
            } catch (InvalidCipherTextException e) {
                App.log.severe("Invalid ciphertext: " + Base64.encodeToString(data, Base64.NO_WRAP));
                e.printStackTrace();
                return null;
            }

            return buf;
        }

        byte[] hmac(byte[] data) {
            if (version == 1) {
                return hmac256(hmacKey, data);
            } else {
                // Starting from version 2 we hmac the version too.
                return hmac256(hmacKey, ArrayUtils.add(data, version));
            }
        }

        private SecureRandom getRandom() {
            if (_random == null) {
                _random = new SecureRandom();
            }
            return _random;
        }

        private static byte[] hmac256(byte[] keyByte, byte[] data) {
            HMac hmac = new HMac(new SHA256Digest());
            KeyParameter key = new KeyParameter(keyByte);
            byte[] ret = new byte[hmac.getMacSize()];
            hmac.init(key);
            hmac.update(data, 0, data.length);
            hmac.doFinal(ret, 0);
            return ret;
        }
    }

    static String sha256(String base) {
        return sha256(base.getBytes(Charsets.UTF_8));
    }

    private static String sha256(byte[] base) {
        SHA256Digest digest = new SHA256Digest();
        digest.update(base, 0, base.length);
        byte[] ret = new byte[digest.getDigestSize()];
        digest.doFinal(ret, 0);
        return toHex(ret);
    }

    static String toHex(byte[] bytes) {
        return Hex.toHexString(bytes).toLowerCase();
    }
}
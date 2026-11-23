package io.github.c1589190.bma3.app.onebot;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;
import java.io.IOException;

/** CurrentUser DPAPI, using the same native format as the old Windows launcher. */
final class WindowsDpapi {
    private WindowsDpapi() {}

    static boolean available() {
        return java.io.File.separatorChar == '\\';
    }

    static byte[] protect(byte[] value) throws IOException {
        try {
            return Crypt32Util.cryptProtectData(value, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Windows DPAPI 加密失败");
        }
    }

    static byte[] unprotect(byte[] value) throws IOException {
        try {
            return Crypt32Util.cryptUnprotectData(value, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("Windows DPAPI 解密失败");
        }
    }
}

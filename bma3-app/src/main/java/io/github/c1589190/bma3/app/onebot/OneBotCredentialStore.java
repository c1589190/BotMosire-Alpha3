package io.github.c1589190.bma3.app.onebot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.c1589190.bma3.app.config.AppConfig;
import io.github.c1589190.bma3.core.security.PrivateFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Per-data-directory OneBot credentials. Callers serialize changes with account reconciliation. */
final class OneBotCredentialStore {
    static final String MANUAL = "manual";
    static final String WINDOWS_IMPORT = "windows_dpapi";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;

    record Entry(String value, String source) {
        @Override
        public String toString() {
            return "OneBotCredential[source=" + source + "]";
        }
    }

    OneBotCredentialStore(Path dataDir) {
        path = dataDir.toAbsolutePath().normalize().resolve("secrets/onebot.json");
    }

    static boolean validName(String name) {
        return AppConfig.Account.validTokenRef(name);
    }

    static boolean validValue(String value) {
        return value != null && !value.isBlank() && value.length() <= 8192
                && value.chars().allMatch(character -> character >= 32 && character < 127);
    }

    Map<String, Entry> read() throws IOException {
        checkDirectory();
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new TreeMap<>();
        }
        PrivateFiles.restrict(path);
        byte[] stored = readBytes(path);
        byte[] plain = stored;
        try {
            if (WindowsDpapi.available()) {
                plain = WindowsDpapi.unprotect(stored);
            }
            Map<String, Entry> entries = JSON.readValue(plain, new TypeReference<>() {});
            if (entries == null || entries.entrySet().stream().anyMatch(item -> !validName(item.getKey())
                    || item.getValue() == null || !validValue(item.getValue().value())
                    || !(MANUAL.equals(item.getValue().source()) || WINDOWS_IMPORT.equals(item.getValue().source())))) {
                throw new IOException("OneBot 凭据文件无效");
            }
            return new TreeMap<>(entries);
        } catch (IOException failure) {
            throw new IOException("OneBot 凭据文件不可读");
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(stored, (byte) 0);
        }
    }

    void write(Map<String, Entry> entries) throws IOException {
        checkDirectory();
        byte[] plain = JSON.writeValueAsBytes(entries);
        byte[] stored = plain;
        try {
            if (WindowsDpapi.available()) {
                stored = WindowsDpapi.protect(plain);
            }
            if (stored.length > MAX_BYTES) {
                throw new IOException("OneBot 凭据文件超过大小上限");
            }
            Path temp = PrivateFiles.createTemp(path);
            try {
                Files.write(temp, stored);
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(stored, (byte) 0);
        }
    }

    static String importWindows(String localAppData) throws IOException {
        if (localAppData == null || localAppData.isBlank()) {
            throw new NoSuchFileException("Windows OneBot 凭据来源");
        }
        Path legacy = Path.of(localAppData, "BotMosire-Alpha3", "onebot-token.dpapi");
        if (Files.notExists(legacy, LinkOption.NOFOLLOW_LINKS)) {
            throw new NoSuchFileException("Windows OneBot 凭据来源");
        }
        byte[] stored = readBytes(legacy);
        try {
            byte[] plain = WindowsDpapi.unprotect(stored);
            try {
                return new String(plain, StandardCharsets.UTF_8);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } finally {
            Arrays.fill(stored, (byte) 0);
        }
    }

    private void checkDirectory() throws IOException {
        Path directory = path.getParent();
        if (!Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OneBot 凭据目录不是普通目录");
        }
    }

    private static byte[] readBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("OneBot 凭据必须是普通文件");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                Arrays.fill(bytes, (byte) 0);
                throw new IOException("OneBot 凭据文件超过大小上限");
            }
            return bytes;
        }
    }
}

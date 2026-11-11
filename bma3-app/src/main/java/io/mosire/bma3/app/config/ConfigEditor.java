package io.mosire.bma3.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.mosire.bma3.core.config.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Path-level editor for {@code config/app.yaml}.
 *
 * <p>Two rules keep this safe without a whitelist (the operator asked for unrestricted edits):</p>
 * <ol>
 *   <li><b>Validate before touching the real file.</b> The patched tree is serialized into a
 *       throwaway directory and loaded through {@link ConfigLoader}, so schema/range errors never
 *       reach disk.</li>
 *   <li><b>Atomic replace.</b> The validated bytes are written next to the target and moved with
 *       {@code ATOMIC_MOVE}, so {@link ConfigWatcher} never observes a half-written file.</li>
 * </ol>
 *
 * <p>Note that a successful write only means "the file is valid". Whether the running process
 * actually adopted it is decided by the watcher; callers poll the effective snapshot for that.</p>
 */
public final class ConfigEditor {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private ConfigEditor() {
    }

    public record Result(AppConfig config, String serialized) {
    }

    public static Result patch(Path root, Map<String, String> env, Map<String, Object> patch,
                               List<String> unset) {
        Path file = ConfigLoader.configFile(root);
        ObjectNode tree = readTree(file);
        if (patch != null) {
            patch.forEach((path, value) -> apply(tree, path, value));
        }
        if (unset != null) {
            unset.forEach(path -> remove(tree, path));
        }
        String serialized;
        try {
            serialized = YAML.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (Exception failure) {
            throw new ConfigException("config", "序列化失败: " + failure.getMessage());
        }
        AppConfig config = validate(env, serialized);
        write(file, serialized);
        return new Result(config, serialized);
    }

    private static ObjectNode readTree(Path file) {
        try {
            if (!Files.exists(file)) {
                return YAML.createObjectNode();
            }
            String raw = Files.readString(file);
            if (raw.isBlank()) {
                return YAML.createObjectNode();
            }
            JsonNode node = YAML.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new ConfigException("config", "配置文件根节点必须是映射");
            }
            return (ObjectNode) node;
        } catch (IOException failure) {
            throw new ConfigException("config", "读取失败: " + failure.getMessage());
        }
    }

    /** Validating the candidate in an isolated directory means the live file is never touched. */
    private static AppConfig validate(Map<String, String> env, String serialized) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("bma3-config-check");
            Path candidate = tempDir.resolve("config");
            Files.createDirectories(candidate);
            Files.writeString(candidate.resolve("app.yaml"), serialized);
            return ConfigLoader.load(tempDir, env);
        } catch (IOException failure) {
            throw new ConfigException("config", "临时校验目录创建失败: " + failure.getMessage());
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private static void write(Path file, String serialized) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, "app", ".yaml.tmp");
            Files.writeString(temp, serialized);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new ConfigException("config", "写入失败: " + failure.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void apply(ObjectNode tree, String path, Object value) {
        String[] tokens = tokens(path);
        ObjectNode node = tree;
        for (int index = 0; index < tokens.length - 1; index++) {
            JsonNode child = node.get(tokens[index]);
            if (child == null || !child.isObject()) {
                child = node.putObject(tokens[index]);
            }
            node = (ObjectNode) child;
        }
        String leaf = tokens[tokens.length - 1];
        if (value == null) {
            node.remove(leaf);
        } else if (value instanceof Integer || value instanceof Long) {
            node.put(leaf, ((Number) value).longValue());
        } else if (value instanceof Number number) {
            node.put(leaf, number.doubleValue());
        } else if (value instanceof Boolean bool) {
            node.put(leaf, bool);
        } else if (value instanceof Map<?, ?> || value instanceof java.util.List<?>) {
            // 结构化值（weighting.rules 之类）必须落成 YAML 结构，不能 String.valueOf 成一行
            node.set(leaf, YAML.valueToTree(value));
        } else if (value instanceof JsonNode json) {
            node.set(leaf, json);
        } else {
            node.put(leaf, String.valueOf(value));
        }
    }

    private static void remove(ObjectNode tree, String path) {
        String[] tokens = tokens(path);
        ObjectNode node = tree;
        for (int index = 0; index < tokens.length - 1; index++) {
            JsonNode child = node.get(tokens[index]);
            if (child == null || !child.isObject()) {
                return;
            }
            node = (ObjectNode) child;
        }
        node.remove(tokens[tokens.length - 1]);
    }

    private static String[] tokens(String path) {
        if (path == null || path.isBlank()) {
            throw new ConfigException("config", "patch 路径不能为空");
        }
        String[] tokens = path.split("\\.");
        for (String token : tokens) {
            if (token.isBlank()) {
                throw new ConfigException("config", "patch 路径含空段: " + path);
            }
        }
        return tokens;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}

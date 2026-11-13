package io.mosire.bma3.xvfb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 工作区目录与 {@code meta.json} 的读写。
 *
 * <p>不变量 W5：任何解析出来的工作区目录都必须落在 {@code root} 之内，越界一律拒绝。
 */
public final class WorkspaceStore {

    private static final String META = "meta.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path root;

    public WorkspaceStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** 建一个空工作区（还没起 Xvfb）：目录 + 子目录 + meta。 */
    public Workspace create(int display, int width, int height, int depth) {
        try {
            Files.createDirectories(root);
            String id = freshId();
            Path dir = root.resolve(id);
            Files.createDirectories(dir);
            for (String child : List.of("home", "uploads", "downloads", "shots", "logs")) {
                Files.createDirectories(dir.resolve(child));
            }
            Workspace workspace = new Workspace(id, dir, display, Workspace.STARTING,
                    System.currentTimeMillis(), 0L, 0L, List.of(), width, height, depth, "");
            save(workspace);
            return workspace;
        } catch (IOException failure) {
            throw new UncheckedIOException("创建工作区失败: " + root, failure);
        }
    }

    public void save(Workspace workspace) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", workspace.id());
        node.put("display", workspace.display());
        node.put("state", workspace.state());
        node.put("createdAt", workspace.createdAt());
        node.put("xvfbPid", workspace.xvfbPid());
        node.put("wmPid", workspace.wmPid());
        ArrayNode apps = node.putArray("appPids");
        workspace.appPids().forEach(apps::add);
        node.put("width", workspace.width());
        node.put("height", workspace.height());
        node.put("depth", workspace.depth());
        node.put("error", workspace.error());
        try {
            Path dir = workspace.dir();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(META + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), node);
            Files.move(tmp, dir.resolve(META),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new UncheckedIOException("写 meta.json 失败: " + workspace.dir(), failure);
        }
    }

    public Optional<Workspace> find(String idOrPath) {
        if (idOrPath == null || idOrPath.isBlank()) {
            return Optional.empty();
        }
        Path dir = resolve(idOrPath);
        if (dir == null || !Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve(META))) {
            return Optional.empty();
        }
        return read(dir);
    }

    public List<Workspace> list() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Workspace> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve(META)))
                    .forEach(dir -> read(dir).ifPresent(out::add));
        } catch (IOException failure) {
            return List.of();
        }
        out.sort(Comparator.comparingLong(Workspace::createdAt).reversed());
        return out;
    }

    /** 最近的 RUNNING 工作区（W4：会话级工具的默认目标）。 */
    public Optional<Workspace> latestRunning() {
        return list().stream().filter(Workspace::running).findFirst();
    }

    /** W3：{@code keep=false} 删除整个目录；{@code true} 只清 shots/ 并把 state 置 KEPT。 */
    public Workspace close(Workspace workspace, boolean keep) {
        if (!keep) {
            deleteRecursively(workspace.dir());
            return workspace.withState(Workspace.DELETED).withProcesses(0L, 0L, List.of());
        }
        Path shots = workspace.dir().resolve("shots");
        deleteRecursively(shots);
        try {
            Files.createDirectories(shots);
        } catch (IOException failure) {
            // 清不掉不算失败：保留工作区比 shots 干净更重要
        }
        Workspace kept = workspace.withState(Workspace.KEPT).withProcesses(0L, 0L, List.of());
        save(kept);
        return kept;
    }

    /**
     * 把 id / 绝对路径 / 相对路径解析成工作区目录；越界返回 {@code null}（W5）。
     */
    public Path resolve(String idOrPath) {
        Path candidate;
        try {
            Path raw = Path.of(idOrPath);
            candidate = raw.isAbsolute() ? raw : root.resolve(idOrPath);
        } catch (RuntimeException failure) {
            return null;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return null;
        }
        return normalized;
    }

    private Optional<Workspace> read(Path dir) {
        try {
            JsonNode node = JSON.readTree(dir.resolve(META).toFile());
            List<Long> apps = new ArrayList<>();
            for (JsonNode pid : node.path("appPids")) {
                apps.add(pid.asLong());
            }
            return Optional.of(new Workspace(
                    node.path("id").asText(dir.getFileName().toString()),
                    dir,
                    node.path("display").asInt(90),
                    node.path("state").asText(Workspace.KEPT),
                    node.path("createdAt").asLong(0L),
                    node.path("xvfbPid").asLong(0L),
                    node.path("wmPid").asLong(0L),
                    apps,
                    node.path("width").asInt(1440),
                    node.path("height").asInt(900),
                    node.path("depth").asInt(24),
                    node.path("error").asText("")));
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private String freshId() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String id = "ws-" + Integer.toHexString(RANDOM.nextInt(0x10000));
            if (!Files.exists(root.resolve(id))) {
                return id;
            }
        }
        return "ws-" + System.currentTimeMillis();
    }

    static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 尽力而为：被占用时留待下次清理
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}

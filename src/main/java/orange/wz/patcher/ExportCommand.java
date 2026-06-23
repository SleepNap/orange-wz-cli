package orange.wz.patcher;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * 从 git 仓库导出补丁数据：把指定起点之后变更的 wz xml 抽出来，并生成对应的 diff 文件。
 * 等价于 BeiDou-Server 那边的 ExportPatch.java，但作为独立子命令存在，不依赖那个测试类。
 *
 * --from 支持两种形态：
 *   1) git commit hash（如 27529d68）—— 直接用 `<hash>..HEAD`
 *   2) datetime（如 2026-06-22 或 2026-06-22T15:30:00）—— 工具会查 git log 找该时间点之后的
 *      第一个 commit 作为起点
 */
@Command(name = "export", description = "从 git 仓库导出指定起点之后的 wz xml 与 diff")
public final class ExportCommand implements Callable<Integer> {

    @Option(names = "--from", required = true,
            description = "起点：git commit hash（如 27529d68），或 datetime（如 2026-06-22 / 2026-06-22T15:30:00）。"
                    + "datetime 模式会找该时间点之后的第一个 commit 作为起点。")
    String from;

    @Option(names = "--repo", description = "git 仓库根目录（默认当前目录）")
    Path repo;

    @Option(names = "--out-xml", description = "xml 输出根目录（默认 ~/Desktop/upgrade_yyyyMMdd）")
    Path outXml;

    @Option(names = "--out-diff", description = "diff 输出根目录（默认 ~/Desktop/diff_yyyyMMdd）")
    Path outDiff;

    @Option(names = "--prefix", arity = "1..*", description = "需要扫描的目录前缀（相对仓库根），默认 gms-server/wz、gms-server/wz-zh-CN")
    List<String> prefixes;

    @Option(names = "--no-diff", description = "只复制 xml，不生成 diff")
    boolean noDiff;

    @Option(names = "--context", defaultValue = "30", description = "git diff 上下文行数（-U），默认 30")
    int context;

    @Option(names = {"-v", "--verbose"}, description = "打印每个文件的处理过程")
    boolean verbose;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> DEFAULT_PREFIXES = List.of("gms-server/wz", "gms-server/wz-zh-CN");

    @Override
    public Integer call() {
        Path repoRoot = findRepoRoot(repo != null ? repo.toAbsolutePath().normalize() : Path.of("").toAbsolutePath().normalize());
        if (!Files.isDirectory(repoRoot.resolve(".git"))) {
            System.err.println("[err] 不是 git 仓库（找不到 .git 目录）: " + repoRoot);
            return 2;
        }

        String fromCommit;
        try {
            fromCommit = resolveFromCommit(from, repoRoot);
        } catch (RuntimeException e) {
            System.err.println("[err] 解析 --from 失败: " + e.getMessage());
            return 2;
        }
        if (fromCommit == null) {
            System.err.println("[err] --from 解析为空");
            return 2;
        }

        List<String> effPrefixes = (prefixes == null || prefixes.isEmpty()) ? DEFAULT_PREFIXES : prefixes;
        Path effOutXml = outXml != null ? outXml : defaultDir("upgrade_");
        Path effOutDiff = outDiff != null ? outDiff : defaultDir("diff_");

        System.out.println("==========================================");
        System.out.println("  补丁导出");
        System.out.println("  起点:     " + from + (fromCommit.equals(from) ? "" : "  →  " + fromCommit));
        System.out.println("  仓库:     " + repoRoot);
        System.out.println("  前缀:     " + effPrefixes);
        System.out.println("  xml out:  " + effOutXml);
        System.out.println("  diff out: " + (noDiff ? "(skipped)" : effOutDiff));
        System.out.println("==========================================");

        deleteDirIfExists(effOutXml);
        if (!noDiff) deleteDirIfExists(effOutDiff);

        int totalAdded = 0, totalDeleted = 0, totalFailed = 0;
        for (String prefix : effPrefixes) {
            String shortName = lastSegment(prefix);
            Path targetDir = effOutXml.resolve(shortName);
            Path diffDir = effOutDiff.resolve(shortName);
            System.out.println();
            System.out.println(">>> " + prefix + "/");

            List<String> changed = gitFiles(fromCommit, prefix, "ACMR", repoRoot);
            if (changed.isEmpty()) {
                System.out.println("  (无新增/修改)");
            } else {
                for (String file : changed) {
                    Path src = repoRoot.resolve(file);
                    if (!Files.isRegularFile(src)) {
                        System.out.println("  ! 文件不存在(跳过): " + file);
                        continue;
                    }
                    String rel = file.substring(prefix.length() + 1);
                    Path dst = targetDir.resolve(rel);
                    try {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                        if (verbose) System.out.println("  + " + file);
                        totalAdded++;
                    } catch (IOException e) {
                        totalFailed++;
                        System.err.println("  ! 复制失败: " + file + " — " + e.getMessage());
                        continue;
                    }
                    if (!noDiff) {
                        Path diffDst = diffDir.resolve(rel + ".diff");
                        try {
                            Files.createDirectories(diffDst.getParent());
                            writeFileDiff(fromCommit, file, repoRoot, diffDst, context);
                        } catch (IOException e) {
                            totalFailed++;
                            System.err.println("  ! diff 失败: " + file + " — " + e.getMessage());
                        }
                    }
                }
            }

            List<String> deleted = gitFiles(fromCommit, prefix, "D", repoRoot);
            if (!deleted.isEmpty()) {
                System.out.println("  [删除文件]");
                for (String f : deleted) {
                    System.out.println("    [DEL] " + f);
                }
                totalDeleted += deleted.size();
            }
        }

        System.out.println();
        System.out.println("==========================================");
        System.out.println("  导出完成");
        System.out.println("  新增/修改: " + totalAdded);
        System.out.println("  删除:      " + totalDeleted);
        System.out.println("  失败:      " + totalFailed);
        System.out.println("  xml out:   " + effOutXml);
        if (!noDiff) System.out.println("  diff out:  " + effOutDiff);
        System.out.println("==========================================");
        return totalFailed > 0 ? 1 : 0;
    }

    // ---- 起点解析 ----

    /**
     * 把 --from 解析成 git commit hash。
     * 优先尝试当 hash/ref 用（git rev-parse 能解析就用）；失败再当 datetime 解析。
     */
    private static String resolveFromCommit(String input, Path repoRoot) {
        // 优先 git rev-parse（认识 hash / branch / tag / HEAD~N 等所有 ref 形态）
        String resolved = tryRevParse(input, repoRoot);
        if (resolved != null) return resolved;
        // 失败则按 datetime 处理
        return resolveByDatetime(input, repoRoot);
    }

    /** 用 git rev-parse 解析，成功返回完整 hash，失败返回 null。 */
    private static String tryRevParse(String ref, Path repoRoot) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--verify", ref + "^{commit}")
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            int rc = p.waitFor();
            if (rc == 0 && out != null && out.matches("[0-9a-f]{40}")) {
                return out;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 按 datetime 解析：找到 input 时间点之前（含）最近的一个 commit，返回它的 hash。
     * 然后导出范围 [hash..HEAD] 包含该时间点之后的所有变更。
     */
    private static String resolveByDatetime(String input, Path repoRoot) {
        OffsetDateTime dt = parseDatetime(input);
        if (dt == null) {
            throw new IllegalArgumentException("既不是 git ref 也不是合法 datetime: " + input);
        }
        String iso = dt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        try {
            Process p = new ProcessBuilder(
                    "git", "log",
                    "--until=" + iso,
                    "--pretty=format:%H",
                    "-1"
            )
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(false)
                    .start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            int rc = p.waitFor();
            if (rc != 0 || out == null || !out.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("在 " + iso + " 之前找不到任何 commit");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** 容忍几种常见 datetime 格式，返回带系统时区的 OffsetDateTime；不可解析返回 null。 */
    private static OffsetDateTime parseDatetime(String s) {
        ZoneId zone = ZoneId.systemDefault();
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd",
                "yyyy/MM/dd HH:mm:ss",
                "yyyy/MM/dd HH:mm",
                "yyyy/MM/dd",
                "yyyyMMdd"
        };
        for (String pat : patterns) {
            try {
                DateTimeFormatter f = DateTimeFormatter.ofPattern(pat);
                if (pat.contains("HH")) {
                    return LocalDateTime.parse(s, f).atZone(zone).toOffsetDateTime();
                }
                return LocalDate.parse(s, f).atStartOfDay(zone).toOffsetDateTime();
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    // ---- Git 操作 ----

    /** git log --diff-filter=<ACMR|D> --name-only —— 列出 fromCommit..HEAD 范围内某 prefix 下的文件。 */
    private static List<String> gitFiles(String fromCommit, String prefix, String diffFilter, Path repoRoot) {
        List<String> files = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(
                    "git", "-c", "core.quotePath=false", "log",
                    fromCommit + "..HEAD",
                    "--diff-filter=" + diffFilter,
                    "--name-only",
                    "--pretty=format:",
                    "--",
                    prefix
            )
                    .directory(repoRoot.toFile())
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
                        line = line.substring(1, line.length() - 1);
                    }
                    if (!line.isEmpty()) files.add(line);
                }
            }
            p.waitFor();
        } catch (IOException e) {
            throw new UncheckedIOException("git log 失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return files.stream().distinct().toList();
    }

    /** git diff --binary -U<ctx> fromCommit..HEAD -- <file>  写到 outFile。 */
    private static void writeFileDiff(String fromCommit, String file, Path repoRoot, Path outFile, int context) throws IOException {
        Process p = new ProcessBuilder(
                "git", "-c", "core.quotePath=false", "diff",
                "--binary",
                "-U" + Math.max(0, context),
                fromCommit + "..HEAD",
                "--",
                file
        )
                .directory(repoRoot.toFile())
                .redirectErrorStream(false)
                .start();
        try (var in = p.getInputStream()) {
            Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 工具方法 ----

    private static String lastSegment(String prefix) {
        int idx = prefix.lastIndexOf('/');
        return idx < 0 ? prefix : prefix.substring(idx + 1);
    }

    private static Path findRepoRoot(Path start) {
        Path cur = start;
        while (cur != null) {
            if (Files.isDirectory(cur.resolve(".git"))) return cur;
            cur = cur.getParent();
        }
        return start;
    }

    private static Path defaultDir(String tag) {
        String home = System.getProperty("user.home");
        String date = LocalDate.now().format(DATE_FMT);
        return Paths.get(home, "Desktop", tag + date);
    }

    private static void deleteDirIfExists(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    System.err.println("  ! 删除失败: " + path + " — " + e.getMessage());
                }
            });
        } catch (IOException e) {
            System.err.println("  ! 无法删除目录: " + dir + " — " + e.getMessage());
        }
    }
}

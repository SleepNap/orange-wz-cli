package orange.wz.patcher;

import orange.wz.patcher.model.Change;
import orange.wz.patcher.parser.DiffParser;
import orange.wz.patcher.patch.ImgPatcher;
import orange.wz.provider.tools.wzkey.WzKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * 批量 patch：按文件名配对 diff↔img↔输出。
 *   diff 目录下 a/b/Foo.img.xml.diff
 *     → 找 img 目录下 a/b/Foo.img
 *     → 写到输出目录 a/b/Foo.img
 * 没找到对应 img 的 diff 会跳过，并在最后 BATCH SUMMARY 汇总。
 */
@Command(name = "batch", description = "批量 patch（按目录配对 diff↔img）")
public final class BatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "客户端 img 根目录")
    Path imgDir;

    @Parameters(index = "1", description = "diff 文件根目录（递归扫所有 *.diff）")
    Path diffDir;

    @Parameters(index = "2", description = "输出 img 根目录")
    Path outDir;

    @Option(names = {"-v", "--verbose"}, description = "详细输出每条 change")
    boolean verbose;

    @Option(names = "--dry-run", description = "解析+模拟，不写文件")
    boolean dryRun;

    @Option(names = "--strict", description = "任一变更失败立即中止整批")
    boolean strict;

    @Option(names = "--iv", defaultValue = "GMS", description = "WZ IV：GMS / EMS / BMS / CLASSIC（大小写不敏感；cms/latest 为兼容别名）")
    String ivName;

    @Option(names = "--full-xml-dir", description = "完整服务端 XML 根目录（按 diff 路径自动配对）")
    Path fullXmlDir;

    @Override
    public Integer call() {
        if (!Files.isDirectory(imgDir)) {
            System.err.println("[err] img 目录不存在: " + imgDir);
            return 2;
        }
        if (!Files.isDirectory(diffDir)) {
            System.err.println("[err] diff 目录不存在: " + diffDir);
            return 2;
        }
        WzKey key = IvSupport.resolve(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName + "（支持 GMS / EMS / BMS / CLASSIC）");
            return 2;
        }

        List<Path> diffs;
        try (Stream<Path> walk = Files.walk(diffDir)) {
            diffs = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".diff"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            System.err.println("[err] 扫描 diff 目录失败: " + e.getMessage());
            return 2;
        }

        if (diffs.isEmpty()) {
            System.out.println("[batch] 0 diff 文件，无事可做");
            return 0;
        }

        int ok = 0, fail = 0, skip = 0;
        List<String> skipReasons = new ArrayList<>();
        List<String> failReasons = new ArrayList<>();

        for (Path diff : diffs) {
            Path diffRel = diffDir.toAbsolutePath().relativize(diff.toAbsolutePath());
            String diffRelStr = diffRel.toString().replace('\\', '/');
            // diff 文件名 X.img.xml.diff → 客户端 img 期望 X.img
            String diffName = diff.getFileName().toString();
            if (!diffName.endsWith(".img.xml.diff")) {
                skip++;
                skipReasons.add(diffRelStr + " (文件名不符合 *.img.xml.diff)");
                continue;
            }
            // 把 diff 的相对路径里的 ".wz" 目录前缀去掉一段（diff 是 String.wz/Foo.img.xml.diff，
            // 但客户端 EN/Data 下是 String/Foo.img），按客户端布局做映射。
            String imgRelStr = stripWzSegment(diffRelStr).replace(".img.xml.diff", ".img");
            Path inImg = imgDir.resolve(imgRelStr);
            // 客户端可能是不剥 .wz 的形态——再退一步尝试
            if (!Files.isRegularFile(inImg)) {
                Path alt = imgDir.resolve(diffRelStr.replace(".img.xml.diff", ".img"));
                if (Files.isRegularFile(alt)) {
                    inImg = alt;
                    imgRelStr = diffRelStr.replace(".img.xml.diff", ".img");
                }
            }
            if (!Files.isRegularFile(inImg)) {
                skip++;
                skipReasons.add(imgRelStr + " (no input img)");
                continue;
            }
            Path outImg = outDir.resolve(imgRelStr);

            System.out.println("================================================================");
            System.out.println("[batch] " + diffRelStr);
            try {
                Files.createDirectories(outImg.getParent());
            } catch (IOException e) {
                fail++;
                failReasons.add(imgRelStr + ": 创建输出目录失败 " + e.getMessage());
                if (strict) break;
                continue;
            }

            Path fullXml = pairFullXml(diff);
            if (fullXml != null && !Files.isRegularFile(fullXml)) {
                System.err.println("[warn] 完整 XML 不存在: " + fullXml);
                fullXml = null;
            }

            List<Change> changes;
            try {
                changes = new DiffParser(fullXml).parse(diff);
            } catch (Exception e) {
                fail++;
                failReasons.add(imgRelStr + ": diff 解析失败 " + e.getMessage());
                if (strict) return 3;
                continue;
            }

            ImgPatcher patcher = new ImgPatcher(key, strict, verbose);
            ImgPatcher.Result result = patcher.patch(inImg, changes, outImg, dryRun);

            if (!verbose) {
                for (String line : result.log()) {
                    System.out.println(line);
                }
            }

            if (result.error() != null) {
                fail++;
                failReasons.add(imgRelStr + ": " + result.error());
                System.err.println("[err] " + result.error());
                if (strict) {
                    if (result.error().startsWith("img 解析")) return 4;
                    if (result.error().startsWith("img 写入")) return 5;
                    return 1;
                }
                continue;
            }

            long size = -1;
            try { size = dryRun ? Files.size(inImg) : Files.size(outImg); } catch (Exception ignored) {}
            System.out.println(result.applied() + " applied, " + result.failed() + " failed. Output: " + outImg + " (" + size + " bytes)");
            if (result.failed() > 0) {
                fail++;
                failReasons.add(imgRelStr + ": " + result.failed() + " change(s) failed");
            } else {
                ok++;
            }
        }

        System.out.println();
        System.out.println("================ BATCH SUMMARY ================");
        System.out.println("ok:   " + ok);
        System.out.println("fail: " + fail);
        System.out.println("skip: " + skip);
        for (String r : failReasons) System.out.println("  - " + r);
        for (String r : skipReasons) System.out.println("  - " + r);
        if (fail > 0) return 1;
        return 0;
    }

    /** 把 "String.wz/Foo.img.xml.diff" 里的 ".wz" 段干掉 → "String/Foo.img.xml.diff"。 */
    private static String stripWzSegment(String rel) {
        return rel.replaceAll("([^/]+)\\.wz/", "$1/");
    }

    /** 根据 diff 路径在 --full-xml-dir 下推断对应完整 xml。 */
    private Path pairFullXml(Path diff) {
        if (fullXmlDir == null) return null;
        if (!Files.isDirectory(fullXmlDir)) return null;
        Path diffRel = diffDir.toAbsolutePath().relativize(diff.toAbsolutePath());
        String relStr = diffRel.toString().replace('\\', '/');
        if (!relStr.endsWith(".diff")) return null;
        Path candidate = fullXmlDir.resolve(relStr.substring(0, relStr.length() - ".diff".length()));
        return candidate;
    }
}

package orange.wz.patcher;

import orange.wz.patcher.model.Change;
import orange.wz.patcher.parser.DiffParser;
import orange.wz.patcher.patch.ImgPatcher;
import orange.wz.provider.tools.wzkey.WzKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** 默认子命令：把 diff 应用到 .img 文件。 */
@Command(name = "patch", description = "把 unified diff 应用到 .img 文件")
public final class PatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "原始客户端 .img 文件")
    Path inputImg;

    @Parameters(index = "1", description = "git unified diff 文件")
    Path diffFile;

    @Parameters(index = "2", description = "输出 .img 文件路径（可与 input 相同，原地覆盖）")
    Path outputImg;

    @Option(names = {"-v", "--verbose"}, description = "详细输出每条 change")
    boolean verbose;

    @Option(names = "--dry-run", description = "解析 diff、加载 img、模拟 patch，但不写文件")
    boolean dryRun;

    @Option(names = "--strict", description = "任何一条 change 失败立即中止")
    boolean strict;

    @Option(names = "--iv", defaultValue = "GMS", description = "WZ IV：GMS（默认） / EMS / BMS / CLASSIC（大小写不敏感）")
    String ivName;

    @Option(names = "--full-xml", description = "完整服务端 XML（diff +++ 那一侧）。当 hunk 上下文不带外层 imgdir 时，"
            + "用它从行号反查路径栈，避免歧义/找不到节点。")
    Path fullXml;

    @Option(names = "--full-xml-dir", description = "完整服务端 XML 根目录。会按 diff 文件名自动配对（如 Skill.img.xml.diff "
            + "→ <dir>/.../Skill.img.xml）。仅当 --full-xml 未指定时生效。")
    Path fullXmlDir;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(inputImg)) {
            System.err.println("[err] input.img 不存在: " + inputImg);
            return 2;
        }
        if (!Files.isRegularFile(diffFile)) {
            System.err.println("[err] diff 文件不存在: " + diffFile);
            return 2;
        }

        List<Change> changes;
        try {
            Path resolvedFullXml = resolveFullXml(diffFile);
            if (resolvedFullXml != null && !Files.isRegularFile(resolvedFullXml)) {
                System.err.println("[warn] 完整 XML 不存在，将不使用路径回退: " + resolvedFullXml);
                resolvedFullXml = null;
            }
            changes = new DiffParser(resolvedFullXml).parse(diffFile);
        } catch (Exception e) {
            System.err.println("[err] diff 解析失败: " + e.getMessage());
            return 3;
        }
        System.out.println("[parse] " + changes.size() + " changes from diff");

        if (changes.isEmpty()) {
            try {
                if (!dryRun && !inputImg.toAbsolutePath().equals(outputImg.toAbsolutePath())) {
                    Files.createDirectories(outputImg.toAbsolutePath().getParent());
                    Files.copy(inputImg, outputImg, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                long size = Files.size(outputImg.toAbsolutePath().equals(inputImg.toAbsolutePath()) ? inputImg : outputImg);
                System.out.println("0 applied, 0 failed. Output: " + outputImg + " (" + size + " bytes)");
                return 0;
            } catch (Exception e) {
                System.err.println("[err] 复制 img 失败: " + e.getMessage());
                return 5;
            }
        }

        WzKey key = IvSupport.resolve(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName + "（支持 GMS / EMS / BMS / CLASSIC）");
            return 2;
        }

        ImgPatcher patcher = new ImgPatcher(key, strict, verbose);
        ImgPatcher.Result result = patcher.patch(inputImg, changes, outputImg, dryRun);

        if (!verbose) {
            for (String line : result.log()) {
                System.out.println(line);
            }
        }

        if (result.error() != null) {
            System.err.println("[err] " + result.error());
            if (result.error().startsWith("img 解析")) return 4;
            if (result.error().startsWith("img 写入")) return 5;
            return 1;
        }

        long size;
        try {
            size = dryRun ? Files.size(inputImg) : Files.size(outputImg);
        } catch (Exception e) {
            size = -1;
        }
        String suffix = dryRun ? " (dry-run, not written)" : "";
        System.out.println(result.applied() + " applied, " + result.failed() + " failed. Output: " + outputImg + " (" + size + " bytes)" + suffix);

        if (result.failed() > 0) return 1;
        return 0;
    }

    /**
     * --full-xml 优先；否则用 --full-xml-dir + diff 路径推断目标 xml。
     *
     * 推断规则（按可靠度从高到低尝试）：
     *   1) 用 diff 相对路径（去掉文件名末尾的 .diff）拼到 --full-xml-dir 下；前提是 diff 在 --full-xml-dir 之下、
     *      或两者共享一段相同的尾部（例如两边都有 wz-zh-CN/Quest.wz/Act.img.xml(.diff)）。
     *   2) 退而求其次：递归找首个文件名匹配的 xml。这种回退在多个同名 xml 同时存在时（wz/ 和 wz-zh-CN/ 都有 Act.img.xml）
     *      不可靠，仅在策略 1 没命中时才用，并打印 warn 提示用户用 --full-xml 显式指定。
     */
    private Path resolveFullXml(Path diffPath) {
        if (fullXml != null) return fullXml;
        if (fullXmlDir == null) return null;
        if (!Files.isDirectory(fullXmlDir)) {
            System.err.println("[warn] --full-xml-dir 不是目录: " + fullXmlDir);
            return null;
        }
        String diffName = diffPath.getFileName().toString();
        if (!diffName.endsWith(".diff")) return null;
        String xmlName = diffName.substring(0, diffName.length() - ".diff".length());

        // 策略 1：尝试按 diff 完整路径中"与 fullXmlDir 共享的最长尾段"拼出 xml 路径
        Path absoluteDiff = diffPath.toAbsolutePath().normalize();
        Path absoluteDir = fullXmlDir.toAbsolutePath().normalize();
        // 直接尝试 diff 路径里 fullXmlDir 同名段后面的相对部分
        // 例如 diff = .../diff_20260619/wz-zh-CN/Quest.wz/Act.img.xml.diff
        //      dir  = .../upgrade_20260619/
        // 我们想拿到 wz-zh-CN/Quest.wz/Act.img.xml
        // 做法：从 diff 路径里找出和 dir 平级的兄弟根（diff_xxx 的兄弟是 upgrade_xxx），
        // 然后把 diff_xxx 后面的相对路径整段挪过来。
        Path diffParent = absoluteDiff.getParent();
        if (diffParent != null) {
            // 在 diff 路径里找一个分量名与 fullXmlDir 末段"同根名兄弟"的位置：
            // 简化策略——直接从根往叶尝试每一个后缀子路径，看能不能在 fullXmlDir 下找到对应文件
            int n = absoluteDiff.getNameCount();
            for (int i = 0; i < n; i++) {
                Path tail = absoluteDiff.subpath(i, n);
                // 把 .diff 后缀去掉
                String tailStr = tail.toString().replace('\\', '/');
                if (tailStr.endsWith(".diff")) {
                    tailStr = tailStr.substring(0, tailStr.length() - ".diff".length());
                }
                Path candidate = absoluteDir.resolve(tailStr);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }

        // 策略 2：递归找同名 xml；若有多个同名候选，警告并取第一个
        try (var stream = Files.walk(fullXmlDir)) {
            var matches = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(xmlName))
                    .toList();
            if (matches.isEmpty()) return null;
            if (matches.size() > 1) {
                System.err.println("[warn] --full-xml-dir 中有多个 " + xmlName + " 候选（"
                        + matches.size() + " 个），按 diff 路径无法精确配对，回退取第一个："
                        + matches.get(0) + "。建议用 --full-xml 显式指定。");
            }
            return matches.get(0);
        } catch (Exception e) {
            System.err.println("[warn] 在 --full-xml-dir 中查找 " + xmlName + " 失败: " + e.getMessage());
            return null;
        }
    }
}

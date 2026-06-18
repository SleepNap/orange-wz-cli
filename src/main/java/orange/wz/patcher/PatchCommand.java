package orange.wz.patcher;

import orange.wz.patcher.model.Change;
import orange.wz.patcher.parser.DiffParser;
import orange.wz.patcher.patch.ImgPatcher;
import orange.wz.provider.WzAESConstant;
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

    @Option(names = "--iv", defaultValue = "gms", description = "WZ IV：gms（默认） / cms / latest")
    String ivName;

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
            changes = new DiffParser().parse(diffFile);
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

        WzKey key = buildKey(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName + "（可选：gms / cms / latest）");
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
        System.out.println(result.applied() + " applied, " + result.failed() + " failed. Output: " + outputImg + " (" + size + " bytes)");

        if (result.failed() > 0) return 1;
        return 0;
    }

    private static WzKey buildKey(String name) {
        return switch (name.toLowerCase()) {
            case "gms" -> new WzKey(1, "gms", WzAESConstant.WZ_GMS_IV, WzAESConstant.DEFAULT_KEY);
            case "cms" -> new WzKey(2, "cms", WzAESConstant.WZ_CMS_IV, WzAESConstant.DEFAULT_KEY);
            case "latest" -> new WzKey(3, "latest", WzAESConstant.WZ_LATEST_IV, WzAESConstant.DEFAULT_KEY);
            default -> null;
        };
    }
}

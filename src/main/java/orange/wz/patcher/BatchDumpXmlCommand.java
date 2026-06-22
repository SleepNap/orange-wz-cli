package orange.wz.patcher;

import orange.wz.provider.WzImageFile;
import orange.wz.provider.tools.MediaExportType;
import orange.wz.provider.tools.wzkey.WzKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * 批量 dump-xml：递归把 img 目录下所有 .img 转成 .xml。
 */
@Command(name = "batch-dump-xml", description = "批量把 .img 目录树导出为 .xml")
public final class BatchDumpXmlCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "客户端 img 根目录")
    Path imgDir;

    @Parameters(index = "1", description = "xml 输出根目录")
    Path outDir;

    @Option(names = "--iv", defaultValue = "GMS", description = "WZ IV：GMS / EMS / BMS / CLASSIC（大小写不敏感；cms/latest 为兼容别名）")
    String ivName;

    @Option(names = "--indent", defaultValue = "4", description = "缩进空格数")
    int indent;

    @Option(names = "--linux", description = "用 LF 换行（默认 CRLF）")
    boolean linux;

    @Override
    public Integer call() {
        if (!Files.isDirectory(imgDir)) {
            System.err.println("[err] img 目录不存在: " + imgDir);
            return 2;
        }
        WzKey key = IvSupport.resolve(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName + "（支持 GMS / EMS / BMS / CLASSIC）");
            return 2;
        }
        List<Path> imgs;
        try (Stream<Path> walk = Files.walk(imgDir)) {
            imgs = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".img"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            System.err.println("[err] 扫描 img 目录失败: " + e.getMessage());
            return 2;
        }

        int ok = 0, fail = 0;
        for (Path img : imgs) {
            Path rel = imgDir.toAbsolutePath().relativize(img.toAbsolutePath());
            Path outXml = outDir.resolve(rel.toString() + ".xml");
            try {
                Files.createDirectories(outXml.getParent());
            } catch (IOException e) {
                fail++;
                System.err.println("[err] 创建目录失败: " + outXml.getParent() + " — " + e.getMessage());
                continue;
            }
            WzImageFile imgFile = new WzImageFile(
                    img.getFileName().toString(),
                    img.toAbsolutePath().toString(),
                    key.getName(), key.getIv(), key.getUserKey()
            );
            if (!imgFile.parse()) {
                fail++;
                System.err.println("[err] img 解析失败: " + img);
                continue;
            }
            boolean dumped = imgFile.exportToXml(outXml.toAbsolutePath(), indent, MediaExportType.NONE, linux);
            if (!dumped) {
                fail++;
                System.err.println("[err] 导出失败: " + img);
                continue;
            }
            ok++;
            System.out.println("[ok] " + rel);
        }
        System.out.println();
        System.out.println("batch-dump-xml: " + ok + " ok, " + fail + " fail");
        if (fail > 0) return 1;
        return 0;
    }
}

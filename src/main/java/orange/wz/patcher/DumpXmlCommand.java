package orange.wz.patcher;

import orange.wz.provider.WzImageFile;
import orange.wz.provider.tools.MediaExportType;
import orange.wz.provider.tools.wzkey.WzKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * .img → .xml 导出子命令。供 patcher 验证使用：把 patched .img 转成 xml
 * 与服务端 upgrade 目录下的 xml 做 diff 对比。
 */
@Command(name = "dump-xml", description = "把 .img 文件导出为 XML")
public final class DumpXmlCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "源 .img 文件")
    Path inputImg;

    @Parameters(index = "1", description = "输出 .xml 文件")
    Path outputXml;

    @Option(names = "--iv", defaultValue = "GMS", description = "WZ IV：GMS / EMS / BMS / CLASSIC（大小写不敏感）")
    String ivName;

    @Option(names = "--indent", defaultValue = "4", description = "缩进空格数")
    int indent;

    @Option(names = "--linux", description = "用 LF 换行（默认 CRLF）")
    boolean linux;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(inputImg)) {
            System.err.println("[err] input.img 不存在: " + inputImg);
            return 2;
        }
        WzKey key = IvSupport.resolve(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName + "（支持 GMS / EMS / BMS / CLASSIC）");
            return 2;
        }
        WzImageFile img = new WzImageFile(
                inputImg.getFileName().toString(),
                inputImg.toAbsolutePath().toString(),
                key.getName(), key.getIv(), key.getUserKey()
        );
        if (!img.parse()) {
            System.err.println("[err] img 解析失败: " + inputImg);
            return 4;
        }
        try {
            Files.createDirectories(outputXml.toAbsolutePath().getParent());
        } catch (Exception e) {
            System.err.println("[err] 创建目录失败: " + e.getMessage());
            return 5;
        }
        // MediaExportType.NONE：跳过 PNG / Sound 等二进制资源（节点级语义对比不需要它们）
        boolean ok = img.exportToXml(outputXml.toAbsolutePath(), indent, MediaExportType.NONE, linux);
        if (!ok) {
            System.err.println("[err] 导出失败");
            return 5;
        }
        System.out.println("[ok] " + outputXml);
        return 0;
    }
}

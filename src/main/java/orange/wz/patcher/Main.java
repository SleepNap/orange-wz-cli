package orange.wz.patcher;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

@Command(
        name = "xml-img-patcher",
        mixinStandardHelpOptions = true,
        version = "xml-img-patcher 0.0.1",
        description = "把服务端 XML 的 git unified diff 应用到客户端 .img 文件，保留所有未触及的 PNG / Sound 等二进制资源。",
        subcommands = { PatchCommand.class, ExportXmlCommand.class }
)
public final class Main {

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");

        // 如果第一个参数不是已知子命令，默认走 patch（保持向后兼容）
        if (args.length > 0 && !args[0].equals("patch") && !args[0].equals("export-xml")
                && !args[0].equals("--help") && !args[0].equals("-h")
                && !args[0].equals("--version") && !args[0].equals("-V")) {
            String[] expanded = new String[args.length + 1];
            expanded[0] = "patch";
            System.arraycopy(args, 0, expanded, 1, args.length);
            args = expanded;
        }

        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}

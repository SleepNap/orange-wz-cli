package orange.wz.patcher;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@Command(
        name = "xml-img-patcher",
        mixinStandardHelpOptions = true,
        version = "xml-img-patcher 0.0.1",
        description = "把服务端 XML 的 git unified diff 应用到客户端 .img 文件，保留所有未触及的 PNG / Sound 等二进制资源。",
        subcommands = { PatchCommand.class, DumpXmlCommand.class, BatchCommand.class, VerifyCommand.class, BatchDumpXmlCommand.class }
)
public final class Main {

    private static final Set<String> KNOWN_COMMANDS = Set.of("patch", "dump-xml", "batch", "verify", "batch-dump-xml");

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");

        // 如果第一个参数不是已知子命令，报错
        if (args.length > 0 && !KNOWN_COMMANDS.contains(args[0])
                && !args[0].equals("--help") && !args[0].equals("-h")
                && !args[0].equals("--version") && !args[0].equals("-V")) {
            System.err.println("[err] unknown command: " + args[0]);
            System.err.println("      已知子命令: patch, dump-xml, batch, verify, batch-dump-xml");
            System.exit(2);
        }

        int exit = new CommandLine(new Main()).execute(args);
        System.exit(exit);
    }
}
package orange.wz.patcher;

import orange.wz.patcher.model.Change;
import orange.wz.patcher.model.ChangeOp;
import orange.wz.patcher.model.ValueType;
import orange.wz.patcher.parser.DiffParser;
import orange.wz.provider.WzImageFile;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;
import orange.wz.provider.properties.*;
import orange.wz.provider.tools.wzkey.WzKey;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 校验 patched .img：直接加载 img、把 diff 每条 + 行（Add / Modify）查找节点比对值；
 * DELETE 行查找节点是否已不存在。绕过 dump-xml 序列化，测的就是 img 的实际内容。
 */
@Command(name = "verify", description = "校验：patched .img 是否落实了 diff 要求的每条变更")
public final class VerifyCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "patched .img 文件")
    Path patchedImg;

    @Parameters(index = "1", description = "git unified diff 文件")
    Path diffFile;

    @Parameters(index = "2", arity = "0..1", description = "完整服务端 XML 或目录（与 diff 同布局），用来恢复 hunk 路径栈")
    Path fullXmlOrDir;

    @Option(names = "--iv", defaultValue = "GMS", description = "WZ IV：GMS / EMS / BMS / CLASSIC")
    String ivName;

    @Option(names = {"-v", "--verbose"}, description = "打印每条 ok / miss")
    boolean verbose;

    @Override
    public Integer call() {
        if (!Files.isRegularFile(patchedImg)) {
            System.err.println("[err] img 不存在: " + patchedImg);
            return 2;
        }
        if (!Files.isRegularFile(diffFile)) {
            System.err.println("[err] diff 不存在: " + diffFile);
            return 2;
        }
        WzKey key = IvSupport.resolve(ivName);
        if (key == null) {
            System.err.println("[err] 未知 IV: " + ivName);
            return 2;
        }

        Path fullXml = null;
        if (fullXmlOrDir != null) {
            if (Files.isRegularFile(fullXmlOrDir)) {
                fullXml = fullXmlOrDir;
            } else if (Files.isDirectory(fullXmlOrDir)) {
                // 用 diff 文件名（去 .diff）在目录里递归找
                String diffName = diffFile.getFileName().toString();
                if (diffName.endsWith(".diff")) {
                    String xmlName = diffName.substring(0, diffName.length() - ".diff".length());
                    try (var stream = Files.walk(fullXmlOrDir)) {
                        fullXml = stream.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().equals(xmlName))
                                .findFirst().orElse(null);
                    } catch (Exception ignored) {}
                }
                if (fullXml == null) {
                    System.err.println("[warn] 在 " + fullXmlOrDir + " 中未找到与 diff 配对的 xml，将不使用路径回退");
                }
            } else {
                System.err.println("[warn] full-xml 路径无效，忽略: " + fullXmlOrDir);
            }
        }

        List<Change> changes;
        try {
            changes = new DiffParser(fullXml).parse(diffFile);
        } catch (Exception e) {
            System.err.println("[err] diff 解析失败: " + e.getMessage());
            return 3;
        }

        WzImageFile img = new WzImageFile(
                patchedImg.getFileName().toString(),
                patchedImg.toAbsolutePath().toString(),
                key.getName(), key.getIv(), key.getUserKey()
        );
        if (!img.parse()) {
            System.err.println("[err] img 解析失败: " + patchedImg);
            return 4;
        }

        int total = 0, match = 0, miss = 0;
        for (Change c : changes) {
            // 只校验 Add / Modify / Delete
            if (c.op() == ChangeOp.ADD || c.op() == ChangeOp.MODIFY) {
                total++;
                WzObject node = resolve(img, c.path());
                if (node == null) {
                    miss++;
                    System.err.println("[miss] " + displayPath(img, c) + " — node not found");
                    continue;
                }
                String r = checkValue(node, c);
                if (r == null) {
                    match++;
                    if (verbose) System.out.println("[ok ] " + displayPath(img, c));
                } else {
                    miss++;
                    System.err.println("[miss] " + displayPath(img, c) + " — " + r);
                }
            } else if (c.op() == ChangeOp.DELETE) {
                total++;
                WzObject node = resolve(img, c.path());
                if (node == null) {
                    match++;
                    if (verbose) System.out.println("[ok ] " + displayPath(img, c) + " (deleted)");
                } else if (hasRebirthAdd(changes, c)) {
                    // 同 hunk 内 DELETE 后紧跟 ADD 重建同名节点（如 -<null/> + <string value=""/>），
                    // 节点形态被 ADD 覆盖，DELETE 的"节点消失"期望不适用。
                    match++;
                    if (verbose) System.out.println("[ok ] " + displayPath(img, c) + " (reborn by ADD)");
                } else {
                    miss++;
                    System.err.println("[miss] " + displayPath(img, c) + " — DELETE 节点仍存在");
                }
            }
        }
        System.out.println("verify: " + total + " expected, " + match + " match, " + miss + " miss");
        return miss == 0 ? 0 : 1;
    }

    /** 同 hunk 内是否存在 ADD 与给定 DELETE 同 path 的变更（rename / type-change 场景）。 */
    private boolean hasRebirthAdd(List<Change> all, Change del) {
        List<String> dp = del.path();
        for (Change c : all) {
            if (c == del) continue;
            if (c.op() != ChangeOp.ADD) continue;
            if (samePath(c.path(), dp)) return true;
        }
        return false;
    }

    private static boolean samePath(List<String> a, List<String> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).equals(b.get(i))) return false;
        }
        return true;
    }

    /** 把 path 解析到 img 树里的节点。允许 path[0] 是文件根名（自动剥掉）。 */
    private WzObject resolve(WzImageFile img, List<String> path) {
        if (path.isEmpty()) return null;
        int start = 0;
        if (path.get(0).equalsIgnoreCase(img.getName())) {
            start = 1;
        }
        WzObject cur = img;
        for (int i = start; i < path.size(); i++) {
            WzObject child = childByName(cur, path.get(i));
            if (child == null) return null;
            cur = child;
        }
        return cur;
    }

    private WzObject childByName(WzObject parent, String name) {
        if (parent instanceof orange.wz.provider.WzImage wi) return wi.getChild(name);
        if (parent instanceof WzImageProperty prop && prop.isListProperty()) return prop.getChild(name);
        return null;
    }

    private String displayPath(WzImageFile img, Change c) {
        List<String> p = c.path();
        if (!p.isEmpty() && p.get(0).equalsIgnoreCase(img.getName())) return c.displayPath();
        return img.getName() + "/" + c.displayPath();
    }

    /** 返回 null = 匹配；否则返回错误描述。 */
    private String checkValue(WzObject node, Change c) {
        if (c.type() == ValueType.SUB) {
            // 容器只看存在
            return null;
        }
        return switch (c.type()) {
            case STRING -> (node instanceof WzStringProperty s) ? cmpString(s.getValue(), c.value()) : typeMismatch(node, "string");
            case UOL    -> (node instanceof WzUOLProperty s) ? cmpString(s.getValue(), c.value()) : typeMismatch(node, "uol");
            case INT    -> (node instanceof WzIntProperty p) ? cmpNum(String.valueOf(p.getValue()), c.value()) : typeMismatch(node, "int");
            case SHORT  -> (node instanceof WzShortProperty p) ? cmpNum(String.valueOf(p.getValue()), c.value()) : typeMismatch(node, "short");
            case LONG   -> (node instanceof WzLongProperty p) ? cmpNum(String.valueOf(p.getValue()), c.value()) : typeMismatch(node, "long");
            case FLOAT  -> (node instanceof WzFloatProperty p) ? cmpNum(String.valueOf(p.getValue()), c.value()) : typeMismatch(node, "float");
            case DOUBLE -> (node instanceof WzDoubleProperty p) ? cmpNum(String.valueOf(p.getValue()), c.value()) : typeMismatch(node, "double");
            case VECTOR -> (node instanceof WzVectorProperty p)
                    ? (eqInt(p.getX(), c.x()) && eqInt(p.getY(), c.y()) ? null
                            : "vector 不匹配 want=(" + c.x() + "," + c.y() + ") got=(" + p.getX() + "," + p.getY() + ")")
                    : typeMismatch(node, "vector");
            case NULL   -> (node instanceof WzNullProperty) ? null : typeMismatch(node, "null");
            default     -> null;
        };
    }

    private static String cmpString(String got, String want) {
        String w = want == null ? "" : want;
        String g = got == null ? "" : got;
        return w.equals(g) ? null : "want=" + shortStr(w) + " got=" + shortStr(g);
    }

    private static String cmpNum(String got, String want) {
        try {
            return Double.parseDouble(got) == Double.parseDouble(want == null ? "0" : want) ? null
                    : "want=" + want + " got=" + got;
        } catch (Exception e) {
            return (want == null ? "" : want).equals(got) ? null : "want=" + want + " got=" + got;
        }
    }

    private static boolean eqInt(Integer a, Integer b) {
        return (a == null ? 0 : a) == (b == null ? 0 : b);
    }

    private static String typeMismatch(WzObject n, String want) {
        return "type mismatch: want " + want + " got " + n.getClass().getSimpleName();
    }

    private static String shortStr(String s) {
        if (s.length() <= 60) return "\"" + s.replace("\n", "\\n") + "\"";
        return "\"" + s.substring(0, 57).replace("\n", "\\n") + "...\"";
    }
}

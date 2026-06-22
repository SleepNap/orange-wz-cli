package orange.wz.patcher.parser;

import orange.wz.patcher.model.ValueType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析单行 XML（来自服务端导出的格式），抽出标签名、节点名和值。
 * 服务端 XML 的形态是固定的，例如：
 *   <imgdir name="29400">                              ← 容器开
 *   </imgdir>                                          ← 容器闭
 *   <string name="0" value="Hello"/>                   ← 自闭合叶子
 *   <int name="quest" value="1"/>
 *   <vector name="origin" x="-1" y="30"/>
 *   <canvas name="icon" width="31" height="30">        ← 容器开（canvas 也可能有子节点）
 */
public final class XmlLineParser {
    private static final Pattern NAME_ATTR = Pattern.compile("\\bname=\"([^\"]*)\"");
    private static final Pattern X_ATTR = Pattern.compile("\\bx=\"(-?\\d+)\"");
    private static final Pattern Y_ATTR = Pattern.compile("\\by=\"(-?\\d+)\"");

    private XmlLineParser() {
    }

    public static ParsedLine parse(String trimmed) {
        if (trimmed.isEmpty()) return null;
        // 容错：服务端 diff 偶发会输出残缺的 `-` 行——开头少一个 `<`，例如
        //   `string name="h1" value="..."/>`
        // 这里把它当成正常 `<string ...>` 处理，让 MODIFY 配对能匹配上对应的 `+` 行。
        if (!trimmed.startsWith("<")) {
            if (looksLikeMalformedLeaf(trimmed)) {
                trimmed = "<" + trimmed;
            } else {
                return null;
            }
        }
        if (trimmed.startsWith("<?")) return null;

        if (trimmed.startsWith("</")) {
            int gt = trimmed.indexOf('>');
            String tag = trimmed.substring(2, gt > 2 ? gt : trimmed.length()).trim();
            return new ParsedLine(tag, null, null, null, null, true, false);
        }

        // 抽出标签名
        int spIdx = indexOfAny(trimmed, 1, " \t/>");
        if (spIdx < 0) return null;
        String tag = trimmed.substring(1, spIdx);
        boolean selfClose = trimmed.endsWith("/>");

        String name = matchGroup(NAME_ATTR, trimmed);
        String value = extractValueAttr(trimmed);
        if (value != null) value = unescapeXml(value);
        Integer x = parseIntOrNull(matchGroup(X_ATTR, trimmed));
        Integer y = parseIntOrNull(matchGroup(Y_ATTR, trimmed));

        return new ParsedLine(tag, name, value, x, y, false, selfClose);
    }

    public static ValueType tagToType(String tag) {
        return switch (tag) {
            case "imgdir" -> ValueType.SUB;
            case "string" -> ValueType.STRING;
            case "int" -> ValueType.INT;
            case "short" -> ValueType.SHORT;
            case "long" -> ValueType.LONG;
            case "float" -> ValueType.FLOAT;
            case "double" -> ValueType.DOUBLE;
            case "vector" -> ValueType.VECTOR;
            case "uol" -> ValueType.UOL;
            case "null" -> ValueType.NULL;
            // canvas / sound / convex 等服务端 XML 不应出现于 diff 改动中；遇到当作不支持
            default -> ValueType.UNSUPPORTED;
        };
    }

    /**
     * 判定残缺开头 `<` 的行是否仍像一个合法的 self-closing 叶子。
     * 形如 `string name="h1" value="..."/>` —— 以已知 tag 名打头、有 name= 属性、以 `/>` 结尾。
     */
    private static boolean looksLikeMalformedLeaf(String s) {
        if (!s.endsWith("/>")) return false;
        int sp = indexOfAny(s, 0, " \t/>");
        if (sp <= 0) return false;
        String tag = s.substring(0, sp);
        if (tagToType(tag) == ValueType.UNSUPPORTED) return false;
        return s.indexOf(" name=\"") >= 0 || s.startsWith("name=\"");
    }

    private static int indexOfAny(String s, int from, String chars) {
        int min = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = s.indexOf(chars.charAt(i), from);
            if (idx >= 0 && (min < 0 || idx < min)) min = idx;
        }
        return min;
    }

    private static String matchGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 手动扫描 value="..."，避开正则在长字符串上的回溯爆栈。
     * 服务端 XML 的 value 中不会有未转义的 "（已被 &amp;quot; 转义），
     * 所以 value 的结束就是下一个 "。
     */
    private static String extractValueAttr(String s) {
        int idx = s.indexOf("value=\"");
        if (idx < 0) return null;
        // 确保前面是空白或 < 后的 word boundary
        if (idx > 0) {
            char prev = s.charAt(idx - 1);
            if (Character.isLetterOrDigit(prev) || prev == '_') return null;
        }
        int start = idx + 7;
        int end = s.indexOf('"', start);
        if (end < 0) return null;
        return s.substring(start, end);
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String unescapeXml(String s) {
        if (s.indexOf('&') < 0) return s;
        // 先把已命名实体替换；再处理数字字符引用 &#xHH; / &#NNN;
        String out = s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        out = expandNumericEntities(out);
        // &amp; 必须最后处理，避免把 &amp;#xD; 这样的双重转义错误展开
        return out.replace("&amp;", "&");
    }

    /** 把 &#xHH; / &#NNN; 数字字符引用展开成对应字符。无效引用原样保留。 */
    private static String expandNumericEntities(String s) {
        int idx = s.indexOf("&#");
        if (idx < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int prev = 0;
        while (idx >= 0) {
            sb.append(s, prev, idx);
            int semi = s.indexOf(';', idx + 2);
            if (semi < 0) {
                prev = idx; break;
            }
            String body = s.substring(idx + 2, semi);
            int code = -1;
            try {
                if (!body.isEmpty() && (body.charAt(0) == 'x' || body.charAt(0) == 'X')) {
                    code = Integer.parseInt(body.substring(1), 16);
                } else if (!body.isEmpty()) {
                    code = Integer.parseInt(body);
                }
            } catch (NumberFormatException ignored) {
            }
            if (code >= 0 && code <= 0x10FFFF) {
                sb.appendCodePoint(code);
                prev = semi + 1;
            } else {
                sb.append(s, idx, semi + 1);
                prev = semi + 1;
            }
            idx = s.indexOf("&#", prev);
        }
        sb.append(s, prev, s.length());
        return sb.toString();
    }

    /**
     * @param tag 标签名（不含 < / >）
     * @param name 节点名（name 属性），容器闭合行时为 null
     * @param value value 属性
     * @param x vector 的 x
     * @param y vector 的 y
     * @param closing true 表示这是 </imgdir> 等闭合标签
     * @param selfClose true 表示形如 <int .../>
     */
    public record ParsedLine(
            String tag,
            String name,
            String value,
            Integer x,
            Integer y,
            boolean closing,
            boolean selfClose
    ) {
    }
}

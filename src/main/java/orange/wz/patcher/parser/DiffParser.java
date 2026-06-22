package orange.wz.patcher.parser;

import lombok.extern.slf4j.Slf4j;
import orange.wz.patcher.model.Change;
import orange.wz.patcher.model.ChangeOp;
import orange.wz.patcher.model.SubTree;
import orange.wz.patcher.model.ValueType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 git unified diff，把对服务端 XML 的变更展开成 List&lt;Change&gt;。
 *
 * 算法：
 * 维护两个独立的栈：
 *   - leftStack：还原"左侧文档"（diff 之前）的 imgdir 嵌套
 *   - rightStack：还原"右侧文档"（diff 之后）的 imgdir 嵌套
 * 它们通过 context 行（` ` 前缀）共同前进；
 *   - `-` 行只影响 leftStack
 *   - `+` 行只影响 rightStack
 *
 * 当 hunk 上下文里没有外层容器开标签（短 hunk 切在 imgdir 中部）时，rightStack 会一直空，
 * 解析出来的 path 就是单层叶子名，到 .img 里多解或找不到。fullXml 参数可选地指向"完整服务端 XML"
 * （diff 的 +++ 那一侧的最终文件），parser 用 hunk header 的 +N 行号去那个 XML 文件里扫出
 * 第 N-1 行所属的 imgdir 嵌套栈，作为该 hunk 的起始栈，从而恢复完整路径。
 */
@Slf4j
public final class DiffParser {

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@\\s+-(\\d+)(?:,\\d+)?\\s+\\+(\\d+)(?:,\\d+)?\\s+@@");

    private final List<String> fullXmlLines; // null 表示未提供

    public DiffParser() {
        this(null);
    }

    public DiffParser(Path fullXml) {
        if (fullXml != null) {
            try {
                this.fullXmlLines = Files.readAllLines(fullXml, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("读取完整 XML 失败: " + fullXml + " — " + e.getMessage(), e);
            }
        } else {
            this.fullXmlLines = null;
        }
    }

    public List<Change> parse(Path diffFile) throws IOException {
        List<String> lines = Files.readAllLines(diffFile, StandardCharsets.UTF_8);
        return parse(lines);
    }

    public List<Change> parse(List<String> lines) {
        List<Change> changes = new ArrayList<>();

        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (line.startsWith("@@ ")) {
                int newLineStart = parseHunkNewStart(line);
                int hunkStart = i + 1;
                int hunkEnd = hunkStart;
                while (hunkEnd < lines.size() && !lines.get(hunkEnd).startsWith("@@ ")
                        && !lines.get(hunkEnd).startsWith("diff --git")) {
                    hunkEnd++;
                }
                List<String> seed = newLineStart > 0 ? seedStackFromFullXml(newLineStart) : Collections.emptyList();
                // 服务端导出 diff 时偶尔会把含有字面 `\n` 的 value 字段写成跨多行的 - 或 + 块（例如
                //   -    <string name="0" value="...long text
                //   -"/>
                // ）。这种行会让 XmlLineParser 在第二行解析失败，导致 left/right 栈错位、
                // 后续同 hunk 的 ADD/MODIFY 全部路径错位。这里在喂给 processHunk 之前先把它们合成单行。
                List<String> merged = mergeContinuedValueLines(lines.subList(hunkStart, hunkEnd));
                processHunk(merged, hunkStart, seed, changes);
                i = hunkEnd;
                continue;
            }
            i++;
        }

        return changes;
    }

    /**
     * 把 hunk 行里被字面换行截断的 value 重新合成单行。
     * 同 prefix（' ' / '-' / '+'）的连续行里，若前一行打开了 value="…… 但没闭合，
     * 把下一行的 body 追加进来（用真实换行 \n 连接），直到看见闭合的 `"`。
     */
    private List<String> mergeContinuedValueLines(List<String> hunk) {
        List<String> out = new ArrayList<>(hunk.size());
        StringBuilder buf = null;
        char carry = 0;
        for (String line : hunk) {
            if (line.isEmpty()) {
                if (buf != null) {
                    // 字面换行刚好对应一空行，按真实换行接上
                    buf.append('\n');
                    continue;
                }
                out.add(line);
                continue;
            }
            char prefix = line.charAt(0);
            if (buf != null) {
                if (prefix != carry) {
                    // prefix 不一致：放弃合并，把已积累的吐出去再重新处理当前行
                    out.add(buf.toString());
                    buf = null;
                    carry = 0;
                } else {
                    String body = line.length() > 1 ? line.substring(1) : "";
                    buf.append('\n').append(body);
                    if (isValueAttrClosed(buf.substring(1))) {
                        out.add(buf.toString());
                        buf = null;
                        carry = 0;
                    }
                    continue;
                }
            }
            if (prefix == ' ' || prefix == '-' || prefix == '+') {
                String body = line.substring(1);
                if (hasUnclosedValueAttr(body)) {
                    buf = new StringBuilder(line);
                    carry = prefix;
                    continue;
                }
            }
            out.add(line);
        }
        if (buf != null) out.add(buf.toString());
        return out;
    }

    /** 行内 value="…… 没有以 " 闭合。判断方式：找到第一个 value="，看后面有没有未转义的 "。 */
    private static boolean hasUnclosedValueAttr(String body) {
        int idx = body.indexOf("value=\"");
        if (idx < 0) return false;
        int end = body.indexOf('"', idx + 7);
        return end < 0;
    }

    /** 合并后的 body 是否已经把 value 引号闭合。 */
    private static boolean isValueAttrClosed(String body) {
        return !hasUnclosedValueAttr(body);
    }

    private static int parseHunkNewStart(String headerLine) {
        Matcher m = HUNK_HEADER.matcher(headerLine);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 用完整 XML 文件的前 (newLineStart - 1) 行重建 imgdir 栈。
     * 仅看 imgdir 的开/关：开标签压栈，闭标签出栈，自闭合不动栈。
     * 返回栈底→栈顶的列表（即根→当前 imgdir 路径）。
     */
    private List<String> seedStackFromFullXml(int newLineStart) {
        if (fullXmlLines == null) return Collections.emptyList();
        Deque<String> stack = new ArrayDeque<>();
        int upTo = Math.min(newLineStart - 1, fullXmlLines.size());
        for (int idx = 0; idx < upTo; idx++) {
            String raw = fullXmlLines.get(idx);
            String trimmed = raw.stripLeading();
            XmlLineParser.ParsedLine parsed = XmlLineParser.parse(trimmed);
            if (parsed == null) continue;
            applyStructuralRaw(stack, parsed);
        }
        // ArrayDeque 的迭代顺序是栈顶 → 栈底；我们要根 → 叶
        List<String> reversed = new ArrayList<>(stack.size());
        for (String s : stack) reversed.add(s);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private void processHunk(List<String> hunkLines, int firstLineNumber, List<String> seedPath, List<Change> out) {
        // 把 hunk 内每一行解析成 (kind, parsed, lineNumber)，并预先算好 leftPath 和 rightPath
        Deque<String> leftStack = new ArrayDeque<>();
        Deque<String> rightStack = new ArrayDeque<>();
        // seedPath 是根→叶顺序（如 [Skill.img, 0001005]）。
        // 第一个元素是文件根 <imgdir name="X.img">，对应 WzImage 自身，不出现在内部节点路径里 → 跳过。
        // 栈的约定是 head=最深、tail=根，所以按 root→leaf 顺序逐个 push：
        //   push(root) → [root]
        //   push(child) → [child, root]   ← head=child=最深 ✓
        for (int s = 1; s < seedPath.size(); s++) {
            leftStack.push(seedPath.get(s));
            rightStack.push(seedPath.get(s));
        }

        List<HunkRow> rows = new ArrayList<>(hunkLines.size());
        for (int idx = 0; idx < hunkLines.size(); idx++) {
            String line = hunkLines.get(idx);
            if (line.isEmpty()) continue;
            char prefix = line.charAt(0);
            String body = line.length() > 1 ? line.substring(1) : "";
            if (prefix != ' ' && prefix != '+' && prefix != '-') {
                continue; // 忽略 \ No newline 等
            }

            String trimmed = body.stripLeading();
            XmlLineParser.ParsedLine parsed = XmlLineParser.parse(trimmed);

            // 记录"操作前"的栈快照
            List<String> leftPath = stackToPath(leftStack);
            List<String> rightPath = stackToPath(rightStack);

            // 应用结构变化
            if (parsed != null) {
                if (prefix == ' ' || prefix == '-') applyStructuralRaw(leftStack, parsed);
                if (prefix == ' ' || prefix == '+') applyStructuralRaw(rightStack, parsed);
            }

            rows.add(new HunkRow(prefix, body, parsed, leftPath, rightPath, firstLineNumber + idx));
        }

        // 1) MODIFY 配对：跨 +/- 行，要求 rightPath/leftPath + name + tag 全相等
        boolean[] used = new boolean[rows.size()];
        for (int mi = 0; mi < rows.size(); mi++) {
            HunkRow m = rows.get(mi);
            if (used[mi] || m.prefix() != '-' || !isLeaf(m.parsed())) continue;
            for (int pi = 0; pi < rows.size(); pi++) {
                if (used[pi]) continue;
                HunkRow p = rows.get(pi);
                if (p.prefix() != '+' || !isLeaf(p.parsed())) continue;
                if (!sameNameAndTag(m.parsed(), p.parsed())) continue;
                if (!m.leftPath().equals(p.rightPath())) continue;

                ValueType vt = XmlLineParser.tagToType(p.parsed().tag());
                if (vt != ValueType.UNSUPPORTED) {
                    List<String> path = new ArrayList<>(p.rightPath());
                    path.add(p.parsed().name());
                    out.add(new Change(
                            ChangeOp.MODIFY,
                            path,
                            vt,
                            p.parsed().value(),
                            p.parsed().x(),
                            p.parsed().y(),
                            null,
                            p.lineNumber()
                    ));
                }
                used[mi] = true;
                used[pi] = true;
                break;
            }
        }

        // 2) DELETE：剩余 - 行
        for (int mi = 0; mi < rows.size(); mi++) {
            if (used[mi]) continue;
            HunkRow m = rows.get(mi);
            if (m.prefix() != '-') continue;
            if (m.parsed() == null || m.parsed().closing()) continue;
            if (m.parsed().name() == null) continue;
            // 同 hunk 内若有 + 行重建同名节点（rename 形态：先 - 一个容器开标签，再 +
            // 同名容器在新父下，例如 wz/Quest.wz/Act.img 的 29509 → 29580/29509），
            // 跳过这条 DELETE。否则会把后续 ADD 子树连根铲掉，造成节点丢失。
            String deletedName = m.parsed().name();
            boolean reborn = false;
            for (int qi = 0; qi < rows.size(); qi++) {
                if (used[qi]) continue;
                HunkRow q = rows.get(qi);
                if (q.prefix() != '+') continue;
                if (q.parsed() == null || q.parsed().closing()) continue;
                if (deletedName.equals(q.parsed().name())
                        && matchesContainerOpen(q.parsed().tag())
                        && matchesContainerOpen(m.parsed().tag())) {
                    reborn = true;
                    break;
                }
            }
            if (reborn) {
                used[mi] = true;
                continue;
            }
            List<String> path = new ArrayList<>(m.leftPath());
            path.add(m.parsed().name());
            out.add(new Change(
                    ChangeOp.DELETE,
                    path,
                    XmlLineParser.tagToType(m.parsed().tag()),
                    null, null, null, null,
                    m.lineNumber()
            ));
        }

        // 3) ADD：剩余 + 行 —— 容器开标签会吸收到匹配 </imgdir>，闭合可能在 context 行
        int pi = 0;
        while (pi < rows.size()) {
            HunkRow p = rows.get(pi);
            if (used[pi] || p.prefix() != '+') {
                pi++;
                continue;
            }
            if (p.parsed() == null || p.parsed().closing() || p.parsed().name() == null) {
                pi++;
                continue;
            }

            if (!matchesContainerOpen(p.parsed().tag())) {
                ValueType vt = XmlLineParser.tagToType(p.parsed().tag());
                if (vt == ValueType.UNSUPPORTED) {
                    log.warn("[diff:{}] 不支持的节点类型 <{}>，跳过", p.lineNumber(), p.parsed().tag());
                } else {
                    SubTree leaf = new SubTree(p.parsed().name(), vt, p.parsed().value(), p.parsed().x(), p.parsed().y());
                    List<String> path = new ArrayList<>(p.rightPath());
                    path.add(p.parsed().name());
                    out.add(new Change(
                            ChangeOp.ADD,
                            path,
                            vt,
                            p.parsed().value(),
                            p.parsed().x(),
                            p.parsed().y(),
                            leaf,
                            p.lineNumber()
                    ));
                }
                used[pi] = true;
                pi++;
                continue;
            }

            // 容器开标签：往后扫描直到匹配的 </imgdir>，期间允许跨 context 行
            // （context 行同时关闭左/右栈；右栈的关闭点就是容器结束点）
            SubTree root = new SubTree(p.parsed().name(), ValueType.SUB, null, null, null);
            Deque<SubTree> subStack = new ArrayDeque<>();
            subStack.push(root);
            int depth = 1;
            int qi = pi + 1;
            int lastConsumed = pi;
            while (qi < rows.size() && depth > 0) {
                HunkRow q = rows.get(qi);
                if (q.prefix() == '-') {
                    // 左侧独有的行不影响右文档子树构造
                    qi++;
                    continue;
                }
                if (q.parsed() == null) {
                    qi++;
                    continue;
                }
                if (q.parsed().closing()) {
                    if (matchesContainerOpen(q.parsed().tag())) {
                        depth--;
                        if (q.prefix() == '+') {
                            used[qi] = true;
                            lastConsumed = qi;
                        }
                        if (depth == 0) {
                            qi++;
                            break;
                        }
                        if (!subStack.isEmpty()) subStack.pop();
                    }
                    qi++;
                    continue;
                }
                if (q.parsed().name() == null) {
                    qi++;
                    continue;
                }
                if (!matchesContainerOpen(q.parsed().tag())) {
                    ValueType vt = XmlLineParser.tagToType(q.parsed().tag());
                    if (vt != ValueType.UNSUPPORTED && !subStack.isEmpty()) {
                        SubTree leaf = new SubTree(q.parsed().name(), vt, q.parsed().value(), q.parsed().x(), q.parsed().y());
                        subStack.peek().addChild(leaf);
                    }
                    if (q.prefix() == '+') {
                        used[qi] = true;
                        lastConsumed = qi;
                    }
                    qi++;
                    continue;
                }
                // 嵌套容器开
                SubTree child = new SubTree(q.parsed().name(), ValueType.SUB, null, null, null);
                if (!subStack.isEmpty()) subStack.peek().addChild(child);
                if (q.prefix() == '+') {
                    used[qi] = true;
                    lastConsumed = qi;
                }
                // 自闭合容器（<imgdir name="0"/>）作为空容器一次到位，不入栈也不加深 depth
                if (q.parsed().selfClose()) {
                    qi++;
                    continue;
                }
                subStack.push(child);
                depth++;
                qi++;
            }

            List<String> path = new ArrayList<>(p.rightPath());
            path.add(root.name());
            out.add(new Change(
                    ChangeOp.ADD,
                    path,
                    ValueType.SUB,
                    null, null, null,
                    root,
                    p.lineNumber()
            ));
            used[pi] = true;
            pi = lastConsumed + 1;
        }
    }

    private void applyStructuralRaw(Deque<String> stack, XmlLineParser.ParsedLine parsed) {
        if (parsed.closing()) {
            if (matchesContainerOpen(parsed.tag())) {
                if (!stack.isEmpty()) stack.pop();
            }
            return;
        }
        if (parsed.selfClose()) return;
        if (matchesContainerOpen(parsed.tag()) && parsed.name() != null) {
            stack.push(parsed.name());
        }
    }

    private boolean matchesContainerOpen(String tag) {
        return "imgdir".equals(tag) || "canvas".equals(tag) || "convex".equals(tag);
    }

    private boolean isLeaf(XmlLineParser.ParsedLine p) {
        if (p == null) return false;
        if (p.closing()) return false;
        if (matchesContainerOpen(p.tag())) return false;
        return p.selfClose() || p.value() != null;
    }

    private boolean sameNameAndTag(XmlLineParser.ParsedLine a, XmlLineParser.ParsedLine b) {
        if (a == null || b == null) return false;
        if (a.name() == null || !a.name().equals(b.name())) return false;
        return a.tag().equals(b.tag());
    }

    private List<String> stackToPath(Deque<String> stack) {
        List<String> reversed = new ArrayList<>(stack.size());
        for (String s : stack) reversed.add(s);
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    private record HunkRow(
            char prefix,
            String body,
            XmlLineParser.ParsedLine parsed,
            List<String> leftPath,
            List<String> rightPath,
            int lineNumber
    ) {
    }
}

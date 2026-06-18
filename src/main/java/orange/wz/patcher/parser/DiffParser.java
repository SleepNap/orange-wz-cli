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
import java.util.Deque;
import java.util.List;

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
 * Hunk 整段一次处理：按行号顺序扫描，维护两个栈，同时把"待发出"的 -/+ 行
 * 按近邻聚合后做 MODIFY 配对、容器吸收，最终生成有序的 Change 列表。
 *
 * 容器吸收（imgdir 块）允许跨 context 行：右文档结构变化里 `+&lt;imgdir name="X"&gt;` 的
 * 配对 `&lt;/imgdir&gt;` 可能出现在某个 context 行中——这种情况下 context 同时关闭左右栈，
 * 我们仍把整段视为新增容器子树，并把其中包含的 +/context 子节点都作为子树的一部分。
 */
@Slf4j
public final class DiffParser {

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
                int hunkStart = i + 1;
                int hunkEnd = hunkStart;
                while (hunkEnd < lines.size() && !lines.get(hunkEnd).startsWith("@@ ")
                        && !lines.get(hunkEnd).startsWith("diff --git")) {
                    hunkEnd++;
                }
                processHunk(lines.subList(hunkStart, hunkEnd), hunkStart, changes);
                i = hunkEnd;
                continue;
            }
            i++;
        }

        return changes;
    }

    private void processHunk(List<String> hunkLines, int firstLineNumber, List<Change> out) {
        // 把 hunk 内每一行解析成 (kind, parsed, lineNumber)，并预先算好 leftPath 和 rightPath
        Deque<String> leftStack = new ArrayDeque<>();
        Deque<String> rightStack = new ArrayDeque<>();

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
                subStack.push(child);
                depth++;
                if (q.prefix() == '+') {
                    used[qi] = true;
                    lastConsumed = qi;
                }
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

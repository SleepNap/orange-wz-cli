package orange.wz.patcher.patch;

import lombok.extern.slf4j.Slf4j;
import orange.wz.patcher.model.Change;
import orange.wz.patcher.model.SubTree;
import orange.wz.patcher.model.ValueType;
import orange.wz.provider.WzImage;
import orange.wz.provider.WzImageFile;
import orange.wz.provider.WzImageProperty;
import orange.wz.provider.WzObject;
import orange.wz.provider.properties.*;
import orange.wz.provider.tools.wzkey.WzKey;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 加载 .img 文件，把 List&lt;Change&gt; 应用到节点树，再保存。
 */
@Slf4j
public final class ImgPatcher {
    private final WzKey key;
    private int ok;
    private int err;
    private final List<String> messages = new ArrayList<>();
    private final boolean strict;
    private final boolean verbose;

    public ImgPatcher(WzKey key, boolean strict, boolean verbose) {
        this.key = key;
        this.strict = strict;
        this.verbose = verbose;
    }

    public Result patch(Path inputImg, List<Change> changes, Path outputImg, boolean dryRun) {
        WzImageFile imgFile = new WzImageFile(
                inputImg.getFileName().toString(),
                inputImg.toAbsolutePath().toString(),
                key.getName(), key.getIv(), key.getUserKey()
        );
        if (!imgFile.parse()) {
            return new Result(false, 0, 0, "img 解析失败: " + inputImg, messages);
        }

        for (Change c : changes) {
            try {
                switch (c.op()) {
                    case MODIFY -> applyModify(imgFile, c);
                    case ADD -> applyAdd(imgFile, c);
                    case DELETE -> applyDelete(imgFile, c);
                }
                ok++;
                addLog(formatOk(c));
            } catch (RuntimeException e) {
                err++;
                addLog("[err] " + c.op() + " " + c.displayPath() + " — " + e.getMessage());
                if (strict) {
                    return new Result(false, ok, err, "strict 模式下中止: " + e.getMessage(), messages);
                }
            }
        }

        if (dryRun) {
            return new Result(err == 0, ok, err, "dry-run，未写入", messages);
        }

        imgFile.setChanged(true);
        imgFile.setFilePath(outputImg.toAbsolutePath().toString());
        if (!imgFile.save()) {
            return new Result(false, ok, err, "img 写入失败: " + outputImg, messages);
        }
        return new Result(err == 0, ok, err, null, messages);
    }

    private void applyModify(WzImage img, Change c) {
        WzObject node = resolve(img, c.path());
        if (node == null) {
            // 1) 整树后缀匹配：hunk 起始的 context 不够，path 可能是真实路径的后缀
            //    （例如真实 portal/12/tm 在 hunk 中只能恢复成 12/tm）。
            //    若树中存在唯一一个以 c.path() 为后缀的节点，且类型匹配，则用它。
            WzObject suffixMatch = resolveBySuffix(img, c.path(), c.type());
            if (suffixMatch != null) {
                applyValue(suffixMatch, c);
                markChanged(suffixMatch);
                return;
            }
            // 2) 都找不到 → 报错，不再强行 ADD，避免在错误位置创建节点污染 img。
            //    （服务端 XML 是"瘦"的，hunk 上下文不足时无法可靠定位完整路径。）
            throw new RuntimeException("节点不存在或路径不唯一: " + c.displayPath());
        }
        applyValue(node, c);
        markChanged(node);
    }

    /**
     * 在整 img 树中查找"路径以 wantedPath 结尾"且类型符合的节点。
     * 用于 hunk context 不足以恢复完整路径时的回退。如果命中数量不为 1 则返回 null（不冒险）。
     */
    private WzObject resolveBySuffix(WzImage root, List<String> wantedPath, ValueType wantedType) {
        if (wantedPath.isEmpty()) return null;
        List<WzObject> matches = new ArrayList<>();
        searchBySuffix(root, wantedPath, wantedType, matches, 2);
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void searchBySuffix(WzObject node, List<String> wantedPath, ValueType wantedType, List<WzObject> out, int maxMatches) {
        if (out.size() >= maxMatches) return;
        if (matchesPathSuffix(node, wantedPath) && matchesType(node, wantedType)) {
            out.add(node);
            // 不 return：兄弟可能也匹配，但有 maxMatches 限制
        }
        List<? extends WzObject> children;
        if (node instanceof WzImage img) children = img.getChildren();
        else if (node instanceof WzImageProperty prop && prop.isListProperty()) children = prop.getChildren();
        else return;
        for (WzObject child : children) {
            searchBySuffix(child, wantedPath, wantedType, out, maxMatches);
        }
    }

    private boolean matchesPathSuffix(WzObject node, List<String> wantedPath) {
        WzObject cur = node;
        for (int i = wantedPath.size() - 1; i >= 0; i--) {
            if (cur == null) return false;
            if (!wantedPath.get(i).equalsIgnoreCase(cur.getName())) return false;
            cur = cur.getParent();
        }
        return true;
    }

    private boolean matchesType(WzObject node, ValueType wantedType) {
        if (wantedType == null) return true;
        return switch (wantedType) {
            case STRING -> node instanceof WzStringProperty;
            case INT -> node instanceof WzIntProperty;
            case SHORT -> node instanceof WzShortProperty;
            case LONG -> node instanceof WzLongProperty;
            case FLOAT -> node instanceof WzFloatProperty;
            case DOUBLE -> node instanceof WzDoubleProperty;
            case VECTOR -> node instanceof WzVectorProperty;
            case UOL -> node instanceof WzUOLProperty;
            case NULL -> node instanceof WzNullProperty;
            case SUB -> node instanceof WzListProperty || node instanceof WzImage;
            default -> false;
        };
    }

    /** MODIFY 找不到节点时使用：把缺失的中间路径全部新建出来，最后挂上叶子。 */
    private void applyAddByPath(WzImage img, Change c) {
        if (c.path().isEmpty()) {
            throw new RuntimeException("路径为空");
        }
        // 找最深存在的祖先
        WzObject ancestor = img;
        int matched = 0;
        for (String segment : c.path()) {
            WzObject child = childByName(ancestor, segment);
            if (child == null) break;
            ancestor = child;
            matched++;
        }
        // 从 matched 一直到末尾倒数第二个 segment 都需要建为容器；最后一个建为叶子
        WzObject cur = ancestor;
        for (int i = matched; i < c.path().size() - 1; i++) {
            WzListProperty container = new WzListProperty(c.path().get(i), null, img);
            addChild(cur, container);
            markChanged(container);
            cur = container;
        }
        String leafName = c.path().get(c.path().size() - 1);
        WzImageProperty leaf = buildLeaf(leafName, c.type(), c.value(), c.x(), c.y(), img);
        addChild(cur, leaf);
        markChanged(leaf);
    }

    private void applyAdd(WzImage img, Change c) {
        if (c.path().isEmpty()) {
            throw new RuntimeException("ADD 路径为空");
        }
        List<String> parentPath = c.path().subList(0, c.path().size() - 1);
        WzObject parent = resolve(img, parentPath);
        if (parent == null) {
            throw new RuntimeException("父节点不存在: " + String.join("/", parentPath));
        }
        String name = c.path().get(c.path().size() - 1);
        WzObject existing = childByName(parent, name);
        if (existing != null) {
            // 节点已存在（客户端 .img 通常比服务端 XML 更全，diff 看似新增、实际已有）。
            // 把整棵子树 merge 进去：缺失的子节点新建，已存在的递归合并；不覆盖叶子值。
            if (c.subTree() != null) {
                mergeSubTree(existing, c.subTree());
            }
            // 若是叶子 ADD 且节点已存在，且类型相符，则不动（不覆盖既有值）
            return;
        }
        WzImageProperty newNode;
        if (c.subTree() != null) {
            newNode = buildFromSubTree(c.subTree(), img);
        } else {
            newNode = buildLeaf(name, c.type(), c.value(), c.x(), c.y(), img);
        }
        addChild(parent, newNode);
        markChanged(newNode);
    }

    /**
     * 把 SubTree 合并到已有节点：
     * - SubTree 是容器：existing 也应是容器（WzListProperty/WzImage），递归 merge 子节点
     * - SubTree 是叶子：什么都不做（不覆盖既有值，因为客户端原值通常更完整）
     */
    private void mergeSubTree(WzObject existing, SubTree tree) {
        if (tree.type() != ValueType.SUB) {
            // 叶子：不覆盖
            return;
        }
        for (SubTree child : tree.children()) {
            WzObject existingChild = childByName(existing, child.name());
            if (existingChild != null) {
                mergeSubTree(existingChild, child);
                continue;
            }
            // 缺失：新建并加入
            WzImageProperty newNode = buildFromSubTree(child, getWzImage(existing));
            addChild(existing, newNode);
            markChanged(newNode);
        }
    }

    private WzImage getWzImage(WzObject node) {
        if (node instanceof WzImage img) return img;
        if (node instanceof WzImageProperty prop) return prop.getWzImage();
        return null;
    }

    private void applyDelete(WzImage img, Change c) {
        if (c.path().isEmpty()) {
            throw new RuntimeException("DELETE 路径为空");
        }
        List<String> parentPath = c.path().subList(0, c.path().size() - 1);
        WzObject parent = resolve(img, parentPath);
        if (parent == null) {
            // 父节点都不存在，那要删的节点自然也不存在；视为成功
            return;
        }
        String name = c.path().get(c.path().size() - 1);
        // 节点不存在也视为成功（幂等）
        removeChild(parent, name);
    }

    private WzObject resolve(WzImage root, List<String> path) {
        WzObject cur = root;
        for (String segment : path) {
            WzObject child = childByName(cur, segment);
            if (child == null) return null;
            cur = child;
        }
        return cur;
    }

    private WzObject childByName(WzObject parent, String name) {
        if (parent instanceof WzImage img) return img.getChild(name);
        if (parent instanceof WzImageProperty prop && prop.isListProperty()) return prop.getChild(name);
        return null;
    }

    private void addChild(WzObject parent, WzImageProperty child) {
        if (parent instanceof WzImage img) {
            if (!img.addChild(child)) throw new RuntimeException("addChild 失败: " + child.getName());
        } else if (parent instanceof WzImageProperty prop && prop.isListProperty()) {
            if (!prop.addChild(child)) throw new RuntimeException("addChild 失败: " + child.getName());
        } else {
            throw new RuntimeException("不支持的父节点类型: " + parent.getClass().getSimpleName());
        }
    }

    private boolean removeChild(WzObject parent, String name) {
        if (parent instanceof WzImage img) return img.removeChild(name);
        if (parent instanceof WzImageProperty prop && prop.isListProperty()) return prop.removeChild(name);
        return false;
    }

    private void applyValue(WzObject node, Change c) {
        switch (node) {
            case WzStringProperty p -> p.setValue(c.value() == null ? "" : c.value());
            case WzIntProperty p -> p.setValue(parseInt(c.value()));
            case WzShortProperty p -> p.setValue(parseShort(c.value()));
            case WzLongProperty p -> p.setValue(parseLong(c.value()));
            case WzFloatProperty p -> p.setValue(parseFloat(c.value()));
            case WzDoubleProperty p -> p.setValue(parseDouble(c.value()));
            case WzUOLProperty p -> p.setValue(c.value() == null ? "" : c.value());
            case WzVectorProperty p -> {
                if (c.x() != null) p.setX(c.x());
                if (c.y() != null) p.setY(c.y());
            }
            case WzNullProperty p -> { /* null 节点没有值 */ }
            default -> throw new RuntimeException("不支持修改的节点类型: " + node.getClass().getSimpleName());
        }
    }

    private WzImageProperty buildFromSubTree(SubTree tree, WzImage img) {
        if (tree.type() == ValueType.SUB) {
            WzListProperty container = new WzListProperty(tree.name(), null, img);
            for (SubTree child : tree.children()) {
                container.addChild(buildFromSubTree(child, img));
            }
            return container;
        }
        return buildLeaf(tree.name(), tree.type(), tree.value(), tree.x(), tree.y(), img);
    }

    private WzImageProperty buildLeaf(String name, ValueType type, String value, Integer x, Integer y, WzImage img) {
        return switch (type) {
            case STRING -> new WzStringProperty(name, value == null ? "" : value, null, img);
            case INT -> new WzIntProperty(name, parseInt(value), null, img);
            case SHORT -> new WzShortProperty(name, parseShort(value), null, img);
            case LONG -> new WzLongProperty(name, parseLong(value), null, img);
            case FLOAT -> new WzFloatProperty(name, parseFloat(value), null, img);
            case DOUBLE -> new WzDoubleProperty(name, parseDouble(value), null, img);
            case VECTOR -> new WzVectorProperty(name, x == null ? 0 : x, y == null ? 0 : y, null, img);
            case UOL -> new WzUOLProperty(name, value == null ? "" : value, null, img);
            case NULL -> new WzNullProperty(name, null, img);
            default -> throw new RuntimeException("不能构建的叶子类型: " + type);
        };
    }

    private void markChanged(WzObject node) {
        if (node instanceof WzImageProperty prop) {
            prop.setTempChanged(true);
            if (prop.getWzImage() != null) {
                prop.getWzImage().setChanged(true);
                prop.getWzImage().setTempChanged(true);
            }
        }
    }

    private void addLog(String s) {
        messages.add(s);
        if (verbose) {
            System.out.println(s);
        }
    }

    private String formatOk(Change c) {
        return switch (c.op()) {
            case MODIFY -> "[ok]  MODIFY  " + c.displayPath() + (c.value() != null ? " = \"" + truncate(c.value()) + "\"" : "");
            case ADD -> {
                int count = c.subTree() != null ? c.subTree().countNodes() : 1;
                yield "[ok]  ADD     " + c.displayPath() + " (" + count + " node" + (count > 1 ? "s" : "") + ")";
            }
            case DELETE -> "[ok]  DELETE  " + c.displayPath();
        };
    }

    private String truncate(String s) {
        if (s == null) return "";
        if (s.length() > 60) return s.substring(0, 57) + "...";
        return s.replace("\n", "\\n");
    }

    private int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("int 值无效: " + s);
        }
    }

    private short parseShort(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Short.parseShort(s.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("short 值无效: " + s);
        }
    }

    private long parseLong(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("long 值无效: " + s);
        }
    }

    private float parseFloat(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("float 值无效: " + s);
        }
    }

    private double parseDouble(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new RuntimeException("double 值无效: " + s);
        }
    }

    public record Result(boolean success, int applied, int failed, String error, List<String> log) {
    }
}

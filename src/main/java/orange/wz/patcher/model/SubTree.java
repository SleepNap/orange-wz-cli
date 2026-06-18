package orange.wz.patcher.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 用于 ADD 操作时携带整棵子树。叶子时 children 为 null/空，type 为 STRING/INT/...；
 * 容器时 type=SUB，value 为 null。
 */
public final class SubTree {
    private final String name;
    private final ValueType type;
    private final String value;
    private final Integer x;
    private final Integer y;
    private final List<SubTree> children = new ArrayList<>();

    public SubTree(String name, ValueType type, String value, Integer x, Integer y) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.x = x;
        this.y = y;
    }

    public String name() {
        return name;
    }

    public ValueType type() {
        return type;
    }

    public String value() {
        return value;
    }

    public Integer x() {
        return x;
    }

    public Integer y() {
        return y;
    }

    public List<SubTree> children() {
        return children;
    }

    public void addChild(SubTree child) {
        children.add(child);
    }

    /** 递归统计节点数（含自身）。 */
    public int countNodes() {
        int total = 1;
        for (SubTree child : children) {
            total += child.countNodes();
        }
        return total;
    }
}

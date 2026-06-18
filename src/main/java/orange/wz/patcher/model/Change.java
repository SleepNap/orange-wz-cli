package orange.wz.patcher.model;

import java.util.List;

/**
 * 一条 diff 变更。
 * - MODIFY: path 指向叶子节点，value(/x,y) 是新值；type 是叶子类型
 * - ADD:    path 指向新节点（含名字最后一段）；subTree 是要新建的整棵子树
 * - DELETE: path 指向被删节点
 */
public record Change(
        ChangeOp op,
        List<String> path,
        ValueType type,
        String value,
        Integer x,
        Integer y,
        SubTree subTree,
        int sourceLine
) {
    public String displayPath() {
        return String.join("/", path);
    }
}

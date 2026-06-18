package orange.wz.patcher.model;

/** 单个 diff 变更的类型。 */
public enum ChangeOp {
    /** 修改已有叶子节点的值。 */
    MODIFY,
    /** 在父容器下新增节点（可能是叶子，也可能是整棵子树）。 */
    ADD,
    /** 删除已有节点。 */
    DELETE
}

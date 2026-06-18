package orange.wz.patcher.model;

/**
 * 服务端 XML 中 imgdir 之外、本工具能识别的叶子节点类型。
 * SUB 是 imgdir 容器；CANVAS / SOUND 服务端 XML 不应该出现，遇到时只警告并跳过。
 */
public enum ValueType {
    STRING,
    INT,
    SHORT,
    LONG,
    FLOAT,
    DOUBLE,
    VECTOR,
    UOL,
    NULL,
    /** imgdir 容器（嵌套节点）。 */
    SUB,
    /** 工具不支持的类型（canvas / sound 等）。 */
    UNSUPPORTED
}

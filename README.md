# xml-img-patcher

把服务端 XML 的 git unified diff 应用到客户端 `.img` 文件，**保留所有未触及的 PNG / Sound / Canvas / UOL / Vector 等二进制资源**。

适用场景：服务端用瘦 XML（只含业务节点）维护文本/数值变更，客户端的 `.img` 里有完整图标/音效/UI 资源；要把服务端的改动同步回客户端，又不能丢资源。

## 为什么需要这个工具

直接的"反向重生成 .img"流程会把客户端那些只存在于 .img、不存在于服务端 XML 的资源（PNG 图标、音效、UOL 引用、Vector 几何…）全部丢掉——之前出过 `0403.img: 1.3 MB → 70 KB`、`Skill/000.img: 3.1 MB → 4.9 KB` 这种事故。

本工具走另一条路：**直接打开 .img、按 diff 改节点、原样写回**，不重建文件。Diff 没碰到的节点一字节不动。

## 用法

### 基本

```bash
java -jar xml-img-patcher.jar patch <input.img> <diff.xml.diff> <output.img> [选项]
```

或者 `patch` 子命令省略写：

```bash
java -jar xml-img-patcher.jar <input.img> <diff.xml.diff> <output.img>
```

### 选项

| 选项 | 说明 |
|---|---|
| `-v, --verbose` | 实时打印每条 change 的处理过程 |
| `--dry-run` | 解析 diff、加载 img、模拟 patch，**不写文件** |
| `--strict` | 任何一条 change 失败立即中止；默认是尽力做完，最后汇总 |
| `--iv <gms\|cms\|latest>` | WZ 加密 IV，默认 `gms`（GMS v83） |
| `--full-xml <file>` | 完整服务端 XML（diff `+++` 那一侧的最终文件）。当 hunk 上下文不带外层 imgdir 时，用它从 hunk 头的行号反查路径栈，避免歧义/找不到节点。**强烈推荐配** |
| `--full-xml-dir <dir>` | 完整服务端 XML 根目录，会按 diff 路径自动配对（如 `wz-zh-CN/Quest.wz/Act.img.xml.diff` → `<dir>/wz-zh-CN/Quest.wz/Act.img.xml`）。批量处理时用这个，比 `--full-xml` 省事 |

### 把 .img 导出为 XML

调试或验证时需要看 patched .img 的内容：

```bash
java -jar xml-img-patcher.jar export-xml <input.img> <output.xml> [选项]
```

| 选项 | 说明 |
|---|---|
| `--iv <gms\|cms\|latest>` | 同上 |
| `--indent <N>` | 缩进空格数，默认 4 |
| `--linux` | 用 LF 换行（默认 CRLF） |

注意：默认会**跳过 PNG / Sound 等二进制资源**（只输出节点骨架），便于纯文本对比。

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 全部成功 |
| 1 | 部分 change 失败但已写出（非严格模式） |
| 2 | 参数错误或文件不存在 |
| 3 | diff 解析失败 |
| 4 | img 解析失败 |
| 5 | img 写入失败 |

## 输出格式

可被 AI / shell 脚本解析，关键字 `MODIFY` / `ADD` / `DELETE` / `[ok]` / `[err]` 永远是英文：

```
[parse] 12 changes from diff
[ok]  MODIFY  9999999/name = "已杀怪物数"
[ok]  ADD     04031786 (3 nodes)
[ok]  DELETE  oldNode
[err] MODIFY  Foo/Bar — 节点不存在或路径不唯一: Foo/Bar
3 applied, 1 failed. Output: D:\out.img (1,335,712 bytes)
```

## 典型 batch 脚本

把整目录的 diff 应用到 `client/Data` 下对应的 .img：

```bash
DIFF_DIR=/path/to/diff_20260619
XML_DIR=/path/to/upgrade_20260619       # 服务端完整 XML
CLIENT=/path/to/BeiDou-Client/Data
OUT=/path/to/patched-Data

while IFS= read -r -d '' diff; do
  rel="${diff#$DIFF_DIR/}"              # e.g. wz/Item.wz/Etc/0403.img.xml.diff
  rel="${rel#wz/}"; rel="${rel#wz-zh-CN/}"
  img="${rel%.img.xml.diff}.img"        # e.g. Item.wz/Etc/0403.img
  img="${img//.wz\//\/}"                # 去掉 .wz/  → Item/Etc/0403.img
  src="$CLIENT/$img"
  dst="$OUT/$img"
  mkdir -p "$(dirname "$dst")"
  java -jar xml-img-patcher.jar patch "$src" "$diff" "$dst" --full-xml-dir "$XML_DIR"
done < <(find "$DIFF_DIR" -name "*.diff" -print0)
```

## 构建

要求 Java 21+ 和 Maven 3.8+：

```bash
mvn -DskipTests package
# 产出 target/xml-img-patcher.jar（fat jar，包含所有依赖，可独立运行）
```

Windows 用户：项目根的 `build.bat` 是封装好的一键构建。

## 已知限制

- **hunk 上下文极短且叶子名在文件中不唯一时**会失败（如 `String.wz/Skill.img` 里 `desc` 出现 600+ 次，hunk 起始切在 imgdir 中部、没有外层 `<imgdir>` 标签可依据）。**解决方法：传 `--full-xml` 或 `--full-xml-dir`**，工具会从 hunk header 的行号反查完整路径，规避此问题。
- 不支持 `Canvas` / `Sound` / `Convex` 等富媒体节点的 diff 修改（服务端 XML 本来也不会动这些）。
- 不做 batch / 多文件配对，需要 batch 时用上面的 shell 循环。

## 许可

MIT。

## 参考

- 项目计划文档（设计动机、调研记录、算法说明）：[`xml-img-patcher-plan.md`](./xml-img-patcher-plan.md)
- 同源 .NET 实现（用相同算法、可独立运行的 self-contained exe）：见姊妹仓库 `xml-img-patcher-csharp`（如果你有）

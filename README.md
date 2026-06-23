# xml-img-patcher

把服务端 XML 的 git unified diff 应用到客户端 `.img` 文件，**保留所有未触及的 PNG / Sound / Canvas / UOL / Vector 等二进制资源**。

适用场景：服务端用瘦 XML（只含业务节点）维护文本/数值变更，客户端的 `.img` 里有完整图标/音效/UI 资源；要把服务端的改动同步回客户端，又不能丢资源。

## 为什么需要这个工具

直接的"反向重生成 .img"流程会把客户端那些只存在于 .img、不存在于服务端 XML 的资源（PNG 图标、音效、UOL 引用、Vector 几何…）全部丢掉——之前出过 `0403.img: 1.3 MB → 70 KB`、`Skill/000.img: 3.1 MB → 4.9 KB` 这种事故。

本工具走另一条路：**直接打开 .img、按 diff 改节点、原样写回**，不重建文件。Diff 没碰到的节点一字节不动。

## 用法

### 子命令

```
xml-img-patcher patch          <input.img> <diff> <output.img> [选项]
xml-img-patcher dump-xml       <input.img> <output.xml>        [选项]
xml-img-patcher batch          <img目录> <diff目录> <输出目录> [选项]
xml-img-patcher batch-dump-xml <img目录> <xml输出目录>         [选项]
xml-img-patcher verify         <patched.img> <diff> [full-xml或目录] [选项]
xml-img-patcher export         --from=<hash或datetime>        [选项]
```

| 子命令 | 作用 |
|---|---|
| `patch` | 对一个 .img 应用一个 .diff，输出新 .img。保留 PNG/Sound/UOL 等所有 diff 没碰过的二进制资源 |
| `dump-xml` | 把 .img 转成服务端格式的 .xml，方便肉眼看或对比 |
| `batch` | 批量版的 patch。按文件名自动配对：diff 目录下 `a/b/Foo.img.xml.diff` → 找 img 目录里的 `a/b/Foo.img` → 写到输出目录 `a/b/Foo.img`。diff 目录可多层嵌套，工具会递归扫所有 `*.diff`。没找到对应 img 的 diff 会跳过并在最后 BATCH SUMMARY 汇总 |
| `batch-dump-xml` | 批量版的 dump-xml。递归把目录下所有 .img 都转成 .xml |
| `verify` | 校验：直接加载 patch 后的 .img，把 diff 里每条 + 变更（Add/Modify）查节点比对值；DELETE 查节点是否已消失。绕过 dump-xml 序列化，测的就是 img 的实际内容 |
| `export` | 从 git 仓库导出指定起点之后的 wz xml 与 diff，等价于服务端 `ExportPatch.java`。`--from` 同时支持 commit hash 和 datetime |

### patch 选项

| 选项 | 说明 |
|---|---|
| `-v, --verbose` | 实时打印每条 change 的处理过程 |
| `--dry-run` | 解析 diff、加载 img、模拟 patch，**不写文件** |
| `--strict` | 任何一条 change 失败立即中止；默认是尽力做完，最后汇总 |
| `--iv <GMS\|EMS\|BMS\|CLASSIC>` | WZ 加密 IV，默认 `GMS`（大小写不敏感） |
| `--full-xml <file>` | 完整服务端 XML（diff `+++` 那一侧的最终文件）。当 hunk 上下文不带外层 imgdir 时，用它从 hunk 头的行号反查路径栈，避免歧义/找不到节点。**强烈推荐配** |
| `--full-xml-dir <dir>` | 完整服务端 XML 根目录，会按 diff 路径自动配对。批量处理时用这个，比 `--full-xml` 省事 |

### batch 选项

| 选项 | 说明 |
|---|---|
| `--full-xml-dir <dir>` | 完整服务端 XML 根目录（按 diff 路径自动配对） |
| `--dry-run` / `--strict` / `--iv` / `-v` | 同 patch |

### dump-xml 选项

| 选项 | 说明 |
|---|---|
| `--iv <GMS\|EMS\|BMS\|CLASSIC>` | 同上 |
| `--indent <N>` | 缩进空格数，默认 4 |
| `--linux` | 用 LF 换行（默认 CRLF） |

默认会**跳过 PNG / Sound 等二进制资源**（只输出节点骨架），便于纯文本对比。

### verify 选项

| 选项 | 说明 |
|---|---|
| `<full-xml 或目录>` | 第 3 个位置参数。完整服务端 XML 文件，或与之同布局的目录（工具会按 diff 文件名配对查找）。用来恢复 hunk 路径栈 |
| `--iv <GMS\|EMS\|BMS\|CLASSIC>` | 同上 |
| `-v` | 打印每条 ok / miss |

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

## 典型用法

### 单文件 patch

```bash
xml-img-patcher patch \
  --full-xml=C:/upgrade_20260622/wz-zh-CN/Quest.wz/QuestInfo.img.xml \
  C:/client/Data/Quest/QuestInfo.img \
  C:/diff_20260622/wz-zh-CN/Quest.wz/QuestInfo.img.xml.diff \
  C:/out/Quest/QuestInfo.img
```

### 批量 patch

```bash
xml-img-patcher batch \
  --full-xml-dir=C:/upgrade_20260622/wz-zh-CN \
  C:/client/Data \
  C:/diff_20260622/wz-zh-CN \
  C:/out/Data
```

末尾会打印 `BATCH SUMMARY`，汇总 ok / fail / skip 文件数和每个失败/跳过的原因。

### 批量导出 XML

```bash
xml-img-patcher batch-dump-xml C:/client/Data C:/out_xml/Data
```

### 校验

```bash
xml-img-patcher verify \
  C:/out/Quest/QuestInfo.img \
  C:/diff_20260622/wz-zh-CN/Quest.wz/QuestInfo.img.xml.diff \
  C:/upgrade_20260622/wz-zh-CN
```

输出 `verify: N expected, M match, K miss`，miss=0 即通过。

## 构建

要求 Java 21+ 和 Maven 3.8+：

```bash
mvn -DskipTests package
# 产出 target/xml-img-patcher.jar（fat jar，包含所有依赖，可独立运行）
```

Windows 用户：项目根的 `build.bat` 是封装好的一键构建。

### Native exe（GraalVM）

用 GraalVM native-image 出独立 exe，启动 0 延迟、不依赖 JRE：

```cmd
build-native.bat
:: 产出 dist\xml-img-patcher.exe
```

要求：GraalVM JDK 21 + MSVC 工具链（vcvarsall.bat）。脚本里硬编码了作者本机的路径，换机构建需改脚本里的 `JAVA_HOME` 和 `VCDIR`。



## 开发与测试

这一节记录本工具做了什么、设计思路、产物清单、以及怎么复现测试。

### 做了什么

把原 OrzRepacker（Spring Boot GUI + MCP HTTP 服务）改造成一个纯 CLI 工具 `xml-img-patcher`，只做一件事：**把 git unified diff 里的节点变更应用到客户端 `.img`，不重建文件、不丢二进制资源**。

- 砍掉 Spring Boot / Swing GUI / MCP HTTP 那一套
- 引入 `picocli 4.7.6`，主类 `orange.wz.patcher.Main`，子命令 `patch` / `export-xml`
- 保留并复用 `orange.wz.provider.*`（v83 散文件 img 读写内核：WzKey/AES、WzImage 解析、节点增删改、Canvas/PNG/Sound/UOL/Vector 完整保留）
- 新增 `patcher/parser/DiffParser`、`patcher/parser/XmlLineParser`、`patcher/patch/ImgPatcher` 三件套
- `maven-shade-plugin` 出 fat jar

### 设计思路

```
diff (git unified)  ──┐
                      ├──► DiffParser ──► List<Change{op, path[], type, value, subTree}>
完整服务端 XML       ──┘     ▲                │
(--full-xml)                   │ 行号回查      │ 按路径定位
seed hunk 起始 imgdir 栈       │               ▼
                          ImgPatcher ──► WzImage 原地改节点 ──► 写回 .img
```

三个输入各司其职：

| 输入 | 角色 |
|---|---|
| **diff** | 唯一真理来源：改哪个节点、改成什么值。hunk 头给行号、`+/-` 行给值 |
| **完整服务端 XML**（可选 `--full-xml`/`--full-xml-dir`）| 路径字典：当 hunk 上下文不带外层 `<imgdir>` 时，用 hunk 头的 `+N` 行号到 XML 里扫前 N−1 行重建 imgdir 嵌套栈，恢复完整路径 |
| **`.img`** | 唯一被读写的对象：按解出来的路径 `getChild` 精确取节点，原地改值，原样写回 |

DiffParser 维护 left/right 两份 imgdir 栈（`-` 行只动 left、`+` 行只动 right、context 行共同前进），按近邻聚合把 `-/+` 配成 MODIFY、剩余 `-` 成 DELETE、剩余 `+` 成 ADD（容器开标签会吸收到匹配 `</imgdir>`，整棵子树作为一个 SubTree 加进去）。

ImgPatcher 拿到 `List<Change>` 后逐条应用：MODIFY 按路径取节点改值；ADD 找父节点挂子树，若节点已存在则 merge（不覆盖客户端更完整的既有值）；DELETE 幂等。`-` `--dry-run` 不写盘，`--strict` 失败即停。

### 修过的 bug（开发中实测发现）

1. **`XmlLineParser.unescapeXml` 不认数字字符引用**：`&#xD;` `&#xA;` 这类被当字面 6 字符存进 `.img`，导致中文长文本里的换行错乱。补上 `expandNumericEntities` 展开 `&#xHH;` / `&#NNN;`。
2. **`XmlExport.escapeText` 不转义 `\r \n \t`**：attr value 里的字面 `\r\n` 会被 XML 解析器按规范归一化为空格，丢失换行语义。补上 `&#xD;` `&#xA;` `&#x9;` 转义。
3. **DiffParser seed 栈 push 顺序**（`--full-xml` 引入时自造的）：`ArrayDeque.push` 是头插，要按 root→leaf 顺序逐个 push 才能让 head=最深；最早写反了导致兄弟节点被错误嵌套。同时 seed 要跳过文件根 `<imgdir name="X.img">`（对应 WzImage 自身，不在内部节点路径里）。

### 产物清单

```
.
├── pom.xml                              # picocli + shade fat jar
├── build.bat                            # 一键 mvn package
├── xml-img-patcher.bat                  # 启动包装（加进 PATH 后直接 xml-img-patcher ...）
├── README.md                            # 本文档
├── xml-img-patcher-plan.md              # 原始设计/调研文档
├── src/main/java/orange/wz/
│   ├── patcher/
│   │   ├── Main.java                    # picocli 入口
│   │   ├── PatchCommand.java            # patch 子命令 + --full-xml / --full-xml-dir
│   │   ├── ExportXmlCommand.java        # export-xml 子命令
│   │   ├── model/                       # Change / ChangeOp / SubTree / ValueType
│   │   ├── parser/
│   │   │   ├── DiffParser.java          # unified diff → List<Change>，含 seed 栈
│   │   │   └── XmlLineParser.java       # 单行 XML 解析 + 实体反转义
│   │   └── patch/ImgPatcher.java        # Change 应用到 WzImage + 后缀回退
│   └── provider/                        # v83 img 读写内核（保留）
└── test-out/                            # 测试套件（脚本入仓，产物被 gitignore）
    ├── run-all.sh                       # 跑全部 20 个 case 的 patch+export
    ├── compare.sh                       # patched xml vs upgrade xml 行级对比
    ├── verify-hunks.sh                  # diff hunk 行级生效检查
    └── verify-paths.py                  # 路径敏感的节点级正确性验证（主验证器）
```

### 怎么复现测试

前置数据（放桌面，路径在脚本里硬编码，按需改）：

```
C:\Users\CN\Desktop\diff_20260619\        # 20 个 .img.xml.diff（wz/ 10 个 + wz-zh-CN/ 10 个）
C:\Users\CN\Desktop\upgrade_20260619\     # 对应的完整服务端 XML（--full-xml-dir 指向它）
E:\LocalGit\GitHub\BeiDou-Client\         # 客户端 .img 源（EN/ 英文、Data/ 中文）
```

1. 把源 `.img` 拷到 `test-out/src/` 下（按 `wz/→EN 优先 fallback Data`、`wz-zh-CN/→Data` 映射，**不污染原 img**）
2. 跑全量：

   ```bash
   mvn -DskipTests package                 # 出 fat jar
   bash test-out/run-all.sh                # patch 全部 + export-xml，日志在 test-out/log/
   # 再把 patched img 统一 export 成 2 空格 LF 的 xml 便于对比
   for img in $(find test-out/patched -name "*.img"); do
     rel="${img#test-out/patched/}"
     java -jar target/xml-img-patcher.jar export-xml "$img" "test-out/xml2/${rel%.img}.xml" --indent 2 --linux
   done
   python -X utf8 test-out/verify-paths.py  # 路径敏感验证，输出每个 case 的 pass/fail
   ```

### 测试结果（最近一次）

| 维度 | 结果 |
|---|---|
| patcher 自报 | **20 / 20 case、4991 / 4991 changes、0 failed** |
| verify-paths 节点级验证 | 除 `zh_Quest_Say` 23 条 `STILL_OLD`（已确认是验证器局限——"删了重写但值未变"，patched 节点值与 upgrade XML 逐一对照一致）外，**全部 PASS** |
| 抽样真值 | zh Mob `9999999/name=已杀怪物数`、en Mob `9999999/name=Hunted Monsters`、zh Skill `0001005/desc` 冷却 2 小时→300 秒、en 0403 新增 `04031786` 子树、en Skill/000 深路径新增 `0001005/level/1/cooltime=300` —— 全部正确 |

## 已知限制

- **hunk 上下文极短且叶子名在文件中不唯一时**会失败（如 `String.wz/Skill.img` 里 `desc` 出现 600+ 次，hunk 起始切在 imgdir 中部、没有外层 `<imgdir>` 标签可依据）。**解决方法：传 `--full-xml` 或 `--full-xml-dir`**，工具会从 hunk header 的行号反查完整路径，规避此问题。
- 不支持 `Canvas` / `Sound` / `Convex` 等富媒体节点的 diff 修改（服务端 XML 本来也不会动这些）。
- 不做 batch / 多文件配对，需要 batch 时用上面的 shell 循环。

## 许可

MIT。

## 参考

- 项目计划文档（设计动机、调研记录、算法说明）：[`xml-img-patcher-plan.md`](./xml-img-patcher-plan.md)
- 同源 .NET 实现（用相同算法、可独立运行的 self-contained exe）：见姊妹仓库 `xml-img-patcher-csharp`（如果你有）

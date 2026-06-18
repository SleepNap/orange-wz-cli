# XML diff → IMG 增量同步工具 — 项目计划

> **状态**：规划中，未开始实现
> **创建日期**：2026-06-18
> **作者**：基于与昨日小睡的对话整理
> **位置**：本文档存档于 `E:\LocalGit\Local\xml-img-patcher-plan.md`

---

## 1. 背景与动机

### 1.1 起因事件

BeiDou-Client 仓库（`E:\LocalGit\GitHub\BeiDou-Client`）在提交 `6cc7c5f`（"大量社区汉化pr"）中，使用了"服务端 XML → 反向重生成完整 .img → 覆盖客户端"的流程，结果触发了致命缺陷：

| 文件 | 之前 | 之后 | 损失 |
|---|---|---|---|
| `Data/Item/Etc/0403.img` | 1,335,685 B | 70,250 B | **-94.7%**（图标 PNG 全丢） |
| `Data/Skill/000.img` | 3,176,810 B | 4,918 B | **-99.8%**（技能图标全丢） |
| `Data/Skill/1000.img` | 3,210,255 B | 5,474 B | **-99.8%** |
| `Data/Skill/2000.img` | 3,445,793 B | 5,617 B | **-99.8%** |
| `Data/Map/Map/Map2/209000000.img` | 44,459 B | 31,971 B | -28% |
| `Data/String/Skill.img` | 874,096 B | 534,019 B | -38.9% |

随后用 `da2eaaf` 部分回滚了 4 个文件，但其他还在错误状态。最终在 2026-06-18 的对话中确认：两个提交（`6cc7c5f` + `da2eaaf`）都需要丢弃，已经 `git reset --hard e5678d0` + `git push --force-with-lease` 完成清理。

### 1.2 根因

服务端的 XML 是"瘦"的——只保留服务端业务逻辑用得到的节点（int / string / imgdir 容器），**完全不存** PNG 图标（canvas 节点）、音频（Sound_DX8）、UOL 链接、Vector 等"展示资源"节点。这些资源只存在于官方客户端的 .img 文件里。

所以"反向生成"流程是单向有损的：
```
官方完整 img (1.3MB)
  ──→ 服务端解析仅取业务节点 ──→ 服务端 XML (200KB)
  ──→ 反向重生成完整 img ──→ 残缺 img (70KB) ❌ 丢了图标和音频
```

### 1.3 用户需求（与昨日小睡 2026-06-18 对话）

原话节选：
- "服务端的 xml 只会包含简单的节点数据，资源文件如图片、音频什么的都会丢弃，导致反向生成时有问题"
- "我让你做的工具是针对 cli 或者脚本，是 ai 可调用的。不需要图形化界面"
- "java 和 c# 各来一个"
- "目前只做加载 img，加载 xml diff，然后根据 diff 修改 img"
- "java 也能编译成 exe"（参考 `beidou-cli` 用 GraalVM Native Image，`NapMysqlTool` 用 Liberica NIK）
- 项目位置：`E:\LocalGit\Local\`，不上 GitHub（"后续我要开源再弄"）

### 1.4 目标产出

两个独立、对等的 CLI 工具：

| 产出 | 路径 | 形态 |
|---|---|---|
| Java 版 | `E:\LocalGit\Local\xml-img-patcher-java\` | 可执行 jar + 可选的 native-image exe |
| C# 版 | `E:\LocalGit\Local\xml-img-patcher-csharp\` | self-contained .NET exe |

**核心能力**（两版完全一致）：

```
xml-img-patcher <input.img> <diff.xml.diff> <output.img>
```

1. 加载一个原始客户端 .img 文件
2. 加载一个 git unified diff 文件
3. 把 diff 里的节点级变更应用到内存中的 img 树
4. 把修改后的 img 写出到 output 路径
5. **保留所有未被 diff 触及的二进制节点**（PNG / Sound / UOL / Vector 等）

**不做**（明确范围外）：
- GUI（图形化已有 HaSuite/orange-wz GUI，用户不需要重复建设）
- batch 模式 / 多文件配对
- 反向（img → diff）
- 修改 BeiDou-Client 仓库的提交流程
- 自动 git 提交

---

## 2. 输入数据现状（用户已准备）

### 2.1 服务端导出

```
C:\Users\CN\Desktop\upgrade_20260618\
├── wz\                              # 服务端 wz 目录的完整最新 XML
│   ├── Item.wz\Etc\0403.img.xml
│   ├── Map.wz\Map\Map2\209000000.img.xml
│   ├── Quest.wz\Check.img.xml
│   ├── Quest.wz\QuestInfo.img.xml
│   ├── Quest.wz\Say.img.xml
│   ├── Skill.wz\000.img.xml
│   ├── Skill.wz\1000.img.xml
│   ├── Skill.wz\2000.img.xml
│   ├── String.wz\Mob.img.xml
│   └── String.wz\Skill.img.xml
└── wz-zh-CN\                        # 中文翻译覆盖层
    ├── Quest.wz\...
    └── String.wz\...
```

```
C:\Users\CN\Desktop\diff_20260618\
├── wz-diff\                         # git unified diff 形式的变更
│   ├── Item.wz\Etc\0403.img.xml.diff
│   ├── Map.wz\Map\Map2\209000000.img.xml.diff
│   ├── Quest.wz\Check.img.xml.diff
│   ├── Quest.wz\QuestInfo.img.xml.diff
│   ├── Quest.wz\Say.img.xml.diff
│   ├── Skill.wz\000.img.xml.diff
│   ├── Skill.wz\1000.img.xml.diff
│   ├── Skill.wz\2000.img.xml.diff
│   ├── String.wz\Mob.img.xml.diff
│   └── String.wz\Skill.img.xml.diff
└── wz-zh-CN-diff\
    ├── Quest.wz\Act.img.xml.diff
    ├── Quest.wz\Check.img.xml.diff
    ├── Quest.wz\PQuest.img.xml.diff
    ├── Quest.wz\QuestInfo.img.xml.diff
    ├── Quest.wz\Say.img.xml.diff
    ├── String.wz\Eqp.img.xml.diff
    ├── String.wz\Etc.img.xml.diff
    ├── String.wz\Mob.img.xml.diff
    ├── String.wz\Npc.img.xml.diff
    └── String.wz\Skill.img.xml.diff
```

### 2.2 客户端

```
E:\LocalGit\GitHub\BeiDou-Client\
├── Data\                            # 中文资源（运行时主目录）
│   ├── Item\Etc\0403.img
│   ├── Quest\{Act,Check,PQuest,QuestInfo,Say}.img
│   ├── Skill\{000,1000,2000}.img
│   ├── String\{Eqp,Etc,Mob,Npc,Skill}.img
│   └── ...
├── EN\                              # 英文/汉化覆盖层（用于切语言）
└── BeiDou.exe                       # GMS v83 客户端
```

### 2.3 diff 样本

`String/Mob.img.xml.diff` 极简变更：
```diff
@@ -4024,7 +4024,7 @@
         <string name="name" value="脑壳"/>
     </imgdir>
     <imgdir name="9999999">
-        <string name="name" value="黄金蛋"/>
+        <string name="name" value="已杀怪物数"/>
     </imgdir>
     <imgdir name="9400630">
```

`Item/Etc/0403.img.xml.diff` 节点新增：
```diff
@@ -8450,6 +8450,16 @@
       <int name="quest" value="1"/>
     </imgdir>
   </imgdir>
+  <imgdir name="04031786">
+    <imgdir name="info">
+      <int name="quest" value="1"/>
+    </imgdir>
+  </imgdir>
+  <imgdir name="04031787">
+    <imgdir name="info">
+      <int name="quest" value="1"/>
+    </imgdir>
+  </imgdir>
   <imgdir name="04031788">
```

---

## 3. 客户端 .img 二进制格式（GMS v83）

调研结论：

- **不是** PKG4（PKG4 是 v159+ 才有），是 v83 的**散文件 IMG**（每个 .img 单独一个文件）
- 客户端根目录另有 `List.wz`（13KB，加密文件名校验表）+ `TempPackage.wz`（62B，空壳，HaSuite 用作宿主），它们与本工具无关
- `Data/Quest/Say.img` 前 16 字节：`73 F8 6C 77 FC 79 83 27 19 58 00 00 80 67 0B 00`
  - 起始 `0x73` = 无 offset 标记的传统 img
  - 前 12 字节是 WZ Property header（带 GMS WZ-IV 加密）
- 节点类型（IMG bytecode 内部 tag）：
  - `0x00` Null
  - `0x02` / `0x0B` Short
  - `0x03` / `0x13` Int（变长压缩）
  - `0x14` Long
  - `0x04` Float
  - `0x05` Double
  - `0x08` String（通过 offset 引用字符串池）
  - `0x09` SubProperty（容器，存子节点）
  - 复合对象通过 tag 字符串识别：`Property` / `Canvas` / `Shape2D#Vector2D` / `Shape2D#Convex2D` / `Sound_DX8` / `UOL`

完整支持读写这种格式的库（**不要自己写**）：

- C#：[`lastbattle/MapleLib`](https://github.com/lastbattle/MapleLib) — HaRepacker 同款，2026-06 活跃，NuGet `MapleLib-Core`
- Java：[`leevccc/orange-wz`](https://github.com/leevccc/orange-wz) — OrzRepacker 内核，2026-06 活跃，自带 Spring Boot MCP server（端口 10002）

---

## 4. 选库：与 LLM 调研 + 用户指定

### 4.1 Java：`leevccc/orange-wz`（用户指定）

- 仓库：https://github.com/leevccc/orange-wz
- 核心 API（`src/main/java/orange/wz/` 下）：
  - `WzNodeUtil` 节点操作工具
  - `Img2Xml` / `Xml2Img2` 整 img 与 XML 转换（本工具不直接用）
  - `mcp/tool/impl/BatchUpdateNodesTool` / `CreateChildNodeTool` / `DeleteNodeTool` 已经是封装好的"对节点做改/增/删"的实现，可以直接借鉴或调用
- 支持：v83 散文件 img、Canvas/PNG/Sound/UOL/Vector 完整保留、节点增删改、bms/gms/kms 多 IV
- 集成方式：clone → `mvn install` 到本地 ~/.m2 → 项目 pom.xml 加依赖

### 4.2 C#：`lastbattle/MapleLib`

- 仓库：https://github.com/lastbattle/MapleLib
- NuGet：`MapleLib-Core` 1.0.0
- 核心 API：
  - `WzImgDeserializer.WzImageFromIMGBytes(bytes, version)` 从 byte[] 读 img
  - `WzImage.GetFromPath(path)` 按 `/` 分隔路径取节点
  - `WzPropertyCollection.Add` / `.Remove` 增删
  - `WzImgSerializer.SerializeImage(img, version)` 写回 byte[]
- WzMapleVersion 枚举传 `GMS`

---

## 5. 项目结构

### 5.1 Java 项目（`E:\LocalGit\Local\xml-img-patcher-java`）

参考 `E:\LocalGit\Local\beidou-cli`（同样 picocli + jackson + 可 native-image 编译为 exe）和 `E:\LocalGit\GitHub\NapMysqlTool`（用 Liberica NIK 编译 exe）。

```
xml-img-patcher-java/
├── pom.xml                          # 见 5.1.1
├── README.md                        # 用法、AI 调用示例
├── LICENSE
├── build-native.bat                 # GraalVM native-image 编译为 exe
├── install.bat                      # 把 exe 装到 PATH
└── src/main/
    ├── java/com/beidou/patcher/
    │   ├── Main.java                # picocli @Command(name="xml-img-patcher")
    │   ├── parser/
    │   │   ├── DiffParser.java      # 解析 unified diff → List<Change>
    │   │   ├── XmlContext.java      # 维护 hunk 内 imgdir 嵌套栈，重建节点路径
    │   │   └── ValueTypeMapper.java # 标签名 ↔ WZ 节点类型映射
    │   ├── model/
    │   │   ├── Change.java          # 数据类
    │   │   ├── ChangeOp.java        # 枚举 MODIFY / ADD / DELETE
    │   │   └── ValueType.java       # 枚举 STRING / INT / SHORT / LONG / FLOAT / DOUBLE / SUB
    │   └── patcher/
    │       ├── ImgPatcher.java      # 协调：load → patch → save
    │       └── OrangeWzAdapter.java # 封装 orange-wz 调用
    └── resources/
        └── ...
```

#### 5.1.1 pom.xml 关键配置

```xml
<properties>
  <maven.compiler.source>21</maven.compiler.source>
  <maven.compiler.target>21</maven.compiler.target>
  <picocli.version>4.7.6</picocli.version>
  <orange-wz.version>0.x</orange-wz.version>     <!-- 看 orange-wz 实际版本 -->
  <main.class>com.beidou.patcher.Main</main.class>
</properties>

<dependencies>
  <dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli</artifactId>
    <version>${picocli.version}</version>
  </dependency>
  <dependency>
    <groupId>orange</groupId>          <!-- 实际 groupId 看 orange-wz 的 pom -->
    <artifactId>wz</artifactId>
    <version>${orange-wz.version}</version>
  </dependency>
</dependencies>

<!-- maven-shade-plugin 打 fat jar -->
<!-- profile=native：org.graalvm.buildtools:native-maven-plugin -->
```

#### 5.1.2 Native Image 编译

参考 `beidou-cli` 的做法：

```bash
# build-native.bat
mvn -Pnative -DskipTests package
# 产出 target\xml-img-patcher.exe
```

要求环境：
- Liberica Native Image Kit 23+ 或 GraalVM Java 21+
- Windows 上需要 MSVC 工具链（VS Build Tools）

如果 orange-wz 用了大量反射或 Spring Boot 启动逻辑，可能需要写 `reflect-config.json`。本工具只调 orange-wz 里的 wz 解析部分（不启动 Spring），反射面应该可控。**先不强求 native-image，fat jar 模式优先跑通**。

### 5.2 C# 项目（`E:\LocalGit\Local\xml-img-patcher-csharp`）

```
xml-img-patcher-csharp/
├── XmlImgPatcher.sln
├── XmlImgPatcher.csproj             # net8.0
├── README.md
├── publish.bat                      # dotnet publish 出 self-contained exe
└── src/
    ├── Program.cs                   # CommandLine 解析，等价 Main.java
    ├── Parser/
    │   ├── DiffParser.cs
    │   ├── XmlContext.cs
    │   └── ValueTypeMapper.cs
    ├── Model/
    │   ├── Change.cs
    │   ├── ChangeOp.cs
    │   └── ValueType.cs
    └── Patcher/
        ├── ImgPatcher.cs
        └── MapleLibAdapter.cs
```

#### 5.2.1 csproj 关键配置

```xml
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net8.0</TargetFramework>
    <PublishSingleFile>true</PublishSingleFile>
    <SelfContained>true</SelfContained>
    <RuntimeIdentifier>win-x64</RuntimeIdentifier>
  </PropertyGroup>
  <ItemGroup>
    <PackageReference Include="MapleLib-Core" Version="1.0.0" />
    <PackageReference Include="System.CommandLine" Version="2.0.0-beta4" />
  </ItemGroup>
</Project>
```

发布命令：
```
dotnet publish -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
```

---

## 6. 算法设计

### 6.1 数据模型

```java
class Change {
    List<String> path;          // ["04031786", "info", "quest"] 从 img 根开始
    ChangeOp op;                // MODIFY / ADD / DELETE
    ValueType valueType;        // STRING / INT / ... / SUB
    String value;               // 对叶子节点；SUB 类型时 null
    SubTree subTree;            // 对 ADD 整棵子树时持有完整结构
    int sourceLine;             // diff 中行号，便于报错定位
}
```

```java
class SubTree {
    String name;
    ValueType type;
    String value;               // 叶子时
    List<SubTree> children;     // imgdir 容器时
}
```

### 6.2 DiffParser 算法

输入：unified diff 文件（UTF-8 文本）
输出：`List<Change>`

**总体策略**：使用栈式状态机跟踪 `<imgdir>` 嵌套层级。每个 hunk 重置栈到一个未知状态，但通过 hunk 内 context 行（前导空格的行）逐步还原栈。

**伪代码**：

```
void parse(File diff) {
    跳过 diff 头：以 "diff --git", "index ", "+++ ", "--- " 开头的行
    for each hunk:
        Stack<String> imgdirStack = new Stack();
        // 提示：hunk header 形如 @@ -A,B +C,D @@，B/D 是行数
        for each 行 in hunk:
            String trimmed = 行.stripLeading();
            char prefix = 行.charAt(0); // ' ', '+', '-'
            switch (prefix):
                case ' ': // context line
                    handleStructural(trimmed, imgdirStack); // 仅用来同步栈
                    break;
                case '-':
                    Change c = parseLine(trimmed, imgdirStack, DELETE_OR_OLD);
                    若紧邻下一行是 +，且节点名相同 → 升级为 MODIFY
                    否则 → DELETE
                    handleStructural(trimmed, imgdirStack);
                    break;
                case '+':
                    若上一行已被吸收为 MODIFY → 跳过
                    否则 → ADD
                    若是 <imgdir> 开放标签，启动子树收集模式（直到匹配 </imgdir>）
                    handleStructural(trimmed, imgdirStack);
                    break;
}

void handleStructural(String line, Stack stack) {
    if (line.startsWith("<imgdir name=\"") && !line.endsWith("/>")) {
        stack.push(extractName(line));
    } else if (line.startsWith("</imgdir>")) {
        stack.pop();
    }
    // <string/>, <int/> 等自闭合不影响栈
}
```

**陷阱与处理**：

1. **服务端 XML 缩进可能是 4 空格、2 空格、tab 混用** — 用 `stripLeading()` 忽略，靠标签语义判断
2. **`-A +A2 -B +B2` 连续修改** — 每对 -/+ 独立处理，按节点名匹配（不能假设连续）
3. **diff 起始的 hunk header 后立即就是变更行（无 context）** — 这种 hunk 的 imgdir 栈完全靠"完整 XML 文件"辅助：如果用户提供了完整 XML（`upgrade_20260618`），可以二次校验；如果没提供，通过递归向上解析"@@ -A 行附近"也能恢复（picocli 阶段先不强求）
4. **新增的 `<imgdir>` 块包含子节点** — 整体作为一个 SubTree 添加，递归收集
5. **diff 路径里的文件名只用来报错定位**，不解析（用户传入的 `--img` 才是真目标）

### 6.3 ImgPatcher 算法

```java
void patch(Path inputImg, List<Change> changes, Path outputImg) {
    WzImage img = orangeWz.parseImg(inputImg);
    int ok = 0, err = 0;
    for (Change c : changes) {
        try {
            switch (c.op) {
                case MODIFY -> {
                    WzNode node = img.getFromPath(c.path);
                    if (node == null) throw new NodeNotFound(c.path);
                    node.setValue(c.valueType, c.value);
                }
                case ADD -> {
                    WzNode parent = img.getFromPath(c.path.dropLast());
                    if (parent == null) throw new ParentNotFound(c.path);
                    if (parent.hasChild(c.path.last())) throw new AlreadyExists(c.path);
                    parent.addChild(buildNodeFromSubTree(c.subTree));
                }
                case DELETE -> {
                    WzNode parent = img.getFromPath(c.path.dropLast());
                    parent.removeChild(c.path.last());
                }
            }
            ok++;
            log("[ok]  " + c.op + " " + String.join("/", c.path));
        } catch (Exception e) {
            err++;
            log("[err] " + c.op + " " + String.join("/", c.path) + " — " + e.getMessage());
        }
    }
    orangeWz.writeImg(img, outputImg);
    log(ok + " applied, " + err + " failed.");
    System.exit(err == 0 ? 0 : 1);
}
```

**关键不变量**：
- 整个流程只动 diff 提到的节点；其他节点（包括 PNG / Sound）原样保留
- 输出文件大小应该与输入大小相近（除非 diff 真的大量增删）
- 对 0403.img 测试用例：input 1.3MB → output 应仍 ≈ 1.3MB（**绝不能 70KB**）

---

## 7. CLI 接口

### 7.1 用法（两版完全一致）

```
xml-img-patcher <input.img> <diff.xml.diff> <output.img>
```

**参数**：
- `input.img` — 必填，原始客户端 .img 路径
- `diff.xml.diff` — 必填，git unified diff 文件
- `output.img` — 必填，输出 .img 路径（可与 input 相同，原地覆盖）

**可选参数**（picocli / System.CommandLine）：
- `-v, --verbose` — 详细输出每条 change 的处理过程
- `--dry-run` — 解析 diff、加载 img、模拟 patch，但不写文件
- `--strict` — 任何一条 change 失败立即中止（默认是尽力做完，最后汇总）

### 7.2 退出码

| 码 | 含义 |
|---|---|
| 0 | 全部成功 |
| 1 | 部分 change 失败但已写出（非严格模式） |
| 2 | 参数错误或文件不存在 |
| 3 | diff 解析失败 |
| 4 | img 解析失败 |
| 5 | img 写入失败 |

### 7.3 输出格式（可被 AI 解析）

```
[parse] 12 changes from diff
[ok]  MODIFY  Mob/9999999/name = "已杀怪物数"
[ok]  ADD     0403/04031786 (subtree, 2 nodes)
[ok]  ADD     0403/04031787 (subtree, 2 nodes)
[err] MODIFY  Foo/Bar — node not found
3 applied, 1 failed. Output: D:\out.img (1,335,712 bytes)
```

`--verbose` 时附加：
```
[trace] Parsed 1 hunk at line 5-25, stack=[]
[trace] After patch: img has 1234 nodes, 12 modified
```

---

## 8. 实现里程碑

### M1 — Java MVP（最小可跑）

- [ ] 新建 `xml-img-patcher-java`，pom.xml 用 picocli 出 fat jar
- [ ] `git clone` orange-wz，`mvn install` 到本地 m2
- [ ] 写 `OrangeWzAdapter`：load img、按路径取节点、改 string 值、保存 img
- [ ] **冒烟测试**：手写一个 Change（修改 `Mob/9999999/name`），不解析 diff，直接调 patcher 跑通 `Data/String/Mob.img`，确认输出文件大小变化 < 100B 且文本变了
- [ ] 写 `DiffParser`，先支持 MODIFY 一种（最常见的字符串汉化）
- [ ] 接通：`Mob.img.xml.diff` 端到端能跑

### M2 — Java 完整版

- [ ] DiffParser 支持 ADD（含子树）
- [ ] DiffParser 支持 DELETE
- [ ] 类型支持 INT / SHORT / LONG / FLOAT / DOUBLE
- [ ] 跑通 `Item/Etc/0403.img.xml.diff`（含新增节点的回归用例）
- [ ] 抽样验证：用 orange-wz GUI 打开 patched img，确认 PNG 仍在
- [ ] 真机验证：把 patched Data 放到 BeiDou.exe 旁，启动游戏正常

### M3 — Java native-image（可选）

- [ ] 写 `build-native.bat`（参考 beidou-cli）
- [ ] 解决 orange-wz 中可能的反射/资源问题（reflect-config.json）
- [ ] 出 `xml-img-patcher.exe`（无需 JRE）

### M4 — C# 版

- [ ] 新建 `xml-img-patcher-csharp`，dotnet new console
- [ ] 加 MapleLib-Core NuGet
- [ ] 移植 DiffParser（Java→C#，逻辑相同）
- [ ] 移植 ImgPatcher，使用 MapleLib API
- [ ] 同样跑通 Mob.img + 0403.img 两个用例
- [ ] dotnet publish 出 self-contained .exe

### M5 — 双版一致性

- [ ] 两版用同一组 diff + img 输入，输出 binary 对比（允许 wz 库正常排列差异，但所有节点值应一致）
- [ ] 用 orange-wz GUI 同时打开两版输出做 diff，节点应等价
- [ ] 写 README 收尾

---

## 9. 验证用例

### 9.1 最小冒烟（必跑）

| Case | input.img | diff | 期望 output |
|---|---|---|---|
| **C1: 单字符串修改** | `Data/String/Mob.img` (68KB) | `wz-zh-CN-diff/String.wz/Mob.img.xml.diff` | 大小 ±100B；查 9999999 节点 name = "已杀怪物数" |
| **C2: 节点新增** | `Data/Item/Etc/0403.img` (1.3MB) | `wz-diff/Item.wz/Etc/0403.img.xml.diff` | **大小 ≈ 1.3MB**（绝不允许 70KB）；新增 04031786 / 04031787 节点存在；其他 4031xxx 节点的 PNG 图标完好 |
| **C3: 任务对话汉化** | `Data/Quest/Say.img` (2.3MB) | `wz-zh-CN-diff/Quest.wz/Say.img.xml.diff` | 大小 ±5KB；抽样 3 个对话节点文本变了 |
| **C4: 空 diff** | 任意 | 空 unified diff | 输出与输入二进制相同（或仅 wz 库正常重排） |

### 9.2 真机验证

1. 把 patched 后的 `Data/Item/Etc/0403.img` 放回客户端
2. 启动 `BeiDou.exe`
3. 确认游戏不闪退
4. 进游戏看：
   - 任务道具 04031786 / 04031787 的图标是否能显示（不是问号方块）
   - 怪物名字是否变成"已杀怪物数"
5. 如果 OK，则证明本工具可投入使用

### 9.3 Java vs C# 一致性

两版用同一对 (img, diff) 输入，比较输出：
- 节点级一致：用 orange-wz / HaSuite 各自打开两版输出，遍历节点比对值
- 字节级允许差异：wz 库内部对节点排列、字符串池排列可能略有不同，但语义等价即可

---

## 10. 风险与未决问题

| 风险 | 影响 | 缓解 |
|---|---|---|
| orange-wz 没发布到 Maven Central | 新机器需先 clone+install | README 写清楚步骤；后续可以 fork 后挂 jitpack |
| native-image 对 orange-wz 反射不友好 | exe 编译失败 | M3 设为可选，fat jar 也能用 |
| diff 上下文不足以重建路径 | DiffParser 在某些 hunk 位置失败 | 引入"用 upgrade_20260618 完整 XML 辅助校验"作为 fallback |
| 服务端 XML 顺序与客户端 img 顺序不一致 | 节点找不到 | path 匹配只用名字，不用顺序，问题不大 |
| MapleLib 与 orange-wz 写出的 img 字节级不一致 | 双版校验难做 | 改用节点级比对 |
| GMS WZ key 与 orange-wz 默认 key 不匹配 | img 解析乱码 | 测试时确认；orange-wz 支持多 key 切换 |

未决：
- diff 文件是否会跨 img 文件？（看样例不会，每个 .diff 只对应一个 img）
- DELETE 在本批次 diff 里是否真的出现？需扫一下 20 个 diff 看；如果没有，M2 的 DELETE 可以先不实现

---

## 11. 参考材料

- 参考项目（同作者 / 类似栈）：
  - `E:\LocalGit\Local\beidou-cli` — picocli + GraalVM native-image，pom 模板
  - `E:\LocalGit\GitHub\NapMysqlTool` — Liberica NIK 编译 exe 流程
- WZ 库：
  - https://github.com/lastbattle/MapleLib（C#）
  - https://github.com/leevccc/orange-wz（Java，含 MCP server，用户指定）
  - https://github.com/Kagamia/WzComparerR2（C#，只读，做参考）
- 客户端来源：
  - `E:\LocalGit\GitHub\BeiDou-Client\README.md` 提到 CosmicWZ + Arnuh 特制 HaSuite 工具链
- 上次故障的恢复：
  - 重置到 `e5678d0`，已 force-push 到 `origin/main`（2026-06-18）

---

## 12. 备忘

- **文档优先**：本文档存档于 `E:\LocalGit\Local\xml-img-patcher-plan.md`，避免后续 chat session 丢失上下文导致重做
- **不要在 BeiDou-Client 仓库内创建工具代码**——保持资源仓干净，工具单独项目
- **CLI 出参格式稳定**：方便 AI / shell 脚本解析，每行 `[级别] 操作 路径 = 值` 的固定结构
- **参数传绝对路径**：避免 cwd 歧义，AI 调用时也不必担心当前目录
- 用户母语中文，工具的 stderr/stdout 输出可中文，但 log 关键字（[ok] [err] MODIFY ADD DELETE）保留英文便于脚本解析

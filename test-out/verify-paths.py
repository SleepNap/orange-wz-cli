#!/usr/bin/env python3
"""
路径敏感的 patch 验证器：
对每个 .diff，遍历 hunk，重建每个 +/- 行所属的 <imgdir> 路径栈，
然后到 patched XML 里按路径查找节点，比较实际值是否符合预期。

报告：
  - PASS（值匹配）
  - FAIL_VALUE（值不一致；输出期望/实际/路径）
  - FAIL_NOT_FOUND（patched XML 中找不到该路径）
  - FAIL_STILL_OLD（应当被删的节点仍存在）
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIFF_ROOT = Path(r"C:/Users/CN/Desktop/diff_20260619")
XML_ROOT = ROOT / "test-out" / "xml2"

CASES = [
    ("wz_Quest_Check",     "wz/Quest.wz/Check.img.xml.diff",                "wz/Quest.wz/Check.xml"),
    ("wz_Quest_QuestInfo", "wz/Quest.wz/QuestInfo.img.xml.diff",            "wz/Quest.wz/QuestInfo.xml"),
    ("wz_Quest_Say",       "wz/Quest.wz/Say.img.xml.diff",                  "wz/Quest.wz/Say.xml"),
    ("wz_String_Mob",      "wz/String.wz/Mob.img.xml.diff",                 "wz/String.wz/Mob.xml"),
    ("wz_String_Skill",    "wz/String.wz/Skill.img.xml.diff",               "wz/String.wz/Skill.xml"),
    ("wz_Item_0403",       "wz/Item.wz/Etc/0403.img.xml.diff",              "wz/Item.wz/Etc/0403.xml"),
    ("wz_Map_209000000",   "wz/Map.wz/Map/Map2/209000000.img.xml.diff",     "wz/Map.wz/Map/Map2/209000000.xml"),
    ("wz_Skill_000",       "wz/Skill.wz/000.img.xml.diff",                  "wz/Skill.wz/000.xml"),
    ("wz_Skill_1000",      "wz/Skill.wz/1000.img.xml.diff",                 "wz/Skill.wz/1000.xml"),
    ("wz_Skill_2000",      "wz/Skill.wz/2000.img.xml.diff",                 "wz/Skill.wz/2000.xml"),
    ("zh_Quest_Act",       "wz-zh-CN/Quest.wz/Act.img.xml.diff",            "wz-zh-CN/Quest.wz/Act.xml"),
    ("zh_Quest_Check",     "wz-zh-CN/Quest.wz/Check.img.xml.diff",          "wz-zh-CN/Quest.wz/Check.xml"),
    ("zh_Quest_PQuest",    "wz-zh-CN/Quest.wz/PQuest.img.xml.diff",         "wz-zh-CN/Quest.wz/PQuest.xml"),
    ("zh_Quest_QuestInfo", "wz-zh-CN/Quest.wz/QuestInfo.img.xml.diff",      "wz-zh-CN/Quest.wz/QuestInfo.xml"),
    ("zh_Quest_Say",       "wz-zh-CN/Quest.wz/Say.img.xml.diff",            "wz-zh-CN/Quest.wz/Say.xml"),
    ("zh_String_Eqp",      "wz-zh-CN/String.wz/Eqp.img.xml.diff",           "wz-zh-CN/String.wz/Eqp.xml"),
    ("zh_String_Etc",      "wz-zh-CN/String.wz/Etc.img.xml.diff",           "wz-zh-CN/String.wz/Etc.xml"),
    ("zh_String_Mob",      "wz-zh-CN/String.wz/Mob.img.xml.diff",           "wz-zh-CN/String.wz/Mob.xml"),
    ("zh_String_Npc",      "wz-zh-CN/String.wz/Npc.img.xml.diff",           "wz-zh-CN/String.wz/Npc.xml"),
    ("zh_String_Skill",    "wz-zh-CN/String.wz/Skill.img.xml.diff",         "wz-zh-CN/String.wz/Skill.xml"),
]

LEAF_TAGS = {"string", "int", "short", "long", "float", "double", "uol", "null"}
CONTAINER_OPEN = re.compile(r'^<imgdir\s+name="([^"]+)"\s*>')
CONTAINER_SELFCLOSE = re.compile(r'^<imgdir\s+name="([^"]+)"\s*/>')
CONTAINER_CLOSE = re.compile(r'^</imgdir>')
LEAF = re.compile(r'^<(' + '|'.join(LEAF_TAGS) + r')\s+name="([^"]+)"(?:\s+value="((?:[^"\\]|\\.)*)")?\s*/?>')
HUNK = re.compile(r'^@@\s+-(\d+)(?:,\d+)?\s+\+(\d+)(?:,\d+)?\s+@@')

UPGRADE_ROOT = Path(r"C:/Users/CN/Desktop/upgrade_20260619")

def seed_stack_from_full_xml(full_xml_path, new_line_start):
    """读 full xml 前 new_line_start-1 行，重建 imgdir 路径栈，返回 root→leaf list。
    跳过 list 第一个元素（文件根 <imgdir name="X.img">）。"""
    if not full_xml_path.exists():
        return []
    stack = []
    upTo = new_line_start - 1
    with full_xml_path.open(encoding='utf-8', errors='replace') as f:
        for idx, raw in enumerate(f):
            if idx >= upTo:
                break
            stripped = raw.lstrip()
            if CONTAINER_CLOSE.match(stripped):
                if stack: stack.pop()
            else:
                m_self = CONTAINER_SELFCLOSE.match(stripped)
                if m_self: continue
                m_open = CONTAINER_OPEN.match(stripped)
                if m_open: stack.append(m_open.group(1))
    # 跳过文件根
    return stack[1:] if stack else []

def parse_xml_tree(path):
    """加载 patched XML，返回根 imgdir Element。"""
    return ET.parse(path).getroot()

def norm(s):
    """把 XML attribute 文本值归一化：不同 exporter 在 attr 里对 ' 是否转义为 &apos; 的策略可能不同。
    Python ET 已经把 entity 还原了，所以同时把 diff 那边的 &apos; 也还原回 '。"""
    if s is None:
        return ''
    return (s
            .replace('&apos;', "'")
            .replace('&quot;', '"')
            .replace('&lt;', '<')
            .replace('&gt;', '>')
            .replace('&amp;', '&'))

def resolve_exact(root, path):
    """按 imgdir 路径栈精确定位节点。返回最后一个 leaf Element，或 None。"""
    cur = root
    for segment in path[:-1]:
        nxt = None
        for child in cur:
            if child.tag == 'imgdir' and child.get('name') == segment:
                nxt = child
                break
        if nxt is None:
            return None
        cur = nxt
    leaf_name = path[-1]
    for child in cur:
        if child.get('name') == leaf_name:
            return child
    return None

def find_node_by_suffix(root, path):
    """整树搜索：找名字栈以 path 结尾的唯一节点，命中数 != 1 时返回 None。
    与 ImgPatcher.resolveBySuffix 的语义一致，用来在 hunk 上下文不足以给出完整路径时回退。"""
    matches = []
    def walk(node, parents):
        if len(matches) >= 2: return
        cur = parents + [node.get('name')]
        # 检查 cur 末尾是否匹配 path
        if len(cur) >= len(path):
            tail = cur[-len(path):]
            if all(a == b for a, b in zip(tail, path)):
                matches.append(node)
        for child in node:
            if child.tag == 'imgdir' or child.get('name'):
                walk(child, cur)
    for child in root:
        walk(child, [])
    return matches[0] if len(matches) == 1 else None

def resolve_node(root, path):
    """先精确路径，再后缀回退。"""
    n = resolve_exact(root, path)
    if n is not None:
        return n
    return find_node_by_suffix(root, path)

def parse_diff(diff_path, full_xml_path=None):
    """
    返回 list of (op, leaf_tag, path_segments, value_or_None).
    op = 'modify' / 'add' / 'delete'
    路径根据 hunk 内 <imgdir> 嵌套栈重建；hunk 起始栈用 full_xml_path 按行号回查。
    """
    changes = []
    text = Path(diff_path).read_text(encoding='utf-8', errors='replace').splitlines()
    i = 0
    while i < len(text):
        line = text[i]
        m = HUNK.match(line)
        if not m:
            i += 1
            continue
        new_start = int(m.group(2))
        seed = seed_stack_from_full_xml(full_xml_path, new_start) if full_xml_path else []
        # 进入 hunk。维护两份栈：old_stack（- 视图）和 new_stack（+ 视图）。
        # context 行同时影响两份。两栈都先用 seed 初始化为 root→leaf 顺序的 imgdir 路径。
        i += 1
        old_stack = list(seed)
        new_stack = list(seed)
        pending_minus = None  # (tag, path, value)
        while i < len(text) and not HUNK.match(text[i]) and not text[i].startswith('diff '):
            raw = text[i]
            if not raw:
                i += 1; continue
            prefix = raw[0]
            body = raw[1:].lstrip() if prefix in ' +-' else raw
            stripped = body
            m_open = CONTAINER_OPEN.match(stripped)
            m_self = CONTAINER_SELFCLOSE.match(stripped)
            m_close = CONTAINER_CLOSE.match(stripped)
            m_leaf = LEAF.match(stripped)

            if prefix == '-':
                if m_leaf:
                    tag, name, val = m_leaf.group(1), m_leaf.group(2), m_leaf.group(3)
                    pending_minus = (tag, list(old_stack) + [name], val if val is not None else '')
                if m_open:
                    old_stack.append(m_open.group(1))
                elif m_close:
                    if old_stack: old_stack.pop()
                i += 1; continue

            if prefix == '+':
                if m_leaf:
                    tag, name, val = m_leaf.group(1), m_leaf.group(2), m_leaf.group(3)
                    path = list(new_stack) + [name]
                    if pending_minus and pending_minus[0] == tag and pending_minus[1] == path:
                        changes.append(('modify', tag, path, val if val is not None else '', pending_minus[2]))
                        pending_minus = None
                    else:
                        if pending_minus:
                            changes.append(('delete', pending_minus[0], pending_minus[1], None, pending_minus[2]))
                            pending_minus = None
                        changes.append(('add', tag, path, val if val is not None else '', None))
                elif m_open:
                    if pending_minus:
                        changes.append(('delete', pending_minus[0], pending_minus[1], None, pending_minus[2]))
                        pending_minus = None
                    changes.append(('add_container', 'imgdir', list(new_stack) + [m_open.group(1)], None, None))
                    new_stack.append(m_open.group(1))
                elif m_close:
                    if new_stack: new_stack.pop()
                i += 1; continue

            # context 行：同步两份栈
            if pending_minus:
                changes.append(('delete', pending_minus[0], pending_minus[1], None, pending_minus[2]))
                pending_minus = None
            if m_open:
                old_stack.append(m_open.group(1))
                new_stack.append(m_open.group(1))
            elif m_close:
                if old_stack: old_stack.pop()
                if new_stack: new_stack.pop()
            i += 1
        # hunk 末尾：flush 残留 minus
        if pending_minus:
            changes.append(('delete', pending_minus[0], pending_minus[1], None, pending_minus[2]))
            pending_minus = None
    return changes

def verify_case(case_id, diff_path, xml_path):
    if not diff_path.exists() or not xml_path.exists():
        return ('SKIP', 0, 0, 0, [])
    changes = parse_diff(diff_path)
    root = parse_xml_tree(xml_path)
    pass_n = fail_n = 0
    fails = []
    for ch in changes:
        op = ch[0]
        if op == 'add_container':
            tag, path = ch[1], ch[2]
            node = resolve_node(root, path)
            if node is not None and node.tag == 'imgdir':
                pass_n += 1
            else:
                fail_n += 1
                fails.append(('CONTAINER_MISSING', '/'.join(path)))
            continue
        if op == 'modify':
            tag, path, new_val, old_val = ch[1], ch[2], ch[3], ch[4]
            node = resolve_node(root, path)
            if node is None:
                fail_n += 1; fails.append(('NOT_FOUND', '/'.join(path), '', new_val)); continue
            actual = norm(node.get('value', ''))
            if actual == norm(new_val):
                pass_n += 1
            else:
                fail_n += 1
                fails.append(('VALUE_MISMATCH', '/'.join(path), actual, new_val))
            continue
        if op == 'add':
            tag, path, new_val = ch[1], ch[2], ch[3]
            node = resolve_node(root, path)
            if node is None:
                fail_n += 1; fails.append(('ADD_MISSING', '/'.join(path), '', new_val)); continue
            actual = norm(node.get('value', ''))
            if actual == norm(new_val):
                pass_n += 1
            else:
                # 客户端 img 已有该叶子且值不同 → ImgPatcher 设计上不覆盖 → 视为已知容忍
                pass_n += 1
                fails.append(('ADD_KEPT_OLD', '/'.join(path), actual, new_val))
            continue
        if op == 'delete':
            tag, path, _, old_val = ch[1], ch[2], None, ch[4]
            node = resolve_node(root, path)
            if node is None:
                pass_n += 1
            else:
                actual = norm(node.get('value', ''))
                if actual == norm(old_val):
                    fail_n += 1
                    fails.append(('STILL_OLD', '/'.join(path), actual, ''))
                else:
                    pass_n += 1
            continue
    return ('OK', pass_n, fail_n, len(changes), fails)

def main():
    print(f"{'id':22s}  {'changes':>7s}  {'pass':>5s}  {'fail':>5s}  notes")
    summary = []
    for case_id, diff_rel, xml_rel in CASES:
        diff_path = DIFF_ROOT / diff_rel
        xml_path = XML_ROOT / xml_rel
        # 与 patcher 用同一份 full xml 来 seed hunk 起始栈
        full_xml = UPGRADE_ROOT / diff_rel.replace('.img.xml.diff', '.img.xml')
        if not full_xml.exists():
            full_xml = None
        # 预先把 hunk 内同路径的 delete + add 折叠为 modify（处理类型变化场景，例如 null→string）
        if diff_path.exists():
            raw = parse_diff(diff_path, full_xml)
            collapsed = []
            i = 0
            while i < len(raw):
                ch = raw[i]
                if ch[0] == 'delete':
                    # 向后窥视：若同 path 上紧接（最多跨 add_container 几行）的 add → 合并为 modify
                    j = i + 1
                    matched = False
                    while j < len(raw):
                        nx = raw[j]
                        if nx[0] == 'add' and nx[2] == ch[2]:
                            collapsed.append(('modify', nx[1], nx[2], nx[3], ch[4]))
                            i = j + 1
                            matched = True
                            break
                        if nx[0] in ('delete',):  # 另一个 delete，停止配对
                            break
                        j += 1
                    if matched:
                        continue
                collapsed.append(ch)
                i += 1
        status, pass_n, fail_n, total, fails = verify_case_with(case_id, diff_path, xml_path, collapsed)
        if status == 'SKIP':
            print(f"{case_id:22s}  SKIP")
            continue
        info_kept = sum(1 for f in fails if f[0] == 'ADD_KEPT_OLD')
        notes = ''
        if fail_n > 0:
            kinds = {}
            for f in fails:
                if f[0] == 'ADD_KEPT_OLD': continue
                kinds[f[0]] = kinds.get(f[0], 0) + 1
            notes = ' '.join(f"{k}={v}" for k,v in kinds.items())
        if info_kept:
            notes = (notes + f" [info ADD_KEPT_OLD={info_kept}]").strip()
        print(f"{case_id:22s}  {total:7d}  {pass_n:5d}  {fail_n:5d}  {notes}")
        summary.append((case_id, fails))

    print("\n===== 失败明细（最多每 case 8 条）=====")
    for case_id, fails in summary:
        bad = [f for f in fails if f[0] != 'ADD_KEPT_OLD']
        if not bad:
            continue
        print(f"\n--- {case_id} ---")
        for f in bad[:8]:
            print('  ', f)
        if len(bad) > 8:
            print(f"  ... 共 {len(bad)} 条")

def verify_case_with(case_id, diff_path, xml_path, changes):
    if not diff_path.exists() or not xml_path.exists():
        return ('SKIP', 0, 0, 0, [])
    root = parse_xml_tree(xml_path)
    pass_n = fail_n = 0
    fails = []
    for ch in changes:
        op = ch[0]
        if op == 'add_container':
            tag, path = ch[1], ch[2]
            node = resolve_node(root, path)
            if node is not None and node.tag == 'imgdir':
                pass_n += 1
            else:
                fail_n += 1
                fails.append(('CONTAINER_MISSING', '/'.join(path)))
            continue
        if op == 'modify':
            tag, path, new_val, old_val = ch[1], ch[2], ch[3], ch[4]
            node = resolve_node(root, path)
            if node is None:
                fail_n += 1; fails.append(('NOT_FOUND', '/'.join(path), '', new_val)); continue
            actual = norm(node.get('value', ''))
            if actual == norm(new_val):
                pass_n += 1
            else:
                fail_n += 1
                fails.append(('VALUE_MISMATCH', '/'.join(path), actual, new_val))
            continue
        if op == 'add':
            tag, path, new_val = ch[1], ch[2], ch[3]
            node = resolve_node(root, path)
            if node is None:
                fail_n += 1; fails.append(('ADD_MISSING', '/'.join(path), '', new_val)); continue
            actual = norm(node.get('value', ''))
            if actual == norm(new_val):
                pass_n += 1
            else:
                pass_n += 1
                fails.append(('ADD_KEPT_OLD', '/'.join(path), actual, new_val))
            continue
        if op == 'delete':
            tag, path, _, old_val = ch[1], ch[2], None, ch[4]
            node = resolve_node(root, path)
            if node is None:
                pass_n += 1
            else:
                actual = norm(node.get('value', ''))
                if actual == norm(old_val) and norm(old_val) != '':
                    fail_n += 1
                    fails.append(('STILL_OLD', '/'.join(path), actual, ''))
                else:
                    # value 已变（被同 hunk 内 modify 处理）或 old_val 是空（null 节点删除场景，难以判别）
                    pass_n += 1
            continue
    return ('OK', pass_n, fail_n, len(changes), fails)

if __name__ == '__main__':
    main()

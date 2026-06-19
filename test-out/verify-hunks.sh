#!/usr/bin/env bash
# 验证 diff 实际生效：
#   对每个 .diff 文件
#     - 把所有 '^+' 加行（不含 '+++') 抽出，期望它们都出现在 patched XML 中
#     - 把所有 '^-' 删行（不含 '---') 抽出，期望它们都已不在 patched XML 中
#   只比较 <... value="..."/> 这种叶子节点行——结构性的 <imgdir name="X"> 行可能两边都存在
set -u
cd "$(dirname "$0")/.."

DIFF=C:/Users/CN/Desktop/diff_20260619
OUT=test-out

declare -A MAP=(
  [wz_Quest_Check]="wz/Quest.wz/Check.img.xml.diff:wz/Quest.wz/Check.xml"
  [wz_Quest_QuestInfo]="wz/Quest.wz/QuestInfo.img.xml.diff:wz/Quest.wz/QuestInfo.xml"
  [wz_Quest_Say]="wz/Quest.wz/Say.img.xml.diff:wz/Quest.wz/Say.xml"
  [wz_String_Mob]="wz/String.wz/Mob.img.xml.diff:wz/String.wz/Mob.xml"
  [wz_String_Skill]="wz/String.wz/Skill.img.xml.diff:wz/String.wz/Skill.xml"
  [wz_Item_0403]="wz/Item.wz/Etc/0403.img.xml.diff:wz/Item.wz/Etc/0403.xml"
  [wz_Map_209000000]="wz/Map.wz/Map/Map2/209000000.img.xml.diff:wz/Map.wz/Map/Map2/209000000.xml"
  [wz_Skill_000]="wz/Skill.wz/000.img.xml.diff:wz/Skill.wz/000.xml"
  [wz_Skill_1000]="wz/Skill.wz/1000.img.xml.diff:wz/Skill.wz/1000.xml"
  [wz_Skill_2000]="wz/Skill.wz/2000.img.xml.diff:wz/Skill.wz/2000.xml"
  [zh_Quest_Act]="wz-zh-CN/Quest.wz/Act.img.xml.diff:wz-zh-CN/Quest.wz/Act.xml"
  [zh_Quest_Check]="wz-zh-CN/Quest.wz/Check.img.xml.diff:wz-zh-CN/Quest.wz/Check.xml"
  [zh_Quest_PQuest]="wz-zh-CN/Quest.wz/PQuest.img.xml.diff:wz-zh-CN/Quest.wz/PQuest.xml"
  [zh_Quest_QuestInfo]="wz-zh-CN/Quest.wz/QuestInfo.img.xml.diff:wz-zh-CN/Quest.wz/QuestInfo.xml"
  [zh_Quest_Say]="wz-zh-CN/Quest.wz/Say.img.xml.diff:wz-zh-CN/Quest.wz/Say.xml"
  [zh_String_Eqp]="wz-zh-CN/String.wz/Eqp.img.xml.diff:wz-zh-CN/String.wz/Eqp.xml"
  [zh_String_Etc]="wz-zh-CN/String.wz/Etc.img.xml.diff:wz-zh-CN/String.wz/Etc.xml"
  [zh_String_Mob]="wz-zh-CN/String.wz/Mob.img.xml.diff:wz-zh-CN/String.wz/Mob.xml"
  [zh_String_Npc]="wz-zh-CN/String.wz/Npc.img.xml.diff:wz-zh-CN/String.wz/Npc.xml"
  [zh_String_Skill]="wz-zh-CN/String.wz/Skill.img.xml.diff:wz-zh-CN/String.wz/Skill.xml"
)

mkdir -p "$OUT/verify"
printf '%-22s  %-14s  %-14s  %s\n' "id" "added(want_in)" "removed(want_out)" "miss/wrong"

for id in "${!MAP[@]}"; do
  IFS=':' read -r diff_rel xml_rel <<<"${MAP[$id]}"
  diff_path="$DIFF/$diff_rel"
  xml_path="$OUT/xml2/$xml_rel"

  if [[ ! -f "$diff_path" || ! -f "$xml_path" ]]; then
    printf '%-22s  %s\n' "$id" "SKIP (missing input)"
    continue
  fi

  # 抽出叶子节点 + 行 / - 行（含 value 属性的，结构容器行 <imgdir name=...> 不计）
  added_file="$OUT/verify/$id.added.txt"
  removed_file="$OUT/verify/$id.removed.txt"
  grep -E '^\+[[:space:]]*<(string|int|short|long|float|double|uol)\s+name="[^"]+"\s+value=' "$diff_path" \
    | sed 's/^\+//' | sed 's/^[[:space:]]*//' | sort -u > "$added_file"
  grep -E '^-[[:space:]]*<(string|int|short|long|float|double|uol)\s+name="[^"]+"\s+value=' "$diff_path" \
    | sed 's/^-//' | sed 's/^[[:space:]]*//' | sort -u > "$removed_file"

  added_total=$(wc -l < "$added_file")
  removed_total=$(wc -l < "$removed_file")

  # patched xml 行（去缩进）
  xml_norm="$OUT/verify/$id.xml.norm.txt"
  sed 's/^[[:space:]]*//' "$xml_path" | sort -u > "$xml_norm"

  # 应当在的：want_in 中不在 xml 的行数
  miss_in=$(comm -23 "$added_file" "$xml_norm" | wc -l)
  # 应当不在的：want_out 中仍在 xml 的行数
  still_in=$(comm -12 "$removed_file" "$xml_norm" | wc -l)

  status="ok"
  if (( miss_in > 0 || still_in > 0 )); then
    status="MISS_IN=$miss_in STILL_IN=$still_in"
  fi
  printf '%-22s  %-14s  %-14s  %s\n' "$id" "$added_total" "$removed_total" "$status"
done | sort

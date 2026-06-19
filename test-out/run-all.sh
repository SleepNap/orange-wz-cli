#!/usr/bin/env bash
# 一次性跑完全部 diff → patch → export-xml 流程
set -u
cd "$(dirname "$0")/.."

JAR=target/xml-img-patcher.jar
DIFF=C:/Users/CN/Desktop/diff_20260619
UPG=C:/Users/CN/Desktop/upgrade_20260619
OUT=test-out

# 格式: <case-id>|<input.img>|<diff>|<output basename> (相对 patched/, xml/)
CASES=(
  # ---- wz/ (服务端英文主线) → EN/ 优先 ----
  "wz_Quest_Check|$OUT/src/en/Quest/Check.img|$DIFF/wz/Quest.wz/Check.img.xml.diff|wz/Quest.wz/Check.img"
  "wz_Quest_QuestInfo|$OUT/src/en/Quest/QuestInfo.img|$DIFF/wz/Quest.wz/QuestInfo.img.xml.diff|wz/Quest.wz/QuestInfo.img"
  "wz_Quest_Say|$OUT/src/en/Quest/Say.img|$DIFF/wz/Quest.wz/Say.img.xml.diff|wz/Quest.wz/Say.img"
  "wz_String_Mob|$OUT/src/en/String/Mob.img|$DIFF/wz/String.wz/Mob.img.xml.diff|wz/String.wz/Mob.img"
  "wz_String_Skill|$OUT/src/en/String/Skill.img|$DIFF/wz/String.wz/Skill.img.xml.diff|wz/String.wz/Skill.img"
  # ---- wz/ 但 EN 没有，回落到 Data/ (这几个 plan 里标为 corrupt — 见报告) ----
  "wz_Item_0403|$OUT/src/data-shared/Item/Etc/0403.img|$DIFF/wz/Item.wz/Etc/0403.img.xml.diff|wz/Item.wz/Etc/0403.img"
  "wz_Map_209000000|$OUT/src/data-shared/Map/Map/Map2/209000000.img|$DIFF/wz/Map.wz/Map/Map2/209000000.img.xml.diff|wz/Map.wz/Map/Map2/209000000.img"
  "wz_Skill_000|$OUT/src/data-shared/Skill/000.img|$DIFF/wz/Skill.wz/000.img.xml.diff|wz/Skill.wz/000.img"
  "wz_Skill_1000|$OUT/src/data-shared/Skill/1000.img|$DIFF/wz/Skill.wz/1000.img.xml.diff|wz/Skill.wz/1000.img"
  "wz_Skill_2000|$OUT/src/data-shared/Skill/2000.img|$DIFF/wz/Skill.wz/2000.img.xml.diff|wz/Skill.wz/2000.img"
  # ---- wz-zh-CN/ (中文层) → Data/ ----
  "zh_Quest_Act|$OUT/src/zh/Quest/Act.img|$DIFF/wz-zh-CN/Quest.wz/Act.img.xml.diff|wz-zh-CN/Quest.wz/Act.img"
  "zh_Quest_Check|$OUT/src/zh/Quest/Check.img|$DIFF/wz-zh-CN/Quest.wz/Check.img.xml.diff|wz-zh-CN/Quest.wz/Check.img"
  "zh_Quest_PQuest|$OUT/src/zh/Quest/PQuest.img|$DIFF/wz-zh-CN/Quest.wz/PQuest.img.xml.diff|wz-zh-CN/Quest.wz/PQuest.img"
  "zh_Quest_QuestInfo|$OUT/src/zh/Quest/QuestInfo.img|$DIFF/wz-zh-CN/Quest.wz/QuestInfo.img.xml.diff|wz-zh-CN/Quest.wz/QuestInfo.img"
  "zh_Quest_Say|$OUT/src/zh/Quest/Say.img|$DIFF/wz-zh-CN/Quest.wz/Say.img.xml.diff|wz-zh-CN/Quest.wz/Say.img"
  "zh_String_Eqp|$OUT/src/zh/String/Eqp.img|$DIFF/wz-zh-CN/String.wz/Eqp.img.xml.diff|wz-zh-CN/String.wz/Eqp.img"
  "zh_String_Etc|$OUT/src/zh/String/Etc.img|$DIFF/wz-zh-CN/String.wz/Etc.img.xml.diff|wz-zh-CN/String.wz/Etc.img"
  "zh_String_Mob|$OUT/src/zh/String/Mob.img|$DIFF/wz-zh-CN/String.wz/Mob.img.xml.diff|wz-zh-CN/String.wz/Mob.img"
  "zh_String_Npc|$OUT/src/zh/String/Npc.img|$DIFF/wz-zh-CN/String.wz/Npc.img.xml.diff|wz-zh-CN/String.wz/Npc.img"
  "zh_String_Skill|$OUT/src/zh/String/Skill.img|$DIFF/wz-zh-CN/String.wz/Skill.img.xml.diff|wz-zh-CN/String.wz/Skill.img"
)

mkdir -p "$OUT/patched" "$OUT/xml" "$OUT/log"

for entry in "${CASES[@]}"; do
  IFS='|' read -r id src diff target <<<"$entry"
  patched="$OUT/patched/$target"
  xml="$OUT/xml/${target%.img}.xml"
  mkdir -p "$(dirname "$patched")" "$(dirname "$xml")"
  log="$OUT/log/$id.log"

  echo "===== $id =====" | tee "$log"
  if [[ ! -f "$src" ]]; then
    echo "[skip] 源 img 不存在: $src" | tee -a "$log"
    continue
  fi
  if [[ ! -f "$diff" ]]; then
    echo "[skip] diff 不存在: $diff" | tee -a "$log"
    continue
  fi

  java -jar "$JAR" patch "$src" "$diff" "$patched" --full-xml-dir "$UPG" >>"$log" 2>&1
  rc=$?
  echo "patch exit=$rc" >>"$log"
  if [[ $rc -eq 0 || $rc -eq 1 ]]; then
    java -jar "$JAR" export-xml "$patched" "$xml" >>"$log" 2>&1
    echo "export exit=$?" >>"$log"
  fi
  tail -3 "$log"
  echo
done

echo '===== summary ====='
for entry in "${CASES[@]}"; do
  IFS='|' read -r id src diff target <<<"$entry"
  log="$OUT/log/$id.log"
  if [[ ! -f "$log" ]]; then printf '%-25s  no-log\n' "$id"; continue; fi
  applied=$(grep -oE '^[0-9]+ applied' "$log" | tail -1 | awk '{print $1}')
  failed=$(grep -oE '[0-9]+ failed' "$log" | tail -1 | awk '{print $1}')
  printf '%-25s  applied=%-4s failed=%-4s\n' "$id" "${applied:-?}" "${failed:-?}"
done

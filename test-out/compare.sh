#!/usr/bin/env bash
# 把 patched 导出的 xml 与 upgrade 目录的目标 xml 做语义对比。
# 归一化策略：
# 1) 去掉根节点的 indent/media 属性（exporter 自己加的元信息）
# 2) 两边都 strip 首尾空白后逐行比较（缩进已在 export 时设为 2 空格 / LF）
set -u
cd "$(dirname "$0")/.."

UPG=C:/Users/CN/Desktop/upgrade_20260619
OUT=test-out

normalize() {
  sed -E 's/ indent="[0-9]+"//; s/ media="[A-Z_]+"//' "$1" \
    | sed 's/[[:space:]]*$//' \
    | sed -E 's#^([[:space:]]*)#\1#'
}

declare -A MAP=(
  [wz/Quest.wz/Check]=wz/Quest.wz/Check.img.xml
  [wz/Quest.wz/QuestInfo]=wz/Quest.wz/QuestInfo.img.xml
  [wz/Quest.wz/Say]=wz/Quest.wz/Say.img.xml
  [wz/String.wz/Mob]=wz/String.wz/Mob.img.xml
  [wz/String.wz/Skill]=wz/String.wz/Skill.img.xml
  [wz/Item.wz/Etc/0403]=wz/Item.wz/Etc/0403.img.xml
  [wz/Map.wz/Map/Map2/209000000]=wz/Map.wz/Map/Map2/209000000.img.xml
  [wz/Skill.wz/000]=wz/Skill.wz/000.img.xml
  [wz/Skill.wz/1000]=wz/Skill.wz/1000.img.xml
  [wz/Skill.wz/2000]=wz/Skill.wz/2000.img.xml
  [wz-zh-CN/Quest.wz/Act]=wz-zh-CN/Quest.wz/Act.img.xml
  [wz-zh-CN/Quest.wz/Check]=wz-zh-CN/Quest.wz/Check.img.xml
  [wz-zh-CN/Quest.wz/PQuest]=wz-zh-CN/Quest.wz/PQuest.img.xml
  [wz-zh-CN/Quest.wz/QuestInfo]=wz-zh-CN/Quest.wz/QuestInfo.img.xml
  [wz-zh-CN/Quest.wz/Say]=wz-zh-CN/Quest.wz/Say.img.xml
  [wz-zh-CN/String.wz/Eqp]=wz-zh-CN/String.wz/Eqp.img.xml
  [wz-zh-CN/String.wz/Etc]=wz-zh-CN/String.wz/Etc.img.xml
  [wz-zh-CN/String.wz/Mob]=wz-zh-CN/String.wz/Mob.img.xml
  [wz-zh-CN/String.wz/Npc]=wz-zh-CN/String.wz/Npc.img.xml
  [wz-zh-CN/String.wz/Skill]=wz-zh-CN/String.wz/Skill.img.xml
)

mkdir -p "$OUT/cmp"
echo "id                                  patched-lines  upgrade-lines  diff-lines"
for key in "${!MAP[@]}"; do
  patched_xml="$OUT/xml2/$key.xml"
  upgrade_xml="$UPG/${MAP[$key]}"
  if [[ ! -f "$patched_xml" ]]; then echo "$key  patched 不存在"; continue; fi
  if [[ ! -f "$upgrade_xml" ]]; then echo "$key  upgrade 不存在"; continue; fi
  pn="$OUT/cmp/$(echo "$key" | tr '/' '_').patched.xml"
  un="$OUT/cmp/$(echo "$key" | tr '/' '_').upgrade.xml"
  normalize "$patched_xml" > "$pn"
  normalize "$upgrade_xml" > "$un"
  pl=$(wc -l < "$pn")
  ul=$(wc -l < "$un")
  dl=$(diff "$pn" "$un" 2>/dev/null | wc -l)
  printf '%-37s  %-13s  %-13s  %s\n' "$key" "$pl" "$ul" "$dl"
done | sort

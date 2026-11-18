#!/bin/sh
# 界面样式自检：对应 DESIGN.md 的「界面分层」与「微信内面板零件」两节。
# 用法: sh tools/check-ui-style.sh
#
# 只拦最容易把风格带歪的三类：自绘窗口里的宿主 AlertDialog、临时色值、写死字号。
# 已有欠账放在 KNOWN_* 里，新文件不在名单里就会报错。
set -u

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
HOOK="$ROOT/app/src/main/java/dev/jev/wechatmood/hook"

# 色板、自绘控件、贴宿主的入口：这些地方本来就该自己写色值和像素。
COLOR_ALLOW="ReplyTheme.kt|StatsImage.kt|EmotionRingSpan.kt|FrostedAnalysisView.kt|AnalysisCardText.kt|BubbleDecorator.kt|MessageMenu.kt|PanelKit.kt"
# 历史欠账：还在用宿主 AlertDialog，等改成 PanelKit 后再从名单里删掉。
ALERT_KNOWN="ReplyHostUi.kt|HostUi.kt|PanelKit.kt"

fail=0

echo "== 必须修：自绘窗口用了宿主 AlertDialog =="
hits=$(grep -rn "AlertDialog" "$HOOK" --include="*.kt" 2>/dev/null | grep -Ev "$ALERT_KNOWN" || true)
if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed 's/^/  /'
    fail=1
else
    echo "  （无新增）"
fi

echo
echo "== 必须修：窗口里写临时色值 =="
hits=$(grep -rn "Color.parseColor" "$HOOK" --include="*.kt" 2>/dev/null | grep -Ev "$COLOR_ALLOW" || true)
if [ -n "$hits" ]; then
    printf '%s\n' "$hits" | sed 's/^/  /'
    fail=1
else
    echo "  （无）"
fi

echo
echo "== 建议改：写死的字号（改用 ReplyMetrics.TEXT_*）=="
grep -rn "textSize = [0-9]" "$HOOK" --include="*.kt" 2>/dev/null | sed 's/^/  /' || true

echo
echo "== 建议改：直接写死的色值 =="
grep -rn "setTextColor(0x\|0xFF[0-9A-Fa-f]\{6\}" "$HOOK" --include="*.kt" 2>/dev/null \
    | grep -Ev "$COLOR_ALLOW" | sed 's/^/  /' || true

echo
echo "== 已知欠账（不影响本次提交）=="
echo "  ReplyHostUi.kt / HostUi.kt 仍在用宿主 AlertDialog，替换成 kit.picker / kit.info 后从脚本名单里删掉。"

echo
if [ "$fail" -ne 0 ]; then
    echo "有必须修的问题：微信内窗口统一走 PanelKit + ReplyTheme，规则见 DESIGN.md。"
    exit 1
fi
echo "OK：没有发现新增的违规。"

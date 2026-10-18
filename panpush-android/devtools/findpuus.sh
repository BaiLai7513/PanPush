#!/system/bin/sh
# 全盘找 __puus（夸克/UC 的网盘令牌），看看到底存在哪个 App 的哪个文件里。
# 必须跑在全局挂载命名空间（root + nsenter）。
echo "=== 1) 夸克浏览器 / UC 下的 cookie 类文件 ==="
for P in com.quark.browser com.UCMobile; do
  echo "--- $P"
  ls -la /data/data/$P/ 2>/dev/null | head -30
  echo "  *webview* 目录:"
  ls -d /data/data/$P/*webview* 2>/dev/null
done

echo ""
echo "=== 2) 在夸克/UC 的 app_webview 里找 Cookies（含 -wal）==="
for P in com.quark.browser com.UCMobile; do
  for D in /data/data/$P/*webview*; do
    [ -d "$D" ] || continue
    echo "--- $D"
    ls -la "$D/Default/" 2>/dev/null | head -20
  done
done

echo ""
echo "=== 3) 关键词搜索：__puus / pan.quark.cn 出现在哪些文件里 ==="
for P in com.quark.browser com.UCMobile; do
  for F in $(find /data/data/$P -maxdepth 4 -type f 2>/dev/null | head -400); do
    if strings "$F" 2>/dev/null | grep -q -e '__puus' -e 'pan\.quark\.cn'; then
      echo "  HIT: $F  ($(stat -c %s "$F" 2>/dev/null) bytes)"
    fi
  done
done

echo ""
echo "=== 4) 夸克网盘 App 是否存在（可能不是 com.quark.browser）==="
pm list packages 2>/dev/null | grep -iE 'quark|ucmobile|uc\.browser|pan'

echo ""
echo "=== done ==="

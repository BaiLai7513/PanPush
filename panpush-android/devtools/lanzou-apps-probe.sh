#!/system/bin/sh
# 查第三方蓝奏客户端是否存了明文凭据
for p in com.tooyoung.lanzou com.lancloud.lancloud com.guang.cloudx; do
  echo "===== $p ====="
  if [ ! -d "/data/data/$p" ]; then
    echo "  (无数据目录)"
    continue
  fi
  echo "--- 顶层 ---"
  ls -1 "/data/data/$p" 2>&1
  echo "--- shared_prefs ---"
  ls -1 "/data/data/$p/shared_prefs" 2>&1
  echo "--- databases ---"
  ls -1 "/data/data/$p/databases" 2>&1
  echo "--- files ---"
  ls -1 "/data/data/$p/files" 2>&1
  echo ""
done
echo "===== done ====="

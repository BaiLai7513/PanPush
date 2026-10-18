#!/system/bin/sh
# 找出阿里云盘 databases 下哪个库真正含 tb_user_info（表可能只在 -wal 里）
# 必须跑在全局挂载命名空间（root + nsenter）
DBDIR=/data/data/com.alicloud.databox/databases
T=/data/local/tmp/alifind
rm -rf $T; mkdir -p $T; cd $T

for f in $DBDIR/*.db; do
  n=$(basename "$f")
  rm -f probe.db probe.db-wal probe.db-shm
  cp -f "$f" probe.db 2>/dev/null
  cp -f "$f-wal" probe.db-wal 2>/dev/null
  cp -f "$f-shm" probe.db-shm 2>/dev/null
  chmod 666 probe.db probe.db-wal probe.db-shm 2>/dev/null

  # 用 grep 在库+wal 里找表名（sqlite3 不在，用 strings+grep 兜底）
  hit=$(cat probe.db probe.db-wal 2>/dev/null | strings | grep -c 'tb_user_info')
  size=$(stat -c %s probe.db 2>/dev/null)
  walsize=$(stat -c %s probe.db-wal 2>/dev/null)
  echo "$n  db=$size  wal=${walsize:-0}  tb_user_info_hits=$hit"
done
echo "--- done ---"

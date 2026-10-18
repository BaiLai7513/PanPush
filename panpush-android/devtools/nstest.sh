#!/system/bin/sh
# Test a command in a MOUNT NAMESPACE SIBLING to the app.
# Motivation: APatch/Magisk app-isolation makes the app's mount namespace a *sibling*
# (not a child) of the global one, so `nsenter -t 1 -m` fails. But `nsenter -m <pid>`
# with a process that lives in the GLOBAL namespace works.
TARGET=com.xunlei.downloadprovider

echo "=== 0) baseline: our own namespace ==="
ls -ld /data/data/$TARGET 2>&1

echo "=== 1) candidate PIDs in the global mount namespace ==="
for p in 1 2 $$ ; do
  echo -n "pid=$p lsm= "
  cat /proc/$p/attr/current 2>/dev/null | head -c 40
  echo -n "  mnt="
  readlink /proc/$p/ns/mnt 2>/dev/null
done

echo "=== 2) try nsenter -t <pid> -m for a few well-known root daemons ==="
# magiskd / apd / init / zygote / system_server
for name in magiskd apd init zygote zygote64 system_server; do
  PIDS=$(pidof $name 2>/dev/null)
  for p in $PIDS; do
    OUT=$(nsenter -t $p -m -- ls -ld /data/data/$TARGET 2>&1)
    echo "  nsenter -t $p -m ($name) => $OUT"
  done
done

echo "=== 3) brute force: find ANY pid whose mount ns can see the target ==="
FOUND=0
for d in /proc/[0-9]*; do
  p=${d#/proc/}
  if nsenter -t $p -m -- ls -d /data/data/$TARGET >/dev/null 2>&1; then
    echo "  HIT pid=$p  comm=$(cat /proc/$p/comm 2>/dev/null)"
    FOUND=$((FOUND+1))
    [ $FOUND -ge 3 ] && break
  fi
done
[ $FOUND -eq 0 ] && echo "  no hit"

echo "=== 4) does nsenter -t 1 -m work at all? ==="
nsenter -t 1 -m -- ls -ld /data/data/$TARGET 2>&1

echo "=== 5) unshare -m then check ==="
unshare -m -- ls -ld /data/data/$TARGET 2>&1

echo "=== 6) done ==="

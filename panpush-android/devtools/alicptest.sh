#!/system/bin/sh
# 在 app 的 mount namespace 里，逐条对比 execNs 的两次尝试到底哪次成功
echo "=== baseline: 不带 nsenter ==="
ls -ld /data/data/com.alicloud.databox/databases/0.db 2>&1
echo "=== 带 nsenter --mount -t 1 ==="
nsenter --mount -t 1 -- /system/bin/ls -ld /data/data/com.alicloud.databox/databases/0.db 2>&1
echo "=== cp 测试（带 nsenter，写 /data/local/tmp）==="
nsenter --mount -t 1 -- cp -f /data/data/com.alicloud.databox/databases/0.db /data/local/tmp/cptest.db 2>&1
ls -la /data/local/tmp/cptest.db 2>&1
echo "=== cp 测试（不带 nsenter）==="
cp -f /data/data/com.alicloud.databox/databases/0.db /data/local/tmp/cptest2.db 2>&1
ls -la /data/local/tmp/cptest2.db 2>&1
echo "=== 写进 app 私有 cache（目标场景）==="
nsenter --mount -t 1 -- cp -f /data/data/com.alicloud.databox/databases/0.db /data/user/0/com.panpush.app/cache/ali/probe.db 2>&1
ls -la /data/user/0/com.panpush.app/cache/ali/ 2>&1
echo "=== done ==="

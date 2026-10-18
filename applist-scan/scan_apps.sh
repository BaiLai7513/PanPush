#!/system/bin/sh
# 应用列表扫描（读取凭据前的第 0 步）
# 用法：sh scan_apps.sh
# 说明：必须用 root 扫（普通 App 身份会被 Android 包可见性屏蔽）。

echo "=== 1) 目标站点包名匹配（root） ==="
su -c "pm list packages" 2>/dev/null | grep -iE 'xunlei|databox|mfcloud|quark|UCMobile|baidu.netdisk'

echo
echo "=== 2) 逐站点存在性 ==="
for p in \
  com.xunlei.downloadprovider \
  com.alicloud.databox \
  com.mfcloudcalculate.networkdisk \
  com.baidu.netdisk \
  com.quark.browser \
  com.UCMobile
do
  if [ -n "$(su -c "pm path $p" 2>/dev/null)" ]; then
    echo "OK   $p"
  else
    echo "MISS $p"
  fi
done

echo
echo "=== 3) 兜底：数据目录 ==="
ls /data/data 2>/dev/null | grep -iE 'xunlei|databox|mfcloud|quark|UCMobile|baidu.netdisk'

echo
echo "=== 4) 包可见性对照（普通 App vs root） ==="
echo "root  可见包数: $(su -c 'pm list packages' 2>/dev/null | wc -l)"
echo "done"

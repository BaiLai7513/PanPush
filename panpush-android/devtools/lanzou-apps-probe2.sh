#!/system/bin/sh
echo "===== com.tooyoung.lanzou / appInfo.xml ====="
cat /data/data/com.tooyoung.lanzou/shared_prefs/appInfo.xml 2>&1 | cut -c1-1200
echo ""
echo "===== com.tooyoung.lanzou / __local_last_session.json ====="
cat /data/data/com.tooyoung.lanzou/files/__local_last_session.json 2>&1 | cut -c1-800
echo ""
echo "===== com.tooyoung.lanzou / account.lua (前 900) ====="
head -c 900 /data/data/com.tooyoung.lanzou/files/account.lua 2>&1
echo ""
echo "===== com.tooyoung.lanzou / 搜 token/cookie 关键词 ====="
grep -ril "token\|cookie\|uid\|session" /data/data/com.tooyoung.lanzou/files /data/data/com.tooyoung.lanzou/shared_prefs 2>/dev/null | head -10
echo ""
echo "===== com.lancloud.lancloud / FlutterSecureStorage.xml ====="
cat /data/data/com.lancloud.lancloud/shared_prefs/FlutterSecureStorage.xml 2>&1 | cut -c1-900
echo ""
echo "===== com.lancloud.lancloud / FlutterSharedPreferences.xml ====="
cat /data/data/com.lancloud.lancloud/shared_prefs/FlutterSharedPreferences.xml 2>&1 | cut -c1-900
echo ""
echo "===== com.lancloud.lancloud / lancloud.db 表 ====="
strings /data/data/com.lancloud.lancloud/databases/lancloud.db 2>/dev/null | grep -i "CREATE TABLE" | head -10
echo ""
echo "===== done ====="

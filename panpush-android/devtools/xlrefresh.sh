#!/system/bin/sh
# 在设备上直接验证「迅雷 access_token 续期」整条链路
# 关键对照：浏览器 UA → 403；okhttp/4.9.0 → 200
P=/data/data/com.xunlei.downloadprovider/shared_prefs/auth_pref.xml
AT=$(sed -n 's/.*name="access_token">\([^<]*\)<.*/\1/p' $P 2>/dev/null)
RT=$(sed -n 's/.*name="refresh_token">\([^<]*\)<.*/\1/p' $P 2>/dev/null)

echo "access_token  = ${#AT} chars (前12: $(echo $AT | cut -c1-12))"
echo "refresh_token = ${#RT} chars (前12: $(echo $RT | cut -c1-12))"
[ -z "$RT" ] && { echo "没有 refresh_token，无法续期"; exit 1; }

BODY="client_id=Xp6vsxz_7IYVw2BB&client_secret=cXfZ8FGVvEQP5Xv1&grant_type=refresh_token&refresh_token=$RT"
URL=https://xluser-ssl.xunlei.com/v1/auth/token

echo ""
echo "=== A) 用浏览器 UA（预期 403 / 被拦）==="
curl -s -m 20 -o /tmp/xl_a.txt -w "HTTP %{http_code}\n" \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "$BODY" "$URL"
head -c 300 /tmp/xl_a.txt; echo ""

echo ""
echo "=== B) 用客户端 UA okhttp/4.9.0（预期 200 + 新 token）==="
curl -s -m 20 -o /tmp/xl_b.txt -w "HTTP %{http_code}\n" \
  -H 'User-Agent: okhttp/4.9.0' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "$BODY" "$URL"
head -c 300 /tmp/xl_b.txt; echo ""
echo ""
echo "=== done ==="

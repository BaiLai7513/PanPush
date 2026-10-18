# Device-side credential probe (run on PC, via adb + su).
# Purpose: confirm the REAL files / keys / formats on the device BEFORE changing the app,
#          so we stop guessing from docs. Every output below maps to one parser in the app.
#
# Run:  powershell -NoProfile -ExecutionPolicy Bypass -File device-probe.ps1

$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$out = Join-Path $PSScriptRoot "probe-out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Sh([string]$cmd) {
    $full = "su -c " + [char]39 + $cmd + [char]39
    & $adb shell $full 2>&1
}
function Section([string]$t) { Write-Output ("`n=== " + $t + " ===") }
function Save([string]$name, $lines) {
    ($lines -join "`n") | Set-Content (Join-Path $out $name) -Encoding UTF8
}

# ---------------------------------------------------------------- 0. env
Section "device time"
(Sh "date") | Select-Object -First 1
"epoch=" + ((Sh "date +%s") -join "")

# ---------------------------------------------------------------- 1. pan123
Section "pan123: login_user.xml"
$t123 = Sh "cat /data/data/com.mfcloudcalculate.networkdisk/shared_prefs/login_user.xml"
$t123 | Select-Object -First 12
Save "123-login_user.xml" $t123

Section "pan123: login-related keys in FlutterSharedPreferences.xml"
$fl = Sh "cat /data/data/com.mfcloudcalculate.networkdisk/shared_prefs/FlutterSharedPreferences.xml"
Save "123-flutter.xml" $fl
$fl | Select-String -Pattern "login|Token|token|uuid|UUID" | Select-Object -First 20

Section "pan123: app_flutter dir"
Sh "ls /data/data/com.mfcloudcalculate.networkdisk/app_flutter/"

Section "pan123: any *.sp / databases"
Sh "ls /data/data/com.mfcloudcalculate.networkdisk/databases/ 2>&1"

# ---------------------------------------------------------------- 2. xunlei
Section "xunlei: auth_pref.xml"
$x = Sh "cat /data/data/com.xunlei.downloadprovider/shared_prefs/auth_pref.xml"
Save "xunlei-auth.xml" $x
$xt = $x -join "`n"
if ($xt -match 'access_token&quot;:&quot;([^&]+)') { "access_token  : " + $Matches[1].Substring(0, [Math]::Min(28, $Matches[1].Length)) + "..." }
if ($xt -match 'refresh_token&quot;:&quot;([^&]+)') { "refresh_token : " + $Matches[1].Substring(0, [Math]::Min(24, $Matches[1].Length)) + "..." }
if ($xt -match 'expires_at&quot;:&quot;([^&]+)')   { "expires_at    : " + $Matches[1] }
if ($xt -match 'expires_in&quot;:([0-9]+)')       { "expires_in    : " + $Matches[1] }
if ($xt -match 'user_id&quot;:&quot;([^&]+)')     { "user_id       : " + $Matches[1] }

Section "xunlei: captcha token keys"
$cap = Sh "cat /data/data/com.xunlei.downloadprovider/shared_prefs/xl-acc-captcha-token.xml"
Save "xunlei-captcha.xml" $cap
$capt = $cap -join "`n"
$names = [regex]::Matches($capt, 'name="([^"]*captchatoken)"')
"captchatoken keys: " + $names.Count
$names | Select-Object -First 3 | ForEach-Object {
    $k = $_.Groups[1].Value
    $prefix = $k.Substring(0, $k.Length - 12)
    "  key=$k"
    "    prefix(dropLast12)=$prefix  prefixLen=$($prefix.Length)"
}
$exps = [regex]::Matches($capt, 'name="([^"]*expiresat)" value="([0-9]+)"')
"expiresat entries:"
$exps | ForEach-Object { "  " + $_.Groups[1].Value + " = " + $_.Groups[2].Value }

Section "xunlei: xl-acc-user.xml"
Sh "cat /data/data/com.xunlei.downloadprovider/shared_prefs/xl-acc-user.xml"

# ---------------------------------------------------------------- 3. aliyun
Section "aliyun: pull 0.db"
Sh "rm -rf /data/local/tmp/ali_probe; mkdir -p /data/local/tmp/ali_probe; cp /data/data/com.alicloud.databox/databases/0.db /data/local/tmp/ali_probe/; cp /data/data/com.alicloud.databox/databases/0.db-wal /data/local/tmp/ali_probe/; cp /data/data/com.alicloud.databox/databases/0.db-shm /data/local/tmp/ali_probe/; chmod -R 777 /data/local/tmp/ali_probe; ls -la /data/local/tmp/ali_probe"
& $adb pull /data/local/tmp/ali_probe (Join-Path $out "ali_probe") 2>&1 | Select-Object -Last 3

# ---------------------------------------------------------------- 4. baidu
Section "baidu: pull App WebView Cookies"
Sh "ls -la /data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/"
Sh "rm -rf /data/local/tmp/bd_probe; mkdir -p /data/local/tmp/bd_probe; cp /data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/Cookies /data/local/tmp/bd_probe/; cp /data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/Cookies-journal /data/local/tmp/bd_probe/; cp /data/data/com.baidu.netdisk/app_webview_com.baidu.netdisk/Default/Cookies-wal /data/local/tmp/bd_probe/; chmod -R 777 /data/local/tmp/bd_probe; ls /data/local/tmp/bd_probe"
& $adb pull /data/local/tmp/bd_probe (Join-Path $out "bd_probe") 2>&1 | Select-Object -Last 3

# ---------------------------------------------------------------- 5. quark / uc
Section "quark: real webview profile dirs"
Sh "ls -d /data/data/com.quark.browser/*webview*"

Section "quark: does any cookie db contain pan.quark.cn / __puus / ctoken"
$qk = Sh "for f in /data/data/com.quark.browser/*webview*/Default/Cookies; do echo FILE=$f; cp $f /data/local/tmp/qk.db; strings /data/local/tmp/qk.db | grep -oE 'pan[.]quark[.]cn|__puus|ctoken|quark[.]cn' | sort | uniq -c; done"
$qk

Section "uc: webview profile dirs"
Sh "ls -d /data/data/com.UCMobile/*webview*"

# ---------------------------------------------------------------- 6. panpush own store
Section "panpush: own WebView Cookies (look for httpOnly __puus)"
Sh "rm -rf /data/local/tmp/pp_probe; mkdir -p /data/local/tmp/pp_probe; cp /data/data/com.panpush.app/app_webview/Default/Cookies /data/local/tmp/pp_probe/; cp /data/data/com.panpush.app/app_webview/Default/Cookies-wal /data/local/tmp/pp_probe/; cp /data/data/com.panpush.app/app_webview/Default/Cookies-journal /data/local/tmp/pp_probe/; chmod -R 777 /data/local/tmp/pp_probe; ls -la /data/local/tmp/pp_probe"
& $adb pull /data/local/tmp/pp_probe (Join-Path $out "pp_probe") 2>&1 | Select-Object -Last 3

Section "panpush: credential cache shared_prefs/panpush.xml"
Sh "cat /data/data/com.panpush.app/shared_prefs/panpush.xml"
Sh "ls /data/data/com.panpush.app/shared_prefs/"

Section "panpush: Queries declared in manifest (package visibility)"
Sh "dumpsys package com.panpush.app | sed -n '/Queries:/,/^$/p'"

Write-Output ("`ndone. output dir = " + $out)

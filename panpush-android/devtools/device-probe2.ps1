# Right-size detail probe: only the facts we need.
$ErrorActionPreference = "Continue"
$adb = "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
function Sh([string]$cmd) { & $adb shell ("su -c " + [char]39 + $cmd + [char]39) 2>&1 }
function Sec([string]$t) { Write-Output ("`n--- " + $t) }

Sec "epoch now"
(Sh "date +%s") -join ""

Sec "xunlei auth_pref keys (top-level XML names only)"
$auth = Sh "cat /data/data/com.xunlei.downloadprovider/shared_prefs/auth_pref.xml"
($auth -join "`n") | Select-String -Pattern 'name="[^"]+"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { "  " + $_.Value } | Select-Object -Unique

Sec "xunlei access_token expires_at / expires_in (decoded snippet)"
$at = $auth -join "`n"
if ($at -match 'expires_at&quot;:&quot;([^&]+)') { "expires_at = " + $Matches[1] }
if ($at -match 'expires_in&quot;:([0-9]+)')     { "expires_in = " + $Matches[1] }
if ($at -match 'user_id&quot;:&quot;([^&]+)')   { "user_id    = " + $Matches[1] }

Sec "xunlei captcha prefix math (dropLast 12)"
$cap = (Sh "cat /data/data/com.xunlei.downloadprovider/shared_prefs/xl-acc-captcha-token.xml") -join "`n"
$m = [regex]::Matches($cap, 'name="([^"]*captchatoken)"')
"captchatoken key count = " + $m.Count
$m | Select-Object -First 2 | ForEach-Object {
    $k = $_.Groups[1].Value
    $pre = $k.Substring(0, $k.Length - 12)
    "  key   = $k"
    "  dropLast12 prefix = $pre   (len=" + $pre.Length + ")"
    $re = [regex]::Match($cap, [regex]::Escape("$pre" + "expiresat") + '" value="([0-9]+)"')
    if ($re.Success) { "  paired expiresat  = " + $re.Groups[1].Value } else { "  paired expiresat  = NOT FOUND" }
}

Sec "panpush own webview cookies: look for __puus and pan.quark.cn"
$pp = Sh "cp /data/data/com.panpush.app/app_webview/Default/Cookies /data/local/tmp/pp.db 2>/dev/null; strings /data/local/tmp/pp.db | grep -oE 'pan[.]quark[.]cn|__puus|__pus|ctoken|1[.]123pan|_c_WBKFRo|BDUSS|PANWEB' | sort | uniq -c"

Sec "pan123: app_flutter + databases dirs"
Sh "ls /data/data/com.mfcloudcalculate.networkdisk/app_flutter/ 2>&1"
Sh "ls /data/data/com.mfcloudcalculate.networkdisk/databases/ 2>&1 | head -5"

Sec "pan123: does token look like JWT? (userToken value head/tail)"
$t = ((Sh "cat /data/data/com.mfcloudcalculate.networkdisk/shared_prefs/login_user.xml") -join "`n")
if ($t -match 'name="userToken">Bearer ([^<]+)') {
    $tok = $Matches[1]
    "len      = " + $tok.Length
    "head     = " + $tok.Substring(0, 24)
    "is JWT   = " + ($tok -match '^eyJ')
    "dots     = " + ([regex]::Matches($tok, '[.]')).Count
}

Sec "panpush cached creds keys only"
$sp = (Sh "cat /data/data/com.panpush.app/shared_prefs/panpush.xml") -join "`n"
[regex]::Matches($sp, 'name="(cred_[a-z0-9]+)"') | ForEach-Object { "  " + $_.Groups[1].Value }
"has cred_xunlei  = " + ($sp -match 'cred_xunlei')
"has cred_aliyun  = " + ($sp -match 'cred_aliyun')

Sec "panpush manifest Queries"
Sh "dumpsys package com.panpush.app | sed -n '/Queries:/,/^$/p' | head -30"

Sec "xunlei App data dir (is it readable/disabled?)"
Sh "dumpsys package com.xunlei.downloadprovider | grep -E 'enabled=' | head -2"
Sh "dumpsys package com.alicloud.databox | grep -E 'enabled=' | head -2"

Sec "aliyun 0.db columns (via device python if any, else skip)"
Sh "which sqlite3 python3 2>/dev/null"

Sec "done"

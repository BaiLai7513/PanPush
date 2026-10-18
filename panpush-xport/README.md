# panpush · 迅雷 + 蓝奏 + 阿里云盘 接入交付包

> 目标：给 `panpush` 增加 **迅雷云盘 / 蓝奏云 / 阿里云盘** 的「云解析 + 直推 Gopeed」能力。
> 状态：三站协议均已在真机实测打通（列目录 + 取直链），阿里完整链路（转存→取直链→回收）已端到端跑通。
> 日期：2026-10-11 · 设备：OnePlus PKR110（APatch Root）· Termux Python 3.14

---

## 一、包内文件

```
xunlei.py                          ← 【核心交付物】迅雷解析器（新增，零第三方依赖，root 直读）
lanzou.py                          ← 【核心交付物】蓝奏云解析器（新增，零第三方依赖，全匿名）
aliyun.py                          ← 【核心交付物】阿里云盘解析器（新增，零第三方依赖，root 直读）
panpush-xunlei-lanzou-aliyun.patch ← 【推荐】三站合并补丁（28 处，已验证可干净应用）
panpush-xunlei.patch               ← 仅迅雷的补丁（11 处，如需单独上迅雷用这个）
docs/迅雷接入设计与实现.md          ← 迅雷：理论 + 实现 + 集成改动
docs/阿里云盘接入设计与实现.md      ← 阿里：端点矩阵 / 测速 / 门控 / 集成改动
docs/阿里云盘接入调研.md            ← 阿里：真机实测原始记录（过程性）
docs/协议分析.md                    ← 迅雷协议考古与实测记录（含限速对比）
docs/平台对比与改进.md              ← 与 X网盘助手 平台的对比 + 改进清单
docs/网盘站点评估与集成优先级.md    ← 阿里/城通/蓝奏 免费性与优先级 + 四站可借鉴点
tools/xl_compare.py                ← 迅雷双路径测速脚本（分享链 vs 个人云盘链）
tools/xl_tokstat.py                ← 迅雷 captcha 令牌有效期查看器（排障用）
tools/ali_probe.py                 ← 阿里云盘端点/体积门控探测脚本
tools/genpatch.py / _all.py        ← 补丁生成脚本（可复现，_all 覆盖三站）
README.md                          ← 本文件
```

**三个 `*.py` 均为新增独立文件；`panpush.py` 的改动已做成 patch，一条命令即可应用。**

---

## 二、应用补丁（推荐方式）

先把 `xunlei.py`、`lanzou.py`、`aliyun.py` 放到 `panpush.py` 同目录，然后：

```bash
cd /sdcard/Make/Mk/panpush

# 先干跑（应为 "checking file panpush.py"，无 error）
patch -p1 --dry-run < /sdcard/Make/panpush-xunlei-export/panpush-xunlei-lanzou-aliyun.patch

# 正式应用（迅雷 + 蓝奏 + 阿里 一次到位）
patch -p1 < /sdcard/Make/panpush-xunlei-export/panpush-xunlei-lanzou-aliyun.patch
```

合并补丁共 **28 处**（+48 / −6 行）：

- **迅雷 11 处**：文档标注、import、PARSERS 路由、detect_site、`--site` choices、
  `_load_saved_cookie` 早退、`COOKIE_GUIDE`、`check_cookie` 分支、
  `DEFAULT_CONNECTIONS`、`--connections` 默认按站点、site 解析后套用默认并发；
- **蓝奏 7 处**：文档标注、import、PARSERS 路由、detect_site、`--site` choices、
  `COOKIE_GUIDE`、`_load_saved_cookie` 早退；
- **阿里 10 处**：文档标注、import、PARSERS 路由（占位→AliyunParser）、detect_site、
  `--site` choices、`COOKIE_GUIDE`、`_load_saved_cookie` 早退、
  `DEFAULT_CONNECTIONS`（aliyun=64）、`check_cookie` 分支、`--connections` 帮助文案。

已在真机验证：`patch --dry-run` rc=0、真实 `apply` rc=0、四个 `.py` `py_compile` 通过、
`detect_site` 正确识别三站、`default_connections('aliyun')==64` / `('xunlei')==16` / 其余 `==512`、
`COOKIE_GUIDE.keys()` 含 `aliyun`。

> 若只要迅雷：用 `panpush-xunlei.patch`（11 处）。
> 若目标仓库已用 git：`git apply panpush-xunlei-lanzou-aliyun.patch` 亦可（diff 采用 `a/` `b/` 前缀）。

---

## 三、运行前提

### 迅雷
1. **必须 root**：凭据从 `/data/data/com.xunlei.downloadprovider/shared_prefs/` 直读。
2. **迅雷 APP 已登录**（提供 `access_token` / `captcha` / `device_id`）。
3. captcha 过期会自动刷新（会短暂拉起 APP）；设 `XUNLEI_NO_AUTO_REFRESH=1` 可禁用。

### 蓝奏云
- **全匿名**，无需任何凭据。

### 阿里云盘
1. **必须 root**：凭据从 `/data/data/com.alicloud.databox/databases/0.db`（表 `tb_user_info`）直读，
   token 为**明文**；非 root 环境可用 `.cookie.aliyun` 或 `--token` 手动提供。
2. **阿里云盘 APP 已登录**。
3. token 到期会经 `/v2/account/token` 自动续期（refresh_token 长期有效）。

---

## 四、验证（分级）

```bash
cd /sdcard/Make/Mk/panpush

# 语法
python3 -m py_compile xunlei.py lanzou.py aliyun.py

# --- 迅雷 ---
python3 xunlei.py --creds
python3 xunlei.py 'https://pan.xunlei.com/s/<SHARE_ID>?pwd=<PWD>' --list-only

# --- 蓝奏 ---
python3 lanzou.py 'https://<xxx>.lanzouq.com/<id>' --list-only

# --- 阿里 ---
python3 aliyun.py --creds
python3 aliyun.py 'https://www.aliyundrive.com/s/<SHARE_ID>' --list-only
python3 aliyun.py 'https://www.aliyundrive.com/s/<SHARE_ID>'            # 完整：转存→取直链→回收

# 集成后
python3 panpush.py 'https://www.alipan.com/s/<SHARE_ID>' --connections 64
```

---

## 五、关键结论（实现依据）

| 站点 | 认证方式 | 直链 | 并发建议 | 体积限制 |
|---|---|---|---|---|
| 迅雷 | root 直读 APP 三件套（网页已改白盒签名，不复刻） | 分享链可直接取 | 8~16（分享链 ~6Mbps 封顶） | 无 |
| 蓝奏 | 全匿名（acw_sc__v2 WAF 求解） | 可直接取 | 512 | 无 |
| 阿里 | root 直读 APP 明文 token DB | **需先转存**再取副本直链 | 64（按连接限速，越多越快） | **≥约 100MB 取不到直链** |

### 阿里云盘要点
- 端点：`get_share_token`（匿名）→ `adrive/v3/file/list`（匿名）→ `v2/file/copy`（转存）→
  `v2/file/get_download_url`（登录态）→ `v2/recyclebin/trash`（回收）→ `v2/account/token`（续期）。
- 直链：阿里 OSS，支持 `Range`（206 / `Accept-Ranges: bytes`），**按连接限速**
  （66MB 实测 1/4/16/32/64/128 连接 = 0.12/0.46/1.74/2.10/3.86/4.45 MB/s）。
- **体积门控**：≤66MB 可取，≥127MB 一律空 `url`（HTTP 200 软拒），阈值疑似 **100MB**（免费账号）。
  `aliyun.py` 以 `SIZE_GATE=100MB` 保守跳过并写入 `notes`。会员是否解除**待验证**。
- **取直链频控**：连续请求会返回空，约 20s 恢复；`download_url` 内建 3 次退避重试。
- **回收后直链仍可用**（STS 签名独立），故默认「取到直链→回收副本」。

---

## 六、已知限制

1. 迅雷、阿里依赖已登录的 APP；卸载/未登录/清数据 → 无凭据。
2. 迅雷、阿里需要 root（否则需手动提供凭据）。
3. 迅雷分享链 ~6Mbps 限速、阿里 ≥100MB 门控，均为服务端策略，非代码可解决。
4. 阿里转存会在用户云盘产生副本，默认取到直链后回收；如担心账号风险可用 `--no-clean` 评估。
5. 版本号会随 APP 更新漂移，相关值已尽量集中为常量便于维护。

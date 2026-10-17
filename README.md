# 桌宠 · Android

把电脑端插件 [dsh-pet](https://github.com/PC2005-cloud/dsh-pet) 移植成**安卓悬浮桌宠**：
宠物住在桌面上（不是住在某个 App 里），完全离线。

- ✅ 全量 **106 段**透明动画（待机 / 转向 / 拖拽 / 5 种点击回应 / 3 种移动 / 5 大类随机动作）
- ✅ 点击 Q 弹回应、拖拽过阻尼弹簧跟手、甩抛抛物线 + 撞边反弹 + 地面摩擦
- ✅ 屏幕横向漫游，走不出去会自动掉头
- ✅ **长按动作菜单**：像电脑版的右键菜单一样，点播任意一段动画
- ✅ 大小可调（80–360dp）、多开（配置文件）、开机自启
- ❌ 没有碎碎念 / 对话 / 余额 / 系统通知（这些需要 DSH 的模型与账号服务）
- ✅ **零网络权限**：没有 AI 就没有联网，不消耗 token

## 下载安装

到 [Releases](../../releases) 下载最新的 `桌宠-vX.Y-debug.apk`，传到手机安装（需允许「安装未知来源应用」）。

安装后：

1. 打开 App → **授予「显示在其他应用上层」**（悬浮窗权限，必需）
2. 授予通知权限（前台服务常驻用）
3. 点**「放出来」**

### 厂商后台保活（否则锁屏久了会被系统清掉）

- **荣耀 / MagicOS**：设置 → 应用 → 桌宠 → 电池 → 允许后台活动；并加入「应用启动管理」允许列表
- **小米 / 红米**：省电策略选「无限制」+ 打开「自启动」

## 交互

| 操作 | 效果 |
|---|---|
| 单击 | 随机点击回应动画 + Q 弹挤压 |
| **长按** | 弹出动作菜单（分类可折叠，点播任意动画 / 回到初始位置 / 翻转朝向 / 收起来） |
| 按住拖动 | 弹簧跟手 |
| 快速甩出 | 抛物线飞出，撞边反弹，落地摩擦减速并 Q 弹 |
| 通知栏 | 「收起来」= 停止 |

## 手感为什么和电脑版一致

物理是逐行移植，不是重写：

| 电脑版（dsh-pet） | 本项目 |
|---|---|
| `src/shared/physics.ts` | `app/.../Physics.kt`（常量与公式一字未改） |
| `src/shared/constants.ts` | `Pet.kt` 里的命中框 / 16:9 比例 / 基准宽 462 |
| `src/shared/motion.ts` | `Pet.kt` 的 `anchorPixel()` 与漫游边界 |
| `assets/config.jsonc` | `tools/gen-config.mjs` 生成的 `pet-config.json` |

弹簧 `K=200 / C=30`、重力 `1400`、恢复系数 `0.78`、地面摩擦 `2.5`、死区 `500px/s`、
软钳速上限 `3600px/s`、Q 弹 `220ms` / 压深 `0.55` —— 全部与电脑版同值。

## 素材为什么是 WebP

电脑版用 VP9-alpha 的 `.webm`，安卓原生播不了透明视频（`MediaCodec` 不暴露 alpha 平面）。
所以转成**动画 WebP**（Alpha 保留），由 Android 9+ 的 `AnimatedImageDrawable` 原生播放：

```bash
ffmpeg -c:v libvpx-vp9 -i in.webm \
  -vf "fps=15,scale=480:-2:flags=lanczos" \
  -c:v libwebp_anim -pix_fmt yuva420p -q:v 50 -loop 0 \
  out.webp
```

关键点：**必须用 `-c:v libvpx-vp9` 解码**，默认解码器会把 alpha 丢成 `rgb24`。

分辨率选 **480px** 是因为 150dp × density 3.275 ≈ 475px，正好 1:1；
用 300px 时会被放大 1.58 倍，看起来发虚。动画 WebP 体积随分辨率涨得很快
（640px 全量约 530 MB，480px 约 156 MB）。

## 从源码构建

**仓库不包含动画素材**（它们来自上游且有「禁止商用」约束，见 [NOTICE.md](NOTICE.md)），
首次构建前先还原一次：

```bash
# 1) 还原素材 + 生成配置（需要 node/npm 与 ffmpeg）
./tools/fetch-and-transcode.sh          # Linux / macOS
pwsh -File tools/fetch-and-transcode.ps1   # Windows PowerShell

# 2) 构建 debug APK（需要 JDK 17 + Android SDK 34）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

转码参数可调：`WIDTH=640 QUALITY=60 FPS=24 ./tools/fetch-and-transcode.sh`
（体积会明显变大，见上节）。

校验素材是否正确（帧数 / 尺寸 / 透明）：

```bash
python tools/verify_webp.py
```

CI：把 `ci/build.yml.example` 复制成 `.github/workflows/build.yml` 即可启用
（push 与 PR 自动还原素材并构建 APK）。GitHub 要求推送 workflow 文件时 token 带
`workflow` 权限，所以它没有随首次提交一起推上去，需要手动加一次。

## 代码结构

```
app/src/main/java/com/dshpet/android/
├─ MainActivity.kt   设置/授权界面
├─ PetService.kt     前台服务：悬浮窗管理 + 帧循环 + 长按动作菜单
├─ Pet.kt            单只宠物：动画链 + 触摸 + 物理 + 漫游 + 长按
├─ Physics.kt        dsh-pet physics.ts 的逐行移植
├─ PetConfig.kt      pet-config.json 解析
├─ Prefs.kt          尺寸 / 开关
├─ Diag.kt           自诊断（异常落盘 + 界面显示，避免无声闪退）
├─ App.kt            未捕获异常兜底
└─ BootReceiver.kt   开机恢复
tools/
├─ build-assets.mjs          素材转码（跨平台统一排序）
├─ gen-config.mjs            插件配置 → 手机配置（含中文动画名）
├─ fetch-and-transcode.*     一键还原素材
└─ verify_webp.py            素材校验
```

## 已知限制 / 可以贡献的方向

- **悬浮窗是矩形**：宠物盒子里那圈透明区域也会挡住点击（电脑版是逐像素命中）。
  可以按 alpha 做命中判定，或把窗口裁到身体范围。
- **多开没有 UI**：需要在 `pet-config.json` 的 `pets` 数组里加条目。
- **没有右键菜单**（用长按替代），动作菜单的分类目前是折叠列表，可以做成二级页面。
- **画质与体积**：想做「原生 640×360 + 24fps 且体积小」，需要改用
  「彩色流 + 透明遮罩流」双视频 + GL 合成（预计全量 ~80 MB，比现在的 WebP 更小更清晰）。

## 许可与署名

- **代码**：MIT（见 [LICENSE](LICENSE)）
- **动画素材**：来自 [PC2005-cloud/dsh-pet](https://github.com/PC2005-cloud/dsh-pet)，
  其声明为「允许开源使用，**禁止商用**」——因此本仓库不含素材原件，Release 附件同样受此约束。
  详见 [NOTICE.md](NOTICE.md)。
- 本项目是社区移植，与 DeepSeek 官方无关。

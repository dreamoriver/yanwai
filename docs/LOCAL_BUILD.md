# 本机构建、安装与发版

这份只给在本机改代码的人（和 agent）看：怎么打包、怎么装到手机、怎么确认模块真的换了、怎么发版。功能说明见 `docs/` 下其它文件。

## 环境

| 项目 | 值 |
|---|---|
| 源码 | `/workspace/yanwai-src`，分支 `build/v2.7.0`（2.9.x 线） |
| JDK | `/usr/lib/jvm/java-21-openjdk-arm64` |
| Android SDK | `/workspace/android-sdk` |
| Gradle | `/opt/gradle/gradle-8.9/bin/gradle`（仓库没有 wrapper） |
| 签名 keystore | `/workspace/yanwai-signing.jks`，别名 `yanwai` |
| 密码文件 | `/workspace/yanwai-pass.txt` |

签名固定，证书 SHA-256 前六位 `119dc4`，与 GitHub release 上的包一致，所以本地包可以直接覆盖安装。

## 本机构建（不走 CI）

```sh
cd /workspace/yanwai-src
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64
export ANDROID_HOME=/workspace/android-sdk
export YANWAI_KEYSTORE=/workspace/yanwai-signing.jks
export YANWAI_KEYSTORE_PASSWORD="$(cat /workspace/yanwai-pass.txt)"
export YANWAI_KEY_PASSWORD="$(cat /workspace/yanwai-pass.txt)"
export YANWAI_KEY_ALIAS=yanwai

/opt/gradle/gradle-8.9/bin/gradle :app:assembleDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process \
  -Pandroid.aapt2FromMavenOverride=/workspace/android-sdk/bin/aapt2 \
  --offline
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，约 40 秒。

两个必须记住的坑：

1. **aapt2 要覆盖**。本机是 arm64，Gradle 自动下载的 aapt2 是 x86_64 的，会报 `Daemon startup failed`。用 `-Pandroid.aapt2FromMavenOverride=/workspace/android-sdk/bin/aapt2`（那是个 `qemu-x86_64` 包装脚本）。
2. **两个密码都要传**。只传 `YANWAI_KEYSTORE_PASSWORD` 会报 `KeytoolException: Given final block not properly padded`，`YANWAI_KEY_PASSWORD` 必须一起给。

## 安装与生效

```sh
cp app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/yanwai.apk
# /sdcard 是 fuse，SystemServer 读不到，必须放 /data/local/tmp
pm install -r /data/local/tmp/yanwai.apk
```

装完**必须重启微信进程**，模块只在微信进程启动时加载：

```sh
am force-stop com.tencent.mm
monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1
am start -n dev.jev.wechatmood/.MainActivity      # 顺便把言外拉起来，设置服务要在线
```

确认新代码生效：`logcat` 里 tag `WeChatMood` 应该有启动日志（例如 `REPLY_HISTORY_HOOKS`、`MSG_FIELDS`）。只开言外不重启微信，跑的还是旧代码。

## 发版

1. 更新 `version.properties` 与 `CHANGELOG.md`，CHANGELOG 必须有 `## <versionName>` 段落，否则 CI 直接失败。
2. 推 `dreamoriver/yanwai-build` 的 main，触发 `Build APK`（约 6 分钟）。

   ```sh
   git push private HEAD:main
   ```

3. 等 60 秒查一次 release：

   ```sh
   curl -s -H "Authorization: token $GH_TOKEN" \
     "https://api.github.com/repos/dreamoriver/yanwai-build/releases?per_page=1"
   ```

4. **验签**。CI 如果用错 workflow 会退回 debug 签名，装上去就没法覆盖：

   ```sh
   curl -sSL -C - -o /tmp/rel.apk <browser_download_url>
   /workspace/android-sdk/build-tools/35.0.0/apksigner verify --print-certs /tmp/rel.apk | head -3
   ```

   证书 SHA-256 必须是 `119dc4b9cc…`。

CI 用的是**分支自己的** `.github/workflows/build.yml`，改发版流程时确认改动已经在这个分支上。

## 上游 PR

- fork：`dreamoriver/yanwai`，上游：`YIRC99/yanwai`
- PR 从 fork 的 `feat/xxx` 分支提到上游 main，目前 OPEN 的两个：
  - `#10` 群聊统计与 AI 观察、口吻画像、关系档案（`feat/style-profile`）
  - `#11` 合并转发摘要（`feat/forward-digest`）
- `#10` 里已经带了 `PanelKit` 与界面分层规范，`#11` 的摘要窗口还等它合并后再换样式
- 改动攒一批再发，别一个提交一个 release

## 提交前自检

```sh
git config core.hooksPath .githooks   # 只配一次
sh tools/check-ui-style.sh            # 界面违规；pre-commit 与 CI 也会跑
```

`pre-commit` 会拦「没更新 version.properties」和「自绘窗口里用宿主 AlertDialog / 写临时色值」。界面规则见 `DESIGN.md` 的「界面分层」。

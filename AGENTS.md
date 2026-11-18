# Local development rules

- Work in the current checkout. Do not use worktrees. Preserve unrelated changes.
- Every local commit must advance both `versionName` and `versionCode` in `version.properties`, including follow-up fixes. Use `powershell -File tools/bump-version.ps1` for a patch, or `-Part minor` for a feature batch.
- `version.properties` is the single version source. The Gradle package, app header next to 言外, analysis cards and diagnostics must use generated `BuildConfig` values, never independently hard-coded version strings.
- Stage the version update with its corresponding changes. Enable the tracked guard with `git config core.hooksPath .githooks`; it checks the staged version against HEAD. Do not bypass the guard to commit an unchanged version.
- Validate code changes with the relevant offline tests and a Debug build. Use `--no-daemon -Pkotlin.compiler.execution.strategy=in-process`; leave device UI testing to the user unless explicitly requested.
- For a long implementation task, make a scoped local commit before finishing. Do not push or publish unless asked. Check that no project server or compilation process was left running.
- 动界面之前先读 `DESIGN.md` 的「界面分层」与「微信内面板零件」两节：微信内弹出的窗口一律用 `PanelKit` + `ReplyTheme`（圆角/触控/字号取 `ReplyMetrics`），贴宿主的入口（菜单项、设置行、横条）不自绘样式，独立 App 走 XML 与 `Yanwai.*`；改完跑 `sh tools/check-ui-style.sh`。

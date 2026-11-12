# 言外自用构建 · 更新记录

基于 [YIRC99/yanwai](https://github.com/YIRC99/yanwai) 的本地改动，仅供自己使用。
原仓库未声明开源许可证，因此不对外分发安装包。

## 2.7.5

设置页
- 「检测」改成先测后存，检测失败不再覆盖原来能用的 Key
- 检测通过结果写进 prefs，重开 App 不再退回「待检测」
- 旋转/分屏时把未保存的 endpoint、Key、模型草稿带过去
- 回复配置保存后回显最终请求地址

素材
- 新增「回复口吻与自然感」参考片段（整理自 goutoujunshi-jev-chat，MIT）

## 2.7.4

（构建失败，未发布）

## 2.7.3

- max_tokens 600 → 1500，修掉 3–5 个候选时的 `finish_reason=length` 截断
- finish_reason 不再强制 stop，截断时给出明确文案
- 解析前从第一个 `{` 开始取，容忍模型多说一句
- 填入聊天框后自动收起抽屉

## 2.7.2

体验
- 去掉「言外已加载」Toast，初始化失败只提醒一次
- 「复制这条」恢复卡片底色

隐私与网络
- 公网地址强制 HTTPS（本机/局域网放行）
- 关闭 OkHttp 静默重发，避免按次计费被重复扣费
- 三个 HTTP client 共享连接池
- 分析缓存 14 天过期
- 主界面 FLAG_SECURE

清理
- 删除未被引用的 AdapterLocator
- 气泡绘制失败日志去重

## 2.7.1

- 收起状态改用 (talker, messageId, speaker) 作键，修复收起后长按不恢复
- 回复抽屉底部改为左「复制这条」右「填入聊天框」
- 回复建议改为 3–5 个互斥候选，按推荐度排序

## 2.7.0

冷启动
- 新增 SettingsSnapshotCache：微信进程用 Android Keystore 加密保存上次配置
- ModulePrefs.init 先用快照 accept，再异步刷新
- 读取失败按 2s/5s/15s/60s 退避重试
- 未连接时卡片显示「设置未同步 · 点此重试」

交互
- 手动分析的卡片点一下即收起，重新长按恢复
- 回复抽屉新增「填入聊天框」，原草稿存剪贴板

token
- 知识库改为本地二元组检索，29–47K 字符压到 4.6K
- 草稿与上次建议上限 8000 → 2000 字符
- evidence 增加 my_style 口吻样本
- 输出加 max_tokens 与 temperature

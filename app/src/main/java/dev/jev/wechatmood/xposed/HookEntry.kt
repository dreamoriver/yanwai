package dev.jev.wechatmood.xposed

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.jev.wechatmood.BuildConfig
import dev.jev.wechatmood.core.ModulePrefs
import dev.jev.wechatmood.core.MoodLog
import dev.jev.wechatmood.core.Diagnostics
import dev.jev.wechatmood.hook.MessageSniffer
import dev.jev.wechatmood.hook.ReplyDatabaseHistory

/** Direct package entry, independent of the optional initZygote callback. */
class HookEntry : IXposedHookLoadPackage {
    private var installed = false
    private var failedAnnounced = false

    private companion object {
        /** 主线程迟迟不空闲时，最多等这么久就先装，保证功能不丢。 */
        const val IDLE_FALLBACK_MS = 6000L
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName != "com.tencent.mm" || param.processName != "com.tencent.mm") return
        MoodLog.frameworkSink = { XposedBridge.log(it) }
        XposedBridge.log("WeChatMood ${BuildConfig.VERSION_NAME}: entered WeChat main process")
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    // 首帧之前不抢主线程：WeKit 这类重模块同时挂微信时，两边的初始化叠加会把
                    // 冷启动推过系统 5 秒阈值，微信被判「surface 未绘制」直接黑屏。
                    scheduleInitialize(p.args[0] as Context)
                }
            })
        // Covers module loaders that attach after Application.attach has already run.
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnResume", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val activity = p.args.firstOrNull() as? Activity ?: return
                if (activity.packageName != "com.tencent.mm") return
                initialize(activity.application)
                if (!installed) {
                    // 每次切页都弹会烦人，失败只提示一次，细节进日志。
                    if (!failedAnnounced) {
                        failedAnnounced = true
                        Toast.makeText(activity, "情绪助手初始化失败，请查看模块日志", Toast.LENGTH_LONG).show()
                    }
                    return
                }
                runCatching { MessageSniffer.resume(activity) }
                    .onFailure { MoodLog.e("HOST_RESUME_FAILED", it) }
            }
        })
        XposedBridge.hookAllMethods(Instrumentation::class.java, "callActivityOnPause", object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                (p.args.firstOrNull() as? Activity)?.let { MessageSniffer.pause(it) }
            }
        })
    }

    /**
     * 等主线程空闲（首帧画完）再装 hook。
     *
     * 和 WeKit 这类同样在启动路径上装大量 hook 的模块共存时，双方初始化时间相加会超过
     * 系统的「surface 未绘制」阈值，微信冷启动直接黑屏。让出首帧窗口就能同时保留两边功能；
     * 6 秒兜底避免极端情况下永远不装。
     */
    private fun scheduleInitialize(context: Context) {
        val main = Looper.getMainLooper()
        val task = Runnable {
            MoodLog.i("HOOK_INIT_IDLE 主线程空闲，开始安装")
            initialize(context)
        }
        if (Looper.myLooper() == main) {
            main.queue.addIdleHandler {
                task.run()
                false
            }
        } else {
            Handler(main).post { scheduleInitialize(context) }
        }
        Handler(main).postDelayed({ if (!installed) task.run() }, IDLE_FALLBACK_MS)
    }

    @Synchronized private fun initialize(context: Context) {
        if (installed) return
        runCatching {
            MoodLog.init(context)
            ModulePrefs.init(context)
            MessageSniffer.install(context)
            installed = true
            MoodLog.i("微信主进程已加载模块 ${BuildConfig.VERSION_NAME}")
            // 环境采集、日志上报、WCDB 反射扫描都不参与首帧，挪出冷启动主线程。
            Thread({
                runCatching {
                    MoodLog.i("ENVIRONMENT\n${Diagnostics.environment(context)}")
                    ModulePrefs.report("模块 ${BuildConfig.VERSION_NAME} 已加载，等待打开聊天")
                    ReplyDatabaseHistory.install(context.classLoader)
                }.onFailure { MoodLog.e("HOOK_INIT_BACKGROUND_FAILED", it) }
            }, "yanwai-init-bg").start()
        }.onFailure {
            XposedBridge.log("WeChatMood initialization failed: ${it.javaClass.name}")
            MoodLog.e("HOOK_INIT_FAILED", it)
        }
    }
}

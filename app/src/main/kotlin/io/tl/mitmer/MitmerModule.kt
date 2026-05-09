package io.tl.mitmer

import android.os.Environment
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MitmerModule : XposedModule() {

    companion object {
        private const val TAG = "Mitmer"
    }

    private var classLoaderHookHandle: XposedInterface.HookHandle? = null
    private val okioHooked = AtomicBoolean(false)

    override fun onPackageReady(param: PackageReadyParam) {

        val cl = param.classLoader
        hookLoadClass(cl)
    }

    private fun hookLoadClass(cl: ClassLoader) {
        try {
            val loadClassMethod = ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java)
            classLoaderHookHandle = hook(loadClassMethod).intercept { chain ->
                val className = chain.getArg(0) as String
                if (className == "okio.RealBufferedSource" && okioHooked.compareAndSet(false, true)) {
                    val result = chain.proceed()
                    if (result is Class<*>) {
                        Log.i(TAG, "RealBufferedSource 已加载，开始安装 Okio 钩子")
                        installOkioHooks(result.classLoader)
                        classLoaderHookHandle?.unhook()
                        classLoaderHookHandle = null
                    }
                    return@intercept result
                }
                chain.proceed()
            }
        } catch (e: NoSuchMethodException) {
            log(Log.ERROR, TAG, "找不到 loadClass 方法", e)
        }
    }

    private fun installOkioHooks(cl: ClassLoader) {
        val rbsClass = try {
            cl.loadClass("okio.RealBufferedSource")
        } catch (e: ClassNotFoundException) {
            log(Log.ERROR, TAG, "无法加载 RealBufferedSource 类", e)
            return
        }

        // 1. Hook readUtf8() 方法
        try {
            val readUtf8 = rbsClass.getDeclaredMethod("readUtf8")
            readUtf8.isAccessible = true
            hook(readUtf8).intercept { chain ->
                val result = chain.proceed()
                if (result is String && isLikelyJson(result)) {
                    saveResponseAsync(result)
                }
                result
            }
            log(Log.INFO, TAG, "已 Hook readUtf8()")
        } catch (e: Exception) {
            log(Log.WARN, TAG, "Hook readUtf8 失败", e)
        }

        // 2. Hook readString(Charset) 方法
        try {
            val readString = rbsClass.getDeclaredMethod("readString", Charset::class.java)
            readString.isAccessible = true
            hook(readString).intercept { chain ->
                val result = chain.proceed()
                if (result is String && isLikelyJson(result)) {
                    saveResponseAsync(result)
                }
                result
            }
            log(Log.INFO, TAG, "已 Hook readString(Charset)")
        } catch (e: Exception) {
            log(Log.WARN, TAG, "Hook readString 失败", e)
        }

        log(Log.INFO, TAG, "Okio 钩子安装完成，所有 JSON 响应将被保存到 Documents 目录")
    }

    private fun isLikelyJson(str: String): Boolean {
        val trimmed = str.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private fun saveResponseAsync(jsonContent: String) {
        thread {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                if (!docsDir.exists()) docsDir.mkdirs()
                val fileName = "fgo_response_${System.currentTimeMillis()}.json"
                val file = File(docsDir, fileName)
                file.writeText(jsonContent)
                // file.setReadable(true, false)  // 方便 adb 查看
                log(Log.INFO, TAG, "响应已保存 → ${file.absolutePath}")
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "保存 JSON 失败", e)
            }
        }
    }
}

package io.tl.mitmer

import android.os.Environment
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.concurrent.thread

class MitmerModule : XposedModule() {

    companion object {
        private const val TAG = "Mitmer"
        private const val TARGET_PACKAGE = "com.example.targetapp" // 替换为实际目标包名
        private val URL_PATTERN = Pattern.compile("https://game\\.fate-go\\.jp/login/top\\?_userId=(\\d+)")
    }

    private val okhttpHooked = AtomicBoolean(false)
    private var classLoaderHookHandle: XposedInterface.HookHandle? = null

    // 模块入口：当目标包加载完成时调用
    override fun onPackageReady(param: PackageReadyParam) {
        // 仅处理目标应用进程（根据作用域，本方法仅在目标包内被调用，此处可做二次确认）
        if (param.packageName != TARGET_PACKAGE) return

        val cl = param.classLoader
        // 优先尝试注入 OkHttp Interceptor
        tryInjectOkHttpInterceptor(cl)

        // 如果 OkHttp 注入失败或不存在，回退到 hook Okio 读方法（不按 URL 过滤）
        if (!okhttpHooked.get()) {
            fallbackHookOkio(cl)
        }
    }

    // 方案一：通过 OkHttp Interceptor 拦截响应（推荐，可获取完整请求 URL）
    private fun tryInjectOkHttpInterceptor(cl: ClassLoader) {
        try {
            // 1. 定位 OkHttpClient.Builder 类
            val builderClass = cl.loadClass("okhttp3.OkHttpClient\$Builder")
            val buildMethod = builderClass.getDeclaredMethod("build")

            // 2. Hook build 方法，在构建时添加自定义 Interceptor
            hook(buildMethod).intercept { chain ->
                val builder = chain.thisObject
                // 调用原 build 方法前，先添加 Interceptor
                val addInterceptorMethod = builderClass.getMethod("addInterceptor", Interceptor::class.java)
                addInterceptorMethod.invoke(builder, LoginInterceptor())
                // 继续执行原 build 逻辑
                chain.proceed()
            }
            okhttpHooked.set(true)
            log(Log.INFO, TAG, "OkHttp Interceptor 注入成功")
        } catch (e: Throwable) {
            log(Log.WARN, TAG, "OkHttp 不可用或注入失败，将使用 Okio 回退方案", e)
        }
    }

    // 自定义 Interceptor：匹配 URL、提取 userId、异步保存响应 JSON
    inner class LoginInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url().toString()
            val matcher = URL_PATTERN.matcher(url)

            if (matcher.matches()) {
                val userId = matcher.group(1)
                log(Log.INFO, TAG, "捕获目标请求，userId=$userId")

                val response = chain.proceed(request)
                // 使用 peekBody 克隆响应体，不影响原始流
                val body = response.body
                if (body != null) {
                    val content = body.peekBody(Long.MAX_VALUE).string()
                    // 异步保存 JSON
                    saveResponseAsync(userId, content)
                }
                return response
            }
            return chain.proceed(request)
        }
    }

    // 异步保存 JSON 到 Documents/fgo_login_top_${userId}_${timestamp}.json
    private fun saveResponseAsync(userId: String, jsonContent: String) {
        thread {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                if (!docsDir.exists()) docsDir.mkdirs()
                val fileName = "fgo_login_top_${userId}_${System.currentTimeMillis()}.json"
                val file = File(docsDir, fileName)
                file.writeText(jsonContent)
                file.setReadable(true, false) // 方便调试
                log(Log.INFO, TAG, "响应已保存 → ${file.absolutePath}")
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "保存 JSON 失败", e)
            }
        }
    }

    // 方案二：回退方案 – Hook Okio 的 RealBufferedSource 读方法（不区分 URL，仅当 OkHttp 不可用时生效）
    private fun fallbackHookOkio(cl: ClassLoader) {
        // 通过监控 loadClass 等待 RealBufferedSource 载入
        classLoaderHookHandle = hook(ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java))
            .intercept { chain ->
                val className = chain.arg(0) as String
                if (className == "okio.RealBufferedSource" && okhttpHooked.compareAndSet(false, true)) {
                    val result = chain.proceed()
                    if (result is Class<*>) {
                        log(Log.INFO, TAG, "RealBufferedSource 已加载，开始安装 Okio hooks")
                        installOkioHooks(result.classLoader)
                        classLoaderHookHandle?.unhook()
                        classLoaderHookHandle = null
                    }
                    return@intercept result
                }
                chain.proceed()
            }
    }

    private fun installOkioHooks(cl: ClassLoader) {
        val rbsClass = cl.loadClass("okio.RealBufferedSource")
        // Hook readUtf8()
        try {
            val readUtf8 = rbsClass.getDeclaredMethod("readUtf8")
            readUtf8.isAccessible = true
            hook(readUtf8).intercept { chain ->
                val result = chain.proceed()
                if (result is String && isLikelyJson(result)) {
                    // 回退方案无法获取 userId，文件名中不包含 userId 信息
                    saveGenericResponse(result)
                }
                result
            }
        } catch (e: Exception) {
            log(Log.WARN, TAG, "readUtf8 hook 失败", e)
        }
        // Hook readString(Charset)
        try {
            val readString = rbsClass.getDeclaredMethod("readString", Charset::class.java)
            readString.isAccessible = true
            hook(readString).intercept { chain ->
                val result = chain.proceed()
                if (result is String && isLikelyJson(result)) {
                    saveGenericResponse(result)
                }
                result
            }
        } catch (e: Exception) {
            log(Log.WARN, TAG, "readString hook 失败", e)
        }
        log(Log.INFO, TAG, "Okio 回退钩子安装完成（所有 JSON 响应将被保存）")
    }

    private fun isLikelyJson(str: String): Boolean {
        val trimmed = str.trim()
        return trimmed.startsWith("{") && trimmed.endsWith("}")
    }

    private fun saveGenericResponse(content: String) {
        thread {
            try {
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                docsDir.mkdirs()
                val file = File(docsDir, "fgo_response_${System.currentTimeMillis()}.json")
                file.writeText(content)
                file.setReadable(true, false)
                log(Log.INFO, TAG, "回退模式保存 → ${file.absolutePath}")
            } catch (e: Exception) {
                log(Log.ERROR, TAG, "回退模式保存失败", e)
            }
        }
    }
}
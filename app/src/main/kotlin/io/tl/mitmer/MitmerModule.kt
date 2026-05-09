package io.tl.mitmer

import android.app.Application
import android.content.Context
import android.os.Environment
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class MitmerModule : XposedModule() {

    companion object {
        private const val TAG = "Mitmer"
        private val URL_REGEX = Regex("https://game\\.fate-go\\.jp/login/top.*_userId=(\\d+)")
    }

    private val hookLock = AtomicBoolean(false)
    private var loadClassHookHandle: XposedInterface.HookHandle? = null

    private var chainRequestMethod: Method? = null
    private var requestUrlMethod: Method? = null
    private var chainProceedMethod: Method? = null
    private var peekBodyMethod: Method? = null
    private var bodyStringMethod: Method? = null
    private var httpUrlToStringMethod: Method? = null

    override fun onPackageReady(param: PackageReadyParam) {
        val cl = param.classLoader
        hookAttachBaseContext(cl)
        hookLoadClass(cl)
    }

    private fun hookAttachBaseContext(cl: ClassLoader) {
        try {
            val attach = cl.loadClass("android.app.Application")
                .getDeclaredMethod("attachBaseContext", Context::class.java)
            attach.isAccessible = true
            hook(attach).intercept { chain ->
                chain.proceed()
                log(Log.DEBUG, TAG, "attachBaseContext: classLoader=${(chain.getArg(0) as Context).classLoader}")
            }
        } catch (_: Throwable) { }
    }

    private fun hookLoadClass(cl: ClassLoader) {
        try {
            val loadClass = ClassLoader::class.java
                .getDeclaredMethod("loadClass", String::class.java)

            loadClassHookHandle = hook(loadClass).intercept { chain ->
                val name = chain.getArg(0) as String
                if (name == "okhttp3.OkHttpClient\$Builder" && hookLock.compareAndSet(false, true)) {
                    val result = chain.proceed()
                    if (result is Class<*> && result.name == "okhttp3.OkHttpClient\$Builder") {
                        log(Log.INFO, TAG, "OkHttp class loaded via ${chain.thisObject}")
                        try {
                            doHookOkHttp(result.classLoader)
                        } catch (t: Throwable) {
                            log(Log.ERROR, TAG, "Hook injection failed", t)
                            hookLock.set(false)
                        }
                    }
                    return@intercept result
                }
                chain.proceed()
            }
        } catch (_: NoSuchMethodException) {
            log(Log.ERROR, TAG, "Cannot find loadClass method")
        }
    }

    private fun doHookOkHttp(okHttpCl: ClassLoader) {
        val builderClass = okHttpCl.loadClass("okhttp3.OkHttpClient\$Builder")
        val interceptorInterface = okHttpCl.loadClass("okhttp3.Interceptor")
        val chainInterface = okHttpCl.loadClass("okhttp3.Interceptor\$Chain")
        val requestClass = okHttpCl.loadClass("okhttp3.Request")
        val responseClass = okHttpCl.loadClass("okhttp3.Response")
        val responseBodyClass = okHttpCl.loadClass("okhttp3.ResponseBody")

        val builderBuildMethod = builderClass.getDeclaredMethod("build")
        chainRequestMethod = chainInterface.getMethod("request")
        requestUrlMethod = requestClass.getMethod("url")
        chainProceedMethod = chainInterface.getMethod("proceed", requestClass)
        peekBodyMethod = responseClass.getMethod("peekBody", Long::class.javaPrimitiveType)
        bodyStringMethod = responseBodyClass.getMethod("string")
        httpUrlToStringMethod = try {
            okHttpCl.loadClass("okhttp3.HttpUrl").getMethod("toString")
        } catch (_: Exception) {
            okHttpCl.loadClass("java.net.URL").getMethod("toString")
        }

        val addInterceptor = try {
            builderClass.getDeclaredMethod("addNetworkInterceptor", interceptorInterface)
        } catch (_: NoSuchMethodException) {
            builderClass.getDeclaredMethod("addInterceptor", interceptorInterface)
        }

        hook(builderBuildMethod).intercept { chain ->
            val builder = chain.thisObject
            val proxy = Proxy.newProxyInstance(
                okHttpCl, arrayOf(interceptorInterface)
            ) { proxy, method, args ->
                when (method.name) {
                    "intercept" -> if (args != null) {
                        handleIntercept(args[0])
                    } else null
                    "toString" -> "MitmerInterceptor"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> args != null && args.size == 1 && args[0] === proxy
                    else -> null
                }
            }
            addInterceptor.invoke(builder, proxy)
            chain.proceed()
        }

        loadClassHookHandle?.unhook()
        loadClassHookHandle = null
        log(Log.INFO, TAG, "OkHttp Builder.build() hooked")
    }

    private fun handleIntercept(chain: Any): Any {
        val request = chainRequestMethod!!.invoke(chain)
        val requestUrlObj = requestUrlMethod!!.invoke(request)
        val requestUrl = httpUrlToStringMethod!!.invoke(requestUrlObj) as String
        val response = chainProceedMethod!!.invoke(chain, request)
        val responseRequest = chainRequestMethod!!.invoke(response)
        val responseUrlObj = requestUrlMethod!!.invoke(responseRequest)
        val responseUrl = httpUrlToStringMethod!!.invoke(responseUrlObj) as String

        val match = URL_REGEX.find(responseUrl) ?: URL_REGEX.find(requestUrl)
        if (match != null) {
            val userId = match.groupValues[1]
            val body = peekBodyMethod!!.invoke(response, Long.MAX_VALUE)
            val bodyStr = bodyStringMethod!!.invoke(body) as String

            if (bodyStr.startsWith("{") && bodyStr.endsWith("}")) {
                val id = if (userId.isBlank()) "unknown_user" else userId
                thread {
                    try {
                        val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS
                        )
                        dir.mkdirs()
                        val file = File(dir, "fgo_top_${id}_${System.currentTimeMillis()}.json")
                        file.writeText(bodyStr)
                        file.setReadable(true, false)
                        log(Log.INFO, TAG, "Saved: ${file.absolutePath}")
                    } catch (e: Exception) {
                        log(Log.ERROR, TAG, "Failed to save response", e)
                    }
                }
            }
        }
        return response
    }
}

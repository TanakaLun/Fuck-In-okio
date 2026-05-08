package io.tl.mitmer

import android.os.Environment
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Proxy
import kotlin.concurrent.thread

class MitmerModule : XposedModule() {

    companion object {
        private const val TAG = "Mitmer"
        private val URL_REGEX = Regex("https://game\\.fate-go\\.jp/login/top.*_userId=(\\d+)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        val cl = param.classLoader
        try {
            hookBuilderBuild(cl)
            log(Log.INFO, TAG, "Builder.build() hooked")
        } catch (t: Throwable) {
            log(Log.ERROR, TAG, "Failed to hook Builder.build()", t)
        }
    }

    private fun hookBuilderBuild(cl: ClassLoader) {
        val builderClass = cl.loadClass("okhttp3.OkHttpClient\$Builder")
        val buildMethod = builderClass.getDeclaredMethod("build")
        val interceptorInterface = cl.loadClass("okhttp3.Interceptor")

        hook(buildMethod).intercept { chain ->
            val builder = chain.thisObject
            val proxy = Proxy.newProxyInstance(
                cl, arrayOf(interceptorInterface)
            ) { _, method, args ->
                if (method.name == "intercept" && args != null) {
                    handleIntercept(args[0])
                } else null
            }
            builderClass.getDeclaredMethod("addInterceptor", interceptorInterface)
                .invoke(builder, proxy)
            chain.proceed()
        }
    }

    private fun handleIntercept(chain: Any): Any {
        val cClass = chain.javaClass
        val request = cClass.getMethod("request").invoke(chain)
        val url = request.javaClass.getMethod("url").invoke(request)
        val requestUrl = url.toString()

        val response = cClass.getMethod("proceed", request.javaClass).invoke(chain, request)
        val responseReq = response.javaClass.getMethod("request").invoke(response)
        val responseUrl = responseReq.javaClass.getMethod("url").invoke(responseReq).toString()

        val match = URL_REGEX.find(responseUrl) ?: URL_REGEX.find(requestUrl)
        if (match != null) {
            val userId = match.groupValues[1]
            val peekBody = response.javaClass.getMethod("peekBody", Long::class.javaPrimitiveType)
            val body = peekBody.invoke(response, Long.MAX_VALUE)
            val bodyStr = body.javaClass.getMethod("string").invoke(body) as String

            if (bodyStr.startsWith("{") && bodyStr.endsWith("}")) {
                thread {
                    try {
                        val dir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOCUMENTS
                        )
                        dir.mkdirs()
                        val file = File(dir, "fgo_login_top_${userId}_${System.currentTimeMillis()}.json")
                        file.writeText(bodyStr)
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

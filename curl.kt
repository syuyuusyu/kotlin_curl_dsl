package com.mkh.bqm.base.util

import cn.hutool.extra.spring.SpringUtil
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.jsonwebtoken.Jwts
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


val defaultOkHttpClient = OkHttpClient.Builder()
    .readTimeout(3, TimeUnit.MINUTES)
    .connectTimeout(3,TimeUnit.MINUTES)
    .build()


fun json(build: JsonObjectBuilder.() -> Unit): JSONObject {
    val builder = JsonObjectBuilder()
    builder.build()
    return builder.json
}

fun jsonArr(build: JsonArrayBuilder.() -> Unit) :JSONArray{
    val builder = JsonArrayBuilder()
    builder.build()
    return builder.arr
}

class JsonObjectBuilder {
    val json = JSONObject()
    infix fun <T> String.to(value: T) {
        json.put(this, value)
    }
}

class JsonArrayBuilder{
    val arr = JSONArray()
    operator fun <T> T.unaryPlus(){
        arr.put(this)
    }
}

var objectMapper = ObjectMapper().registerKotlinModule()
fun <T> jsonToBean(json: String?, clazz: Class<T>): T = try { objectMapper.readValue(json, clazz) } catch (e: Exception) { println(json); throw e }
fun <T> jsonToBean(json: String?, clazz: TypeReference<T>): T = try { objectMapper.readValue(json, clazz) } catch (e: Exception) {e.printStackTrace(); throw e }

class ProgressResponse(
    val responseBody:ResponseBody,
    val processFun : (loaded:Long, total:Long,done:Boolean)->Unit
) :ResponseBody(){
    private var bufferedSource:BufferedSource?=null

    override fun contentLength(): Long = responseBody.contentLength()

    override fun contentType(): MediaType? = responseBody.contentType()

    override fun source(): BufferedSource {
        if (bufferedSource == null) {
            bufferedSource = source(responseBody.source())?.let { it.buffer() };
        }
        return bufferedSource!!;
    }

    private fun source(source: Source): Source? {
        return object : ForwardingSource(source) {
            var totalBytesRead = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                var bytesRead:Long=0
                try{
                    bytesRead = super.read(sink, byteCount)
                }catch (e: SocketTimeoutException){
                    throw e
                }
                totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                processFun(totalBytesRead,responseBody.contentLength(),bytesRead == -1L)
                return bytesRead
            }
        }
    }
}

class ProgressRequest(
    val file: File,
    val processFun : (loaded:Long, total:Long,done:Boolean)->Unit
):RequestBody(){
    override fun contentType(): MediaType? {
        return "application/octet-stream".toMediaTypeOrNull()
    }

    override fun contentLength(): Long {
        return try {
            file.length()
        } catch (e: IOException) {
            0
        }
    }

    override fun writeTo(sink: BufferedSink) {
        var source: Source? = null
        val filesize= contentLength()
        try {
            source = file.source()
            var total: Long = 0
            var read: Long
            while (source.read(sink.buffer, 4096).also { read = it } != -1L) {
                total += read
                sink.flush()
                processFun(total,filesize,total >= filesize)
            }
        } catch (e:SocketTimeoutException){
            throw e
        } finally {
            source?.close()
        }
    }

}

class RequestMap{
    var map = HashMap<String,Any>()
    infix fun String.to(obj:Any){
        map[this] = obj
    }
}

class RequestEntity{
    var head: RequestMap? = null
    var params : RequestMap? = null
    var body : RequestBody?=null
    var originFile :File?=null
    lateinit var urlstr: String
    var method = "get"
    val request : Request
        get() {
            val build = Request.Builder()
            method = method.uppercase(Locale.getDefault())
            when (method){
                "GET","HEAD" -> build.method(method,null)
                "POST","PUT","PATCH" ->
                    if(body ==null) throw Exception("method $method need requestBody") else build.method(method,body)
                "DELETE" ->  if(body ==null) build.delete() else build.delete(body)
                else -> throw Exception("unsupported method $method")
            }
            var flag = !urlstr.contains("?")
            params?.map?.forEach { (k, v) ->
                this.urlstr += if(flag) "?$k=$v" else "&$k=$v"
                flag=false
            }
            build.url(urlstr)
            val addMap = HashMap<String,Any>()
            head?.map?.forEach(addMap::put)


            addMap.forEach { (k, v) -> build.addHeader(k,v as String) }
            return build.build()
        }

    fun head(init: RequestMap.() -> Unit) {
        head = RequestMap()
        head!!.init()
    }
    fun head(map:Map<String,Any>){
        head = RequestMap()
        map.forEach { (k, v) ->
            head!!.map[k] = v
        }
    }
    fun url(url:String) {
        urlstr=url
    }
    fun method(method:String){
        this.method = method
    }
    fun params(init: RequestMap.() -> Unit) {
        params = RequestMap()
        params!!.init()
    }
    fun body(build:()->Any) {
        when (val result = build()){
            is InputStream -> {
                val buf = ByteArray(result.available())
                while (result.read(buf) != -1)
                body = buf.toRequestBody("application/octet-stream".toMediaType(), 0, buf.size)
            }
            is File ->{
                this.originFile = result
                val input: InputStream = FileInputStream(result)
                val buf = ByteArray(input.available())
                while (input.read(buf) != -1)
                body = buf.toRequestBody("application/octet-stream".toMediaType(), 0, buf.size)
            }
            is String -> body = result.toRequestBody("application/json".toMediaTypeOrNull())
            is JSONObject -> body = result.toString().toRequestBody("application/json".toMediaTypeOrNull())
            is JSONArray -> body = result.toString().toRequestBody("application/json".toMediaTypeOrNull())
            is RequestBody -> body = result
        }
    }
}

class CurlEntity{
    var client = defaultOkHttpClient
    lateinit var request : Request
    lateinit var response: Response
    lateinit var requestEntity: RequestEntity
    var returnType: Any?=null

    var curlEvent: CurlEvent?=null

    var enableDownloadProcess = false
    var enableUploadProcess = false


    fun client(buildClient:()->OkHttpClient){
        this.client = buildClient()
    }
    fun request(buildRequest: RequestEntity.() -> Unit){
        requestEntity = RequestEntity()
        requestEntity.buildRequest()
        request = requestEntity.request
    }
    fun returnType(type:Any){
        this.returnType =  type
    }
    fun event(eventFun: CurlEvent.()->Unit){
        this.curlEvent = CurlEvent(this@CurlEntity, eventFun)
    }
}

class CurlEvent(): CoroutineScope by CoroutineScope(Dispatchers.IO) {

    enum class ProcessCode{beforeCall,faterCall}

    lateinit var eventFun : CurlEvent.() -> Unit
    lateinit var curlEntity: CurlEntity
    constructor(entity: CurlEntity, init: CurlEvent.() -> Unit) : this() {
        this.curlEntity = entity
        this.eventFun = init
    }
    var code = ProcessCode.beforeCall
    var watchProcessing = true
    var threadPool: Executor?=null
    lateinit var response: Response
    lateinit var request: Request
    var inputStream : InputStream? = null
    var source : BufferedSource? = null
    var watchRunning :AtomicBoolean = AtomicBoolean(false)
    var readStreamRunning = AtomicBoolean(false)
    fun close() {
        watchProcessing = false
        watchRunning.set(false)

        readStreamRunning.set(false)
        source?.close()

        inputStream?.close()
        response.close()

    }
    fun threadPool(pol:Executor){
        this.threadPool = pol
    }

    fun threadPool(threadFun:()->Executor){
        this.threadPool = threadFun()
    }
    fun onWacth(watchFun:(readline:String)->Unit)  {
        if(code != ProcessCode.faterCall){
            return
        }
        source = response.body?.source()
        watchRunning.set(true)

        var job:Job?=null
        job = launch(threadPool?.asCoroutineDispatcher() ?: Dispatchers.Default) {
            while (watchRunning.get()){
                response.body?.source()
                try {
                    val line = source?.readUtf8Line()
                    if (line != null) {
                        watchFun(line)
                    }
                    val flag = source?.exhausted()!!
                    if(flag){
                        println("source exhausted")
                        close()
                    }
                }catch (e:Exception){
                    e.printStackTrace()
                    close()
                }
            }
        }
    }
    fun readStream(inputfun:(input:ByteArray)->Unit){
        if(code != ProcessCode.faterCall) return
        inputStream = response.body?.byteStream()!!
        readStreamRunning.set(true)
        launch(threadPool?.asCoroutineDispatcher() ?: Dispatchers.Default) {
            while (readStreamRunning.get()){
                val buf = ByteArray(4096)
                val count = inputStream?.read(buf)!!
                if(count>0){
                    inputfun(buf)
                }
                //delay(1000)
            }
        }
    }
    fun onDownloadProgress(processFun : (loaded:Long, total:Long,done:Boolean)->Unit){
        if(code != ProcessCode.beforeCall) return
        this.curlEntity.enableDownloadProcess = true
        this.curlEntity.client = this.curlEntity.client.newBuilder().addNetworkInterceptor { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse.newBuilder()
                .body(ProgressResponse(originalResponse.body!!, processFun))
                .build()
        }.build()
    }
    fun onUploadProgress(processFun : (loaded:Long, total:Long,done:Boolean)->Unit){
        if(code != ProcessCode.beforeCall) return
        if( this.curlEntity.requestEntity.originFile == null){
            throw Exception("request type numst be File when enable onUploadProgress")
        }
        this.curlEntity.enableUploadProcess = true
        this.curlEntity.requestEntity.body = ProgressRequest(this.curlEntity.requestEntity.originFile!!, processFun)
    }
}

fun request(init: RequestEntity.() -> Unit): Request {
    val en = RequestEntity()
    en.init()
    return en.request
}

fun curl(exec: CurlEntity.()->Unit) :Any{
    var result :Any
    val entity = CurlEntity()
    entity.exec()
    entity.curlEvent?.let {
        it.code = CurlEvent.ProcessCode.beforeCall
        entity.client = entity.client.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()
        it.eventFun(it)
    }
    val call: Call = entity.client.newCall(entity.request)
    val response = call.execute()

    entity.response = response

    entity.curlEvent?.let {
        it.code = CurlEvent.ProcessCode.faterCall
        it.request = entity.request
        it.response = entity.response
        it.eventFun(it)
        if (it.watchRunning.get() || it.readStreamRunning.get()){
            return it
        }
    }
    if(entity.enableUploadProcess || entity.enableDownloadProcess){
        return response
    }
    result = response
    entity.returnType?.let {
        result = try {
            when (it) {
                is TypeReference<*> -> jsonToBean(response.body?.string(), it)
                is Class<*> -> jsonToBean(response.body?.string(), it)
                else -> throw Exception("unknown return type $it")
            }.also {
                response.body?.close()
                response.close()
            }
        }catch (e:Exception){
            e.printStackTrace()
            Any()
        }finally {
            response.body?.close()
            response.close()
        }
    }
    return result
}

fun <T> curl(clazz: TypeReference<T>,exec: CurlEntity.()->Unit) :T {
    val entity = CurlEntity()
    entity.exec()
    val call: Call = entity.client.newCall(entity.request)
    val response = call.execute()
    entity.response = response
    val str = response.body?.string()
    //println(str)
    return jsonToBean(str, clazz).also {
        response.body?.close()
        response.close()
    }
}

fun <T> curl(clazz: Class<T>,exec: CurlEntity.()->Unit) :T {
    val entity = CurlEntity()
    entity.exec()
    val call: Call = entity.client.newCall(entity.request)
    val response = call.execute()
    entity.response = response
    return jsonToBean(response.body?.string(), clazz).also {
        response.body?.close()
        response.close()
    }
}




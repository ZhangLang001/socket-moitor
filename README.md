# 基于socket hook方式的app抓包

在app抓包方面，最简单的就是通过fiddler或者charles，网上很多教程。但是越来越多的app无法简单通过代理抓包了。可能有如下原因


## 证书检查
app本身使用私密的https证书，而不信任我们配置到系统根证书的ca证书，这个时候一般使用JustTrustMe插件可以解决。
但是由于JustTrustMe是通过hook网络库的证书检查逻辑实现的，如果网络库的代码存在混淆，那么JustTrustMe则无法定位到代码，也就无法成功拦截。

## 双向证书认证
服务器检查了客户端的证书，此时需要通过逆向得到客户端证书，然后配置到我们的代理层。需要逆向手段实现，而且几乎没有傻瓜化方案。

## app设置不走系统代理
app的代码逻辑可以定制代理方案，可以设置不走代理，或者走app特殊设定的代理。此时我们配置的系统代理不能生效，那么无法通过charles抓包。
这个时候一般通过VPN来实现，比如PacketCapture。但是通过架设VPN的方式，基本都是在APP内部操作，实际在请求太多的情况下，app并不是特别好操作。



所以很早之前我就设计过一个通过JavaSocket对象进行抓包的方案，不过随着时间推移方案逐渐的不那么好用了。所以现在找时间重构了她。整个过程我遇到过的问题可以大概列举一下

## SSLSocket hook代码重入问题
我们知道，SSLSocket底层就是基于普通的socket，那么我们对socket对象监控，其实应该监控SSLSocket，但是我们的代码不能写死说当遇到SSL的时候，放弃对Socket对象监控。
这个时候我们的逻辑里面，会出现SSLSocket的数据流和Socket的数据流两份。其中Socket的流量是密文，需要考虑如何分辨以及如何保证数据不不重复解码。

目前通过线程变量，控制reentryFlag实现，我只能期待SSLSocket和Socket的读写一定发生在同一个线程。否则我的方案会直接紊乱。

### TCP粘包拆包
在存在连接池，或者链接复用场景下，一个app的多个请求可能在同一个链接中发生。这导致的问题，流量不是单纯的一读一写。我们需要自己重报文中解析出每个http请求和响应报文。
这设计到http协议的完整识别，包括http报文长度计算，http报文分段传输等问题。

这里目前有两种方向
1. 完整的解析http协议特征，然后逐步解析到每个http报文的结尾，但是由于存在TCP拆包问题，每次流量到来都无法明确数据到来完整。比如http分段，包头长度可能由于拆包变成两份。如果简单的读取当前坐标，那么可能坐标不完全。所以需要相对比较复杂的状态机来控制。
以及数据到来分片，但是我们进行数据解析时候，由于大量计算依赖各种索引数据，我们的数据结构很难维护。如果每次数据到来都把它全量刷到数组中，明显资源浪费太多。总之这么做逻辑很复杂。
2. 我们假定数据请求和响应是一个完整单元。一端只有在保证完整收到对端数据之后才可能回写自己的数据。那么通过时间来来，每当流量读写模式改变，那么我们可以认为上一个报文一定完整。
但是如果协议没有明确这么规定，那么一定也是会有问题的。

### GZIP压缩
实际场景很容易出现GZIP压缩，我们输出最终报文的时候，肯定不能直接现实压缩的数据，这种对我们抓包分析毫无作用。所以我们还需要通过对存在压缩的数据解压缩。


这次我重构后的socket监控，支持如下特性。

1. 支持所有基于java Socket对象的流量抓包
2. 不需要考虑证书问题，包括证书检查，证书双向认证等。
3. 不需要考虑网络库代码混淆问题。
4. 不需要考虑代码设置不走系统代理的问题
5. 支持数据报文内容和代码堆栈绑定(这个功能我觉得非常重要)
6. 支持http1.x协议自动美化，包括处理分段，处理压缩
7. 支持非http协议监控(本身抓包基于socket的，本来就和http没关系),并且同样支持绑定堆栈.
8. 提供数据识别美化插件机制，http1.x美化功能就是通过改机制实现
9. 提供数据报文数据监控插件机制，可将报文序列化到文件，输出到控制台等。



# 使用方法，
## 依赖

基于xposed
```
 compileOnly 'de.robv.android.xposed:api:82'
```
这是用于方案通过hook socket实现，你可以替换为任意其他hook框架，只要能够hook socket那三个函数就可以

## 开启模块
`` SocketMonitor.setPacketEventObserver(new FileLogEventObserver(context.getDir("socket_monitor", Context.MODE_PRIVATE)));``

SocketMonitor的静态函数会自动加载组件，不过一般来说抓包通过文件的方式写入到磁盘。所以这里需要指定输出地址，使用``FileLogEventObserver``，另外由于
遇到一些app没有sdcard权限，所以我没有把这个地方设计为直接写到sdcard。

然后你可以看到对应目录出现了抓包文件:

```
sailfish:/data/data/com.virjar.ratel.demoapp/app_ratel_env_mock/default_0/data/app_socket_monitor # ls -alh
total 2.8M
drwx------ 2 u0_a71 u0_a71  12K 2019-10-22 17:02 .
drwx------ 4 u0_a71 u0_a71 4.0K 2019-10-22 14:34 ..
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:36 1571758577427_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:37 1571758613992_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:37 1571758650913_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:38 1571758687933_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:39 1571758730303_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:39 1571758767663_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:40 1571758804249_socket.txt
-rw------- 1 u0_a71 u0_a71  33K 2019-10-22 15:40 1571758840846_socket.txt
```
打开文件可以看到报文内容
```
sailfish:/data/data/com.virjar.ratel.demoapp/app_ratel_env_mock/default_0/data/app_socket_monitor # cat 1571758174855_socket.txt
Socket request local port:41752 remote address:47.94.106.20:80
StackTrace:java.lang.Throwable
	at com.virjar.ratel.api.inspect.socket.OutputStreamWrapper.check(OutputStreamWrapper.java:111)
	at com.virjar.ratel.api.inspect.socket.OutputStreamWrapper.write(OutputStreamWrapper.java:68)
	at okio.Okio$1.write(Okio.java:79)
	at okio.AsyncTimeout$1.write(AsyncTimeout.java:180)
	at okio.RealBufferedSink.flush(RealBufferedSink.java:216)
	at okhttp3.internal.http1.Http1Codec.finishRequest(Http1Codec.java:166)
	at okhttp3.internal.http.CallServerInterceptor.intercept(CallServerInterceptor.java:72)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.java:45)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.java:93)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.java:93)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.java:120)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.RealCall.getResponseWithInterceptorChain(RealCall.java:185)
	at okhttp3.RealCall.execute(RealCall.java:69)
	at com.virjar.ratel.demoapp.SocketMonitorTest.doTest(SocketMonitorTest.java:51)
	at com.virjar.ratel.demoapp.SocketMonitorTest.access$000(SocketMonitorTest.java:16)
	at com.virjar.ratel.demoapp.SocketMonitorTest$1.run(SocketMonitorTest.java:25)


GET /natChannelStatus?group=sekiro-demo HTTP/1.1
X-User-Experience-ID: aa54710f-7383-4a09-8923-3e897d3e1bbf
X-Channel-ID: ANDROID
X-API-Key: l7xx8389a5ba9eb24ae68bad068bd1860bfc
User-Agent: SouthwestAndroid/6.10.2 android/8.1.0
Accept-Encoding: gzip
Host: sekiro.virjar.com
Connection: Keep-Alive



Socket response local port:41752 remote address:47.94.106.20:80
StackTrace:java.lang.Throwable
	at com.virjar.ratel.api.inspect.socket.InputStreamWrapper.check(InputStreamWrapper.java:145)
	at com.virjar.ratel.api.inspect.socket.InputStreamWrapper.read(InputStreamWrapper.java:100)
	at okio.Okio$2.read(Okio.java:139)
	at okio.AsyncTimeout$2.read(AsyncTimeout.java:237)
	at okio.RealBufferedSource.indexOf(RealBufferedSource.java:345)
	at okio.RealBufferedSource.readUtf8LineStrict(RealBufferedSource.java:217)
	at okio.RealBufferedSource.readUtf8LineStrict(RealBufferedSource.java:211)
	at okhttp3.internal.http1.Http1Codec.readResponseHeaders(Http1Codec.java:189)
	at okhttp3.internal.http.CallServerInterceptor.intercept(CallServerInterceptor.java:75)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.connection.ConnectInterceptor.intercept(ConnectInterceptor.java:45)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.internal.cache.CacheInterceptor.intercept(CacheInterceptor.java:93)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.internal.http.BridgeInterceptor.intercept(BridgeInterceptor.java:93)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RetryAndFollowUpInterceptor.intercept(RetryAndFollowUpInterceptor.java:120)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:92)
	at okhttp3.internal.http.RealInterceptorChain.proceed(RealInterceptorChain.java:67)
	at okhttp3.RealCall.getResponseWithInterceptorChain(RealCall.java:185)
	at okhttp3.RealCall.execute(RealCall.java:69)
	at com.virjar.ratel.demoapp.SocketMonitorTest.doTest(SocketMonitorTest.java:51)
	at com.virjar.ratel.demoapp.SocketMonitorTest.access$000(SocketMonitorTest.java:16)
	at com.virjar.ratel.demoapp.SocketMonitorTest$1.run(SocketMonitorTest.java:25)


HTTP/1.1 200
Server: nginx
Date: Tue, 22 Oct 2019 15:39:04 GMT
Content-Type: application/json;charset=UTF-8
Transfer-Encoding: chunked
Connection: keep-alive

{"status":0,"message":null,"data":[],"clientId":null,"ok":true}

sailfish:/data/data/com.virjar.ratel.demoapp/app_ratel_env_mock/default_0/data/app_socket_monitor #
```



## 备注
1. 本项目收录在ratel项目中，并不是一个独立项目，你可以在他基础上自己改造。所以生产可用还需要二次加工
2. 还不支持http2.0 做之前大概抓了一下charles的包，然后弄好才发现被charles忽悠了。考虑http2.0 报文可以无序发送的问题，本项目机制可能还需要一些修改才能适配。这个项目里面可能不会做了。有兴趣的可以自己看看


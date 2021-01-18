(ns mitm-charlie.core
  (:import
   [io.netty.bootstrap ServerBootstrap Bootstrap]
   [io.netty.channel.socket.nio NioServerSocketChannel NioSocketChannel]
   [io.netty.channel ChannelHandlerContext ChannelInboundHandlerAdapter ChannelDuplexHandler
    ChannelInitializer ChannelOption ChannelHandler ChannelFutureListener
    ChannelOutboundHandlerAdapter]
   [io.netty.channel.nio NioEventLoopGroup]
   [io.netty.handler.codec ByteToMessageDecoder]
   [io.netty.handler.logging LoggingHandler LogLevel]
   [io.netty.handler.codec.http
    DefaultFullHttpResponse
    HttpContent HttpHeaders HttpUtil
    HttpContentCompressor
    HttpRequest HttpResponse
    HttpResponseStatus DefaultHttpHeaders
    HttpServerCodec HttpVersion HttpMethod
    LastHttpContent HttpServerExpectContinueHandler
    HttpHeaderNames]
   [io.netty.buffer Unpooled]
   [java.nio ByteBuffer ByteOrder]
   [java.io ByteArrayOutputStream]
   [io.netty.handler.codec.bytes ByteArrayEncoder]
   [java.util.concurrent LinkedBlockingQueue Executors]
   [java.util ArrayList]
   [java.time Instant])
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))




(defn init-server-bootstrap
  [group handlers-factory]
  (.. (ServerBootstrap.)
      (group group)
      (channel NioServerSocketChannel)
      (childHandler
       (proxy [ChannelInitializer] []
         (initChannel [channel]
           (let [handlers (handlers-factory)]
             (.. channel
                 (pipeline)
                 (addLast (into-array ChannelHandler handlers)))))))
      (childOption ChannelOption/SO_KEEPALIVE true)
      (childOption ChannelOption/AUTO_READ false)
      (childOption ChannelOption/AUTO_CLOSE false)))

(defn start-server [handlers-factory]
  (let [event-loop-group (NioEventLoopGroup.)
        bootstrap (init-server-bootstrap event-loop-group handlers-factory)
        channel (.. bootstrap (bind 9007) (sync) (channel))]
    (-> channel
        .closeFuture
        (.addListener
         (proxy [ChannelFutureListener] []
           (operationComplete [fut]
             (.shutdownGracefully event-loop-group)))))
    channel))

(defn flush-and-close [channel]
  (->
   (.writeAndFlush channel Unpooled/EMPTY_BUFFER)
   (.addListener ChannelFutureListener/CLOSE)))

(defn client-proxy-handler
  [source-channel]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelActive [ctx]
      (.. ctx channel read))
    (channelInactive [ctx]
      (flush-and-close source-channel))
    (channelRead [ctx msg]
      (->
       (.writeAndFlush source-channel msg)
       (.addListener
        (proxy [ChannelFutureListener] []
          (operationComplete [complete-future]
            (if (.isSuccess complete-future)
              (.. ctx channel read)
              (flush-and-close (.. ctx channel))))))))))

(defn connect-client
  [source-channel target-host target-port]
  (.. (Bootstrap.)
      (group (.. source-channel eventLoop))
      (channel (.. source-channel getClass))
      (option ChannelOption/SO_KEEPALIVE true)
      (option ChannelOption/AUTO_READ false)
      (option ChannelOption/AUTO_CLOSE false)
      (handler
       (proxy [ChannelInitializer] []
         (initChannel [channel]
           (.. channel
               pipeline
               (addLast (into-array ChannelHandler
                                    [(client-proxy-handler source-channel)])))
           )))
      (connect target-host target-port)))


(defn my-proxy-handler [readhandler]
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [ctx msg]
      (if (instance? HttpRequest msg)
        (tap> [(.uri msg)])))))


;; (spit "event.log" "test 1\n" :append true)
(defn my-log [msg ctx]
  (spit "event.log" (str/join " " [(.id (.channel ctx)) msg "\n"]) :append true))

(defn my-logging-handler []
  (proxy [ChannelDuplexHandler] []
    (channelActive [ctx]
      (my-log "channelActive" ctx)
      (.. ctx channel read))
    (channelRead [ctx msg]
      (my-log "channelRead" ctx)
      (.fireChannelRead ctx msg))
    (channelUnregistered [ctx]
      (my-log "channelUnregistered" ctx)
      (.fireChannelUnregistered ctx))
    (channelInactive [ctx]
      (my-log "channelInactive" ctx)
      (.fireChannelInactive ctx))
    (channelRegistered [ctx]
      (my-log "channelRegistered" ctx)
      (.fireChannelRegistered ctx))
    (exceptionCaught [ctx cause]
      (my-log "exceptionCaught" ctx)
      (tap> cause)
      (.fireExceptionCaught ctx))
    (userEventTriggered [ctx]
      (my-log "userEventTriggered" ctx)
      (.fireUserEventTriggered ctx))
    (bind [ctx address prom]
      (my-log "bind" ctx)
      (.bind ctx address prom))
    (connect [ctx remote-address local-address prom]
      (my-log "connect" ctx)
      (.connect ctx remote-address local-address prom))
    (disconnect [ctx prom]
      (my-log "disconnect" ctx)
      (.disconnect ctx prom))
    (close [ctx prom]
      (my-log "close" ctx)
      (.close ctx prom))
    (deregister [ctx prom]
      (my-log "deregister" ctx)
      (.deregister ctx prom))
    (channelReadComplete [ctx]
      (my-log "channelReadComplete" ctx)
      (.fireChannelReadComplete ctx))
    (write [ctx msg prom]
      (my-log "write" ctx)
      (.write ctx prom))
    (channelWritabilityChanged [ctx]
      (my-log "channelWritabilityChanged" ctx)
      (.fireChannelWritabilityChanged ctx))
    (flush [ctx msg]
      (my-log "flush" ctx)
      (.flush ctx))
    ))



(comment
  )



(defn netty-request->ring-request [^HttpRequest req ssl?]
  (let [question-mark-index (-> req .uri (.indexOf (int 63)))]
    { :uri (let [idx (long question-mark-index)]
             (if (neg? idx)
               (.getUri req)
               (.substring (.getUri req) 0 idx)))
     :query-string (let [uri (.uri req)]
                     (if (neg? question-mark-index)
                       nil
                       (.substring uri (unchecked-inc question-mark-index))))
     :headers (.headers req) ;; turn this into a clojure map
     :request-method (-> req .method .name str/lower-case keyword)
     ;; :body body
     :scheme (if ssl? :https :http)}))

(comment
  (defonce log (atom []))
  (defn tap> [i]
    (swap! log conj i))

  (swap! log (fn [s] []))

  (defonce servers (atom []))
  (do
    (doseq [s @servers] (.close s))
    (swap! servers (fn [s] [])))

  (swap! servers conj (start-server (fn []
                                      [
                                       (my-logging-handler)
                                       ] )))


  (update-proxy my-logging-handler {"exceptionCaught" (fn [_ ctx cause] (.fireExceptionCaught ctx))})


  (def msg nil)
  (defn readit [ctx m]
    (tap> m)
    (println {:httprequest (instance? java.net.http.HttpRequest m)
              })
    )

  (instance? java.net.http.HttpObject nil)


  (let [s (java.net.Socket. "localhost" 9007 true)]
    (with-open [w (io/writer s)]
      (.write w "GET /hello-clj.txt HTTP/1.0\r\nHost: www.freekpaans.nl\r\n\r\n")
      (.flush w)))

  (def s (java.net.Socket. "localhost" 9007 true))
  (.isClosed s)
  ;; (.setTcpNoDelay s true) should be handled

  (with-open [w (io/writer s)]
    (.write w "GET /hello-clj.txt HTTP/1.0\r\nHost: www.freekpaans.nl\r\n\r\n")
    (.flush w))
  (with-open [r (io/reader s)
              sw (java.io.StringWriter.)]
    (io/copy r sw))





  log

  (def r (first @log))
  ;; get host, port, authority
  (.method r)
  (.protocolVersion r)
  (.get (.headers r) "Host")
  (first (.headers r))
  (doto
      (io.netty.handler.codec.http.DefaultHttpHeaders.)
    (.set "Boop" (into-array Long [1 2 3])))
  (first ^Iterator (.iterator (.headers r)))
  (java.net.URL. (.uri r))

  (.channelInactive (my-logging-handler) (proxy [io.netty.channel.ChannelHandlerContext] [] (fireChannelActive [])))


  (.getAllAppenders (io.netty.util.internal.logging.InternalLoggerFactory/getInstance "beep"))

  (org.apache.logging.log4j.LogManager/getLogger "beep")
  (.logger (LoggingHandler.))

  (org.apache.log4j.Category.)

  (.info  (io.netty.util.internal.logging.Log4JLogger. "boop") "abc")



  (io.netty.util.internal.logging.InternalLoggerFactory/setDefaultFactory
   io.netty.util.internal.logging.JdkLoggerFactory/INSTANCE)

  (map + [1 2 3] [3 2 1])

  (org.slf4j.LoggerFactory/getLogger "abc")





  )

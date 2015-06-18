(ns uxbox.impl.websocket
  (:require [promissum.core :as p]
            [catacumba.impl.context :as ctx]
            [catacumba.impl.handlers :as hs])
  (:import ratpack.handling.Handler
           ratpack.handling.Context
           ratpack.func.Action
           ratpack.exec.ExecController
           java.util.concurrent.ExecutorService
           io.netty.buffer.ByteBuf
           catacumba.impl.context.DefaultContext
           catacumba.websocket.WebSocketHandler
           catacumba.websocket.WebSocketMessage
           catacumba.websocket.WebSockets
           catacumba.websocket.WebSocket))

;; (defrecord WebSocketSession [context state handler wsholder]
;;   WebSocketHandler
;;   (^void onOpen [this ^WebSocket ws]
;;     (vreset! wsholder ws)
;;     (let [context (merge context {:state state :ws this})]
;;       (handler :open context)))

;;   (^void onMessage [_ ^WebSocketMessage msg ^Action callback]
;;     (let [message (.getData msg)
;;           context (merge context {:state state :ws this})]
;;       (-> (handler :message (assoc context :message message))
;;           (p/then (fn [_] (.execute callback nil)))
;;           (p/catch (fn [e]
;;                      (letfn [(on-error [_]
;;                                (.execute callback nil)
;;                                (.close @wsholder 400 (str e))
;;                                (vreset! wsholder nil))]
;;                        (-> (handler :error (assoc context :error e))
;;                            (p/then on-error)
;;                            (p/catch on-error))))))))

;;   (^void onClose [this]
;;     (let [context {:state state :ws this}
;;           on-close (fn [_] (vreset! wsholder nil))]
;;       (-> (handler :close context)
;;           (p/then on-close)
;;           (p/catch on-close)))))

;; (defn send!
;;   [ws data]
;;   (assert (string? data) "data should be string")
;;   (let [sock @(:wsholder ws)]
;;     (.send sock data)))

;; (defn websocket
;;   [^DefaultContext context handler]
;;   (->> (WebSocketSession. context (atom {}) handler (volatile! nil))
;;        (WebSockets/websocket ^Context (:catacumba/context context)))))

;; ;; (defmethod hs/adapter :uxbox/websocket
;; ;;   [handler]
;; ;;   (reify Handler
;; ;;     (^void handle [_ ^Context ctx]
;; ;;       (hs/hydrate-context ctx (fn [^DefaultContext context]
;; ;;                                 (websocket context handler))))))


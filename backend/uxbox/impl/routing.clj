(ns uxbox.impl.routing
  (:require [clojure.core.async :as a]
            [cats.monad.exception :as exc]
            [cats.core :as m]
            [promissum.core :as p]
            [catacumba.core :as ct]
            [catacumba.serializers :as sz]
            [catacumba.impl.websocket :as ws]
            [catacumba.impl.handlers :as hs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn encode
  [data]
  (sz/bytes->str (sz/encode data :transit+json)))

(defn decode
  [data]
  (sz/decode (sz/str->bytes data) :transit+json))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IHandlerResponse
  (-handle-response [_ context frameid options] "Handle the response."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare response)

(defn- generic-error-handler
  [context error]
  (if (instance? clojure.lang.ExceptionInfo error)
    (response :error {:message (.getMessage error)
                      :data (ex-data error)})
    (response :error {:message (str error)})))

(defn- handle-error
  [error frameid context options]
  (let [on-error (::on-error options generic-error-handler)
        response (on-error context error)]
    (-handle-response response context frameid options)))

(defmulti handle-frame
  (fn [frame handler context options]
    (:cmd frame)))

(defmethod handle-frame :default
  [frame handler context options]
  (let [frameid (:id frame)
        response (exc/try-on (handler context frame))]
    (if (exc/success? response)
      (-handle-response @response context frameid options)
      (let [error (m/extract response)]
        (handle-error error frameid context options)))))

(defmethod handle-frame :pong
  [frame _ context options]
  (let [state (:state context)]

(defn- send-decode-error
  [{:keys [out]}]
  (let [frame {:cmd :error :id nil :data "Error on deserializing frame."}]
    (a/go (a/>! out (encode frame)))))

(defn- initialize
  [{:keys [in out ctrl] :as context} options]
  (a/go
    (let [received (a/<! in)
          result (exc/try-on (decode received))]
      (if (and (exc/success? result)
               (= (:cmd @result) :hello))
        (a/>! out (encode {:cmd :hello}))
        (do
          (a/<! (send-decode-error context))
          (a/close! out))))))

(def ^:private
  pong-frames-xform
  (comp
   (map decode)
   (filter #(= (:cmd %) :pong))))

(def ^:private
  user-frames-xform
  (comp
   (map decode)
   (filter #(not= (:cmd %) :pong))))

(defn- pong-frames
  [mult context]
  (let [ch (a/chan 1 pong-frames-xform (fn [_] (send-decode-error context)))]
    (a/tap mult ch true)))

(defn- user-frames
  [mult context]
  (let [ch (a/chan 1 user-frames-xform (fn [_] (send-decode-error context)))]
    (a/tap mult ch true)))

(defn- keepalive-loop
  [{:keys [out] :as context} mult]
  (let [in (ping-frames mult context)]
    (a/go-loop []
      (let [frame {:cmd :ping :id (random-uuid)}]
        (a/>! out (encode frame))
        (let [[v ch] (a/alts! [in (a/timeout 1000)])]
          (if (= ch in)
            (do
              (a/<! (a/timeout 1000))
              (recur))
            (a/close! out)))))))

(defn- messages-loop
  [{:keys [out] :as context} mult]
  (let [in (user-frames mult context)]
    (a/go-loop []
      (when-let [frame (a/<! in)]
        (let [result (exc/try-on (decode frame))]
          (if (exc/success? result)
            (handle-frame @result handler context options)
            (a/<! (send-decode-error context))))
        (recur)))))

(defn- dispatcher-loop
  [{:keys [in] :as context} handler options]
  (let [mult (a/mult in)]
    (keepalive-loop context mult)
    (messages-loop context mult)))

(defn- dispatcher
  [{:keys [in out ctrl] :as context} handler options]
  (a/go-loop []
    (when-let [msg (a/<! ctrl)]
      (if (= msg :close)
        (let [container (::on-close-handlers context)]
          (doseq [item @container]
            (exc/try-on (item)))
          (reset! container []))
        (recur))))
  (a/go
    (a/<! (initialize context options))

    ;; Start dispatcher loop
    (a/<! (dispatcher-loop context handler options))

    ;; closing in any case
    (a/close! out)))

(extend-type java.util.concurrent.CompletableFuture
  IHandlerResponse
  (-handle-response [response context frameid options]
    (letfn [(on-resolve [response]
              (-handle-response response context frameid options))
            (on-reject [error]
              (handle-error error frameid context options))]
      (-> response
          (p/then on-resolve)
          (p/catch on-reject)))))

(defrecord Response [data cmd]
  IHandlerResponse
  (-handle-response [this context frameid options]
    (let [frame (into {:id frameid} this)
          output (:out context)]
      (a/put! output (encode frame)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public Api
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn response
  ([data]
   (response :response data))
  ([type data]
   (map->Response {:data data :cmd type})))

(defn on-close
  [context handler]
  {:pre [(fn? handler) (::on-close-handlers context)]}
  (let [container (::on-close-handlers context)]
    (swap! container conj handler)))

(defn router
  ([handler]
   (router handler {}))
  ([handler options]
   (fn [context]
     (let [context (assoc context
                          ::on-close-handlers (atom [])
                          :state (atom {}))]
       (ws/websocket context #(dispatcher % handler options))))))

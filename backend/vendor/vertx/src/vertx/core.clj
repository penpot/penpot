;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns vertx.core
  (:require
   [clojure.spec.alpha :as s]
   [promesa.core :as p]
   [vertx.eventbus :as vxe]
   [vertx.util :as vu])
  (:import
   io.vertx.core.AsyncResult
   io.vertx.core.Context
   io.vertx.core.DeploymentOptions
   io.vertx.core.Handler
   io.vertx.core.Promise
   io.vertx.core.Verticle
   io.vertx.core.Vertx
   io.vertx.core.VertxOptions
   java.util.function.Supplier))

(declare opts->deployment-options)
(declare opts->vertx-options)
(declare build-verticle)
(declare build-actor)
(declare build-disposable)

;; --- Public Api

(s/def :vertx.core$system/threads pos?)
(s/def :vertx.core$system/on-error fn?)
(s/def ::system-options
  (s/keys :opt-un [:vertx.core$system/threads
                   :vertx.core$system/on-error]))

(defn system
  "Creates a new vertx actor system instance."
  ([] (system {}))
  ([options]
   (s/assert ::system-options options)
   (let [^VertxOptions opts (opts->vertx-options options)
         ^Vertx vsm (Vertx/vertx opts)]
     (vxe/configure! vsm opts)
     vsm)))

(defn stop
  [^Vertx o]
  (.close o))

(def ^:dynamic *context* nil)

(defn get-or-create-context
  [vsm]
  (or *context* (.getOrCreateContext ^Vertx (vu/resolve-system vsm))))

(defn current-context
  []
  (or *context* (Vertx/currentContext)))

(defmacro blocking
  [& body]
  (let [sym-vsm (with-meta (gensym "blocking")
                  {:tag 'io.vertx.core.Vertx})
        sym-e   (with-meta (gensym "blocking")
                  {:tag 'java.lang.Throwable})
        sym-prm (gensym "blocking")
        sym-ar  (gensym "blocking")]
    `(let [~sym-vsm (-> (current-context)
                        (vu/resolve-system))
           d# (p/deferred)]
       (.executeBlocking
        ~sym-vsm
        (reify Handler
          (handle [_ ~sym-prm]
            (let [prm# ~(with-meta sym-prm {:tag 'io.vertx.core.Promise})]
              (try
                (.complete prm# (do ~@body))
                (catch Throwable ~sym-e
                  (.fail prm# ~sym-e))))))
        true
        (reify Handler
          (handle [_ ~sym-ar]
            (let [ar# ~(with-meta sym-ar {:tag 'io.vertx.core.AsyncResult})]
              (if (.failed ar#)
                (p/reject! d# (.cause ar#))
                (p/resolve! d# (.result ar#)))))))
       d#)))

(defn wrap-blocking
  ([f] (wrap-blocking (current-context) f))
  ([ctx f]
   (let [^Vertx vsm (vu/resolve-system ctx)]
     (fn [& args]
       (let [d (p/deferred)]
         (.executeBlocking
          vsm
          (reify Handler
            (handle [_ prm]
              (try
                (.complete ^Promise prm (apply f args))
                (catch Throwable e
                  (.fail ^Promise prm e)))))
          true
          (reify Handler
            (handle [_ ar]
              (if (.failed ^AsyncResult ar)
                (p/reject! d (.cause ^AsyncResult ar))
                (p/resolve! d (.result ^AsyncResult ar))))))
         d)))))

(defn handle-on-context
  "Attaches the context (current if not explicitly provided) to the
  promise execution chain."
  ([prm] (handle-on-context prm (current-context)))
  ([prm ctx]
   (assert (instance? Context ctx) "`ctx` should be a valid Context instance")
   (let [d (p/deferred)]
     (p/finally prm (fn [v e]
                      (.runOnContext
                       ^Context ctx
                       ^Handler (reify Handler
                                  (handle [_ v']
                                    (if e
                                      (p/reject! d e)
                                      (p/resolve! d v)))))))
     d)))

(defn run-on-context
  [ctx f]
  (.runOnContext
   ^Context ctx
   ^Handler (reify Handler
              (handle [_ v']
                (f ctx)))))

(s/def :vertx.core$verticle/on-start fn?)
(s/def :vertx.core$verticle/on-stop fn?)
(s/def :vertx.core$verticle/on-error fn?)
(s/def ::verticle-options
  (s/keys :req-un [:vertx.core$verticle/on-start]
          :opt-un [:vertx.core$verticle/on-stop
                   :vertx.core$verticle/on-error]))

(defn verticle
  "Creates a verticle instance (factory)."
  [options]
  (s/assert ::verticle-options options)
  ^{::verticle true ::options options}
  (reify
    Supplier
    (get [_] (build-verticle options))))

(defn verticle?
  "Return `true` if `v` is instance of `IVerticleFactory`."
  [v]
  (true? (::verticle (meta v))))

(s/def :vertx.core$actor/on-message fn?)
(s/def ::actor-options
  (s/keys :req-un [:vertx.core$actor/on-message]
          :opt-un [:vertx.core$verticle/on-start
                   :vertx.core$verticle/on-error
                   :vertx.core$verticle/on-stop]))

(defn actor
  "A shortcut for create a verticle instance (factory) that consumes a
  specific topic."
  [topic options]
  (s/assert string? topic)
  (s/assert ::actor-options options)
  ^{::verticle true ::options options ::topic topic}
  (reify
    Supplier
    (get [_] (build-actor topic options))))

(s/def :vertx.core$deploy/instances pos?)
(s/def :vertx.core$deploy/worker boolean?)
(s/def ::deploy-options
  (s/keys :opt-un [:vertx.core$deploy/worker
                   :vertx.core$deploy/instances]))

(defn deploy!
  "Deploy a verticle."
  ([vsm supplier] (deploy! vsm supplier nil))
  ([vsm supplier options]
   (s/assert verticle? supplier)
   (s/assert ::deploy-options options)
   (let [d (p/deferred)
         o (opts->deployment-options options)]
     (.deployVerticle ^Vertx vsm
                      ^Supplier supplier
                      ^DeploymentOptions o
                      ^Handler (vu/deferred->handler d))
     (p/then' d (fn [id] (build-disposable vsm id))))))

(defn undeploy!
  "Undeploy the verticle, this function should be rarelly used because
  the easiest way to undeplo is executin the callable returned by
  `deploy!` function."
  [vsm id]
  (s/assert string? id)
  (let [d (p/deferred)]
    (.undeploy ^Vertx (vu/resolve-system vsm)
               ^String id
               ^Handler (vu/deferred->handler d))
    d))

;; --- Impl

(defn- build-verticle
  [{:keys [on-start on-stop on-error]
    :or {on-error (constantly nil)
         on-stop (constantly nil)}
    :as options}]
  (let [vsm (volatile! nil)
        ctx (volatile! nil)
        lst (volatile! nil)]
    (reify Verticle
      (init [_ instance context]
        (vreset! vsm instance)
        (vreset! ctx context))
      (getVertx [_] @vsm)
      (^void start [_ ^Promise o]
       (-> (p/do! (on-start @ctx))
           (p/handle (fn [state error]
                       (if error
                         (do
                           (.fail o  ^Throwable error)
                           (on-error @ctx error))
                         (do
                           (when (map? state)
                             (vswap! lst merge state))
                           (.complete o)))))))
      (^void stop [_ ^Promise o]
       (p/handle (p/do! (on-stop @ctx @lst))
                 (fn [_ err]
                   (if err
                     (do (on-error err)
                         (.fail o ^Throwable err))
                     (.complete o))))))))

(defn- build-actor
  [topic {:keys [on-message on-error on-stop on-start]
          :or {on-error (constantly nil)
               on-start (constantly {})
               on-stop (constantly nil)}}]
  (letfn [(-on-start [ctx]
            (let [state (on-start ctx)
                  state (if (map? state) state {})
                  consumer (vxe/consumer ctx topic on-message)]
              (assoc state ::consumer consumer)))]
    (build-verticle {:on-error on-error
                     :on-stop on-stop
                     :on-start -on-start})))

(defn- build-disposable
  [vsm id]
  (reify
    clojure.lang.IDeref
    (deref [_] id)

    clojure.lang.IFn
    (invoke [_] (undeploy! vsm id))

    java.io.Closeable
    (close [_]
      @(undeploy! vsm id))))

(defn- opts->deployment-options
  [{:keys [instances worker]}]
  (let [opts (DeploymentOptions.)]
    (when instances (.setInstances opts (int instances)))
    (when worker (.setWorker opts worker))
    opts))

(defn- opts->vertx-options
  [{:keys [threads worker-threads on-error]}]
  (let [opts (VertxOptions.)]
    (when threads (.setEventLoopPoolSize opts (int threads)))
    (when worker-threads (.setWorkerPoolSize opts (int worker-threads)))
    #_(when on-error (.exceptionHandler opts (vu/fn->handler on-error)))
    opts))




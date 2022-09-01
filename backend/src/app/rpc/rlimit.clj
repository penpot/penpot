;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.rlimit
  "Rate limit strategies implementation for RPC services.

  It mainly implements two strategies: fixed window and bucket. You
  can use one of them or both to create a combination of limits. All
  limits are updated in each request and the most restrictive one
  blocks the user activity.

  On the HTTP layer it translates to the 429 http response.

  The limits are defined as vector of 3 elements:
    [<name:keyword> <strategy:keyword> <opts:string>]

  The opts format is strategy dependent. With fixed `:window` strategy
  you have the following format:
    [:somename :window \"1000/m\"]

  Where the first number means the quantity of allowed request and the
  letter indicates the window unit, that can be `w` for weeks, `h` for
  hours, `m` for minutes and `s` for seconds.

  The the `:bucket` strategy you will have something like this:
    [:somename :bucket \"100/10/15s]

  Where the first number indicates the total tokens capacity (or
  available burst), the second number indicates the refill rate and
  the last number suffixed with the unit indicates the time window (or
  interval) of the refill. This means that this limit configurations
  allow burst of 100 elements and will refill 10 tokens each 15s (1
  token each 1.5segons).

  The bucket strategy works well for small intervals and window
  strategy works better for large intervals.

  All limits uses the profile-id as user identifier. In case of the
  profile-id is not available, the IP address is used as fallback
  value.
  "
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uri :as uri]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.http :as-alias http]
   [app.loggers.audit :refer [parse-client-ip]]
   [app.redis :as redis]
   [app.redis.script :as-alias rscript]
   [app.rpc :as-alias rpc]
   [app.rpc.rlimit.result :as-alias lresult]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [integrant.core :as ig]
   [promesa.core :as p]
   [promesa.exec :as px]))

(def ^:private default-timeout
  (dt/duration 400))

(def ^:private default-options
  {:codec redis/string-codec
   :timeout default-timeout})

(def ^:private bucket-rate-limit-script
  {::rscript/name ::bucket-rate-limit
   ::rscript/path "app/rpc/rlimit/bucket.lua"})

(def ^:private window-rate-limit-script
  {::rscript/name ::window-rate-limit
   ::rscript/path "app/rpc/rlimit/window.lua"})

(def enabled?
  "Allows on runtime completly disable rate limiting."
  (atom true))

(def ^:private window-opts-re
  #"^(\d+)/([wdhms])$")

(def ^:private bucket-opts-re
  #"^(\d+)/(\d+)/(\d+[hms])$")

(defmulti parse-limit   (fn [[_ strategy _]] strategy))
(defmulti process-limit (fn [_ _ _ o] (::strategy o)))

(defmethod parse-limit :window
  [[name strategy opts :as vlimit]]
  (us/assert! ::limit-tuple vlimit)
  (merge
   {::name name
    ::strategy strategy}
   (if-let [[_ nreq unit] (re-find window-opts-re opts)]
     (let [nreq (parse-long nreq)]
       {::nreq nreq
        ::unit (case unit
                 "d" :days
                 "h" :hours
                 "m" :minutes
                 "s" :seconds
                 "w" :weeks)
        ::key  (dm/str "ratelimit.window." (d/name name))
        ::opts opts})
     (ex/raise :type :validation
               :code :invalid-window-limit-opts
               :hint (str/ffmt "looks like '%' does not have a valid format" opts)))))

(defmethod parse-limit :bucket
  [[name strategy opts :as vlimit]]
  (us/assert! ::limit-tuple vlimit)
  (merge
   {::name name
    ::strategy strategy}
   (if-let [[_ capacity rate interval] (re-find bucket-opts-re opts)]
     (let [interval (dt/duration interval)
           rate     (parse-long rate)
           capacity (parse-long capacity)]
       {::capacity capacity
        ::rate     rate
        ::interval interval
        ::opts     opts
        ::params   [(dt/->seconds interval) rate capacity]
        ::key      (dm/str "ratelimit.bucket." (d/name name))})
     (ex/raise :type :validation
               :code :invalid-bucket-limit-opts
               :hint (str/ffmt "looks like '%' does not have a valid format" opts)))))

(defmethod process-limit :bucket
  [redis user-id now {:keys [::key ::params ::service ::capacity ::interval ::rate] :as limit}]
  (let [script (-> bucket-rate-limit-script
                   (assoc ::rscript/keys [(dm/str key "." service "." user-id)])
                   (assoc ::rscript/vals (conj params (dt/->seconds now))))]
    (-> (redis/eval! redis script)
        (p/then (fn [result]
                  (let [allowed?  (boolean (nth result 0))
                        remaining (nth result 1)
                        reset     (* (/ (inst-ms interval) rate)
                                     (- capacity remaining))]
                    (l/trace :hint "limit processed"
                             :service service
                             :limit (name (::name limit))
                             :strategy (name (::strategy limit))
                             :opts (::opts limit)
                             :allowed? allowed?
                             :remaining remaining)
                    (-> limit
                        (assoc ::lresult/allowed? allowed?)
                        (assoc ::lresult/reset (dt/plus now reset))
                        (assoc ::lresult/remaining remaining))))))))

(defmethod process-limit :window
  [redis user-id now {:keys [::nreq ::unit ::key ::service] :as limit}]
  (let [ts     (dt/truncate now unit)
        ttl    (dt/diff now (dt/plus ts {unit 1}))
        script (-> window-rate-limit-script
                   (assoc ::rscript/keys [(dm/str key "." service "." user-id "." (dt/format-instant ts))])
                   (assoc ::rscript/vals [nreq (dt/->seconds ttl)]))]
    (-> (redis/eval! redis script)
        (p/then (fn [result]
                  (let [allowed?  (boolean (nth result 0))
                        remaining (nth result 1)]
                    (l/trace :hint "limit processed"
                             :service service
                             :limit (name (::name limit))
                             :strategy (name (::strategy limit))
                             :opts (::opts limit)
                             :allowed? allowed?
                             :remaining remaining)
                    (-> limit
                        (assoc ::lresult/allowed? allowed?)
                        (assoc ::lresult/remaining remaining)
                        (assoc ::lresult/reset (dt/plus ts {unit 1})))))))))

(defn- process-limits
  [redis user-id limits now]
  (-> (p/all (map (partial process-limit redis user-id now) limits))
      (p/then (fn [results]
                (let [remaining (->> results
                                     (d/index-by ::name ::lresult/remaining)
                                     (uri/map->query-string))
                      reset     (->> results
                                     (d/index-by ::name (comp dt/->seconds ::lresult/reset))
                                     (uri/map->query-string))
                      rejected  (->> results
                                     (filter (complement ::lresult/allowed?))
                                     (first))]

                  (when (and rejected (contains? cf/flags :warn-rpc-rate-limits))
                    (l/warn :hint "rejected rate limit"
                            :user-id (dm/str user-id)
                            :limit-service (-> rejected ::service name)
                            :limit-name (-> rejected ::name name)
                            :limit-strategy (-> rejected ::strategy name)))

                  {:enabled? true
                   :allowed? (some? rejected)
                   :headers  {"x-rate-limit-remaining" remaining
                              "x-rate-limit-reset" reset}})))))

(defn- handle-response
  [f cfg params rres]
  (if (:enabled? rres)
    (let [headers {"x-rate-limit-remaining" (:remaining rres)
                   "x-rate-limit-reset" (:reset rres)}]
      (when-not (:allowed? rres)
        (ex/raise :type :rate-limit
                  :code :request-blocked
                  :hint "rate limit reached"
                  ::http/headers headers))
      (-> (f cfg params)
          (p/then (fn [response]
                    (with-meta response
                      {::http/headers headers})))))

    (f cfg params)))

(defn wrap
  [{:keys [rlimit redis] :as cfg} f mdata]
  (let [skey          (keyword (::rpc/type cfg) (->> mdata ::sv/spec name))
        sname         (dm/str (::rpc/type cfg) "." (->> mdata ::sv/spec name))
        default-rresp (p/resolved {:enabled? false})]
    (if (or (contains? cf/flags :rpc-rate-limit)
            (contains? cf/flags :soft-rpc-rate-limit))
      (fn [cfg {:keys [::http/request] :as params}]
        (let [user-id (or (:profile-id params)
                          (some-> request parse-client-ip)
                          uuid/zero)

              rresp   (when (and user-id @enabled?)
                        (when-let [limits (get-in @rlimit [::limits skey])]
                          (let [redis  (redis/get-or-connect redis ::rlimit default-options)
                                limits (map #(assoc % ::service sname) limits)
                                rresp  (-> (process-limits redis user-id limits (dt/now))
                                           (p/catch (fn [cause]
                                                      ;; If we have an error on processing the
                                                      ;; rate-limit we just skip it for do not cause
                                                      ;; service interruption because of redis downtime
                                                      ;; or similar situation.
                                                      (l/error :hint "error on processing rate-limit" :cause cause)
                                                      {:enabled? false})))]

                            ;; If soft rate are enabled, we process the rate-limit but return
                            ;; unprotected response.
                            (and (contains? cf/flags :soft-rpc-rate-limit) rresp))))]

          (p/then (or rresp default-rresp)
                  (partial handle-response f cfg params))))
      f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONFIG WATCHER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::strategy (s/and ::us/keyword #{:window :bucket}))
(s/def ::capacity ::us/integer)
(s/def ::rate ::us/integer)
(s/def ::interval ::dt/duration)
(s/def ::key ::us/string)
(s/def ::opts ::us/string)
(s/def ::params vector?)
(s/def ::unit #{:days :hours :minutes :seconds :weeks})
(s/def ::nreq ::us/integer)
(s/def ::refresh ::dt/duration)

(s/def ::limit-tuple
  (s/tuple ::us/keyword ::strategy string?))

(s/def ::limits
  (s/map-of keyword? (s/every ::limit :kind vector?)))

(s/def ::limit
  (s/and
   (s/keys :req [::name ::strategy ::key ::opts])
   (s/or :bucket
         (s/keys :req [::capacity
                       ::rate
                       ::interval
                       ::params])
         :window
         (s/keys :req [::nreq
                       ::unit]))))

(s/def ::rlimit
  #(instance? clojure.lang.Agent %))

(s/def ::config
  (s/map-of (s/or :kw keyword? :set set?)
            (s/every ::limit-tuple :kind vector?)))

(defn read-config
  [path]
  (letfn [(compile-pass-1 [config]
            (reduce-kv (fn [o k v]
                         (cond
                           (keyword? k)
                           (assoc o k (mapv parse-limit v))

                           (set? k)
                           (let [limits (mapv parse-limit v)]
                             (reduce #(assoc %1 %2 limits) o k))

                           :else
                           (throw (ex-info "invalid arguments" {}))))
                       {}
                       config))

          (compile-pass-2 [config]
            (let [default (:default config)]
              (reduce-kv (fn [o k v]
                           (assoc o k (into [] (d/distinct-xf ::name) (concat v default))))
                         {}
                         config)))]

    (when-let [config (some->> path slurp edn/read-string)]
      (us/verify! ::config config)
      (let [refresh (->> config meta :refresh dt/duration)
            limits  (->> config compile-pass-1 compile-pass-2)]

        (us/verify! ::limits limits)
        (us/verify! ::refresh refresh)

        {::refresh refresh
         ::limits limits}))))

(defn- refresh-config
  [{:keys [state path executor scheduler] :as params}]
  (letfn [(update-config [{:keys [::updated-at] :as state}]
            (let [updated-at' (fs/last-modified-time path)]
              (merge state
                     {::updated-at updated-at'}
                     (when (or (nil? updated-at)
                               (not= (inst-ms updated-at')
                                     (inst-ms updated-at)))
                       (let [state (read-config path)]
                         (l/info :hint "config refreshed"
                                 :loaded-limits (count (::limits state))
                                 ::l/async false)
                         state)))))

          (schedule-next [state]
            (px/schedule! scheduler
                          (inst-ms (::refresh state))
                          (partial refresh-config params))
            state)]

    (send-via executor state update-config)
    (send-via executor state schedule-next)))

(defn- on-refresh-error
  [_ cause]
  (when-not (instance? java.util.concurrent.RejectedExecutionException cause)
    (if-let [explain (-> cause ex-data us/pretty-explain)]
      (l/warn ::l/raw (str "unable to refresh config, invalid format:\n" explain)
              ::l/async false)
      (l/warn :hint "unexpected exception on loading config"
              :cause cause
              ::l/async false))))

(defn- get-config-path
  []
  (when-let [path (cf/get :rpc-rlimit-config)]
    (and (fs/exists? path) (fs/regular-file? path) path)))

(defmethod ig/pre-init-spec :app.rpc/rlimit [_]
  (s/keys :req-un [::wrk/executor ::wrk/scheduler]))

(defmethod ig/init-key :app.rpc/rlimit
  [_ {:keys [executor] :as params}]
  (let [state (agent {})]

    (set-error-handler! state on-refresh-error)
    (set-error-mode! state :continue)

    (when-let [path (get-config-path)]
      (l/info :hint "initializing rlimit config reader" :path (str path))

      ;; Initialize the state with initial refresh value
      (send-via executor state (constantly {::refresh (dt/duration "5s")}))

      ;; Force a refresh
      (refresh-config (assoc params :path path :state state)))

    state))

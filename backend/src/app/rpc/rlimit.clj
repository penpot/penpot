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
   [app.rpc.rlimit.result :as-alias lresult]
   [app.util.services :as-alias sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

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

(s/def ::strategy (s/and ::us/keyword #{:window :bucket}))

(s/def ::limit-definition
  (s/tuple ::us/keyword ::strategy string?))

(defmulti parse-limit   (fn [[_ strategy _]] strategy))
(defmulti process-limit (fn [_ _ _ o] (::strategy o)))

(defmethod parse-limit :window
  [[name strategy opts :as vlimit]]
  (us/assert! ::limit-definition vlimit)
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
  (us/assert! ::limit-definition vlimit)
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
  (-> (p/all (map (partial process-limit redis user-id now) (reverse limits)))
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

(defn- parse-limits
  [service limits]
  (let [default (some->> (cf/get :default-rpc-rlimit)
                         (us/conform ::limit-definition))

        limits  (cond->> limits
                  (some? default) (cons default))]

    (->> (reverse limits)
         (sequence (comp (map parse-limit)
                         (map #(assoc % ::service service))
                         (d/distinct-xf ::name))))))

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
  [{:keys [redis] :as cfg} f {service ::sv/name :as mdata}]
  (let [limits        (parse-limits service (::limits mdata))
        default-rresp (p/resolved {:enabled? false})]

    (if (and (seq limits)
             (or (contains? cf/flags :rpc-rate-limit)
                 (contains? cf/flags :soft-rpc-rate-limit)))
      (fn [cfg {:keys [::http/request] :as params}]
        (let [user-id (or (:profile-id params)
                          (some-> request parse-client-ip)
                          uuid/zero)

              rresp   (when (and user-id @enabled?)
                        (let [redis (redis/get-or-connect redis ::rlimit default-options)
                              rresp (-> (process-limits redis user-id limits (dt/now))
                                        (p/catch (fn [cause]
                                                   ;; If we have an error on processing the
                                                   ;; rate-limit we just skip it for do not cause
                                                   ;; service interruption because of redis downtime
                                                   ;; or similar situation.
                                                   (l/error :hint "error on processing rate-limit" :cause cause)
                                                   {:enabled? false})))]

                          ;; If soft rate are enabled, we process the rate-limit but return
                          ;; unprotected response.
                          (and (contains? cf/flags :soft-rpc-rate-limit) rresp)))]

          (p/then (or rresp default-rresp)
                  (partial handle-response f cfg params))))
      f)))

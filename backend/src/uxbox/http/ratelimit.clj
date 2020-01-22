;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.http.ratelimit
  "Rate limit"
  (:require
   [clojure.spec.alpha :as s]
   [uxbox.common.exceptions :as ex]
   [uxbox.core :refer [system]]
   [vertx.core :as vc]
   [promesa.core :as p]
   [promesa.exec :as pe])
  (:import
   io.github.resilience4j.ratelimiter.RateLimiter
   io.github.resilience4j.ratelimiter.RateLimiterConfig
   io.github.resilience4j.ratelimiter.RateLimiterRegistry
   java.util.concurrent.CompletableFuture
   java.util.function.Supplier
   java.time.Duration))

;; --- Rate Limiter

(def ^:private registry (RateLimiterRegistry/ofDefaults))

(defn- opts->rate-limiter-config
  [{:keys [limit period timeout] :as opts}]
  (let [custom (RateLimiterConfig/custom)]
    (.limitRefreshPeriod custom (Duration/ofMillis period))
    (.limitForPeriod custom limit)
    (.timeoutDuration custom (Duration/ofMillis timeout))
    (.build custom)))

(defn ratelimit
  [f {:keys [name] :as opts}]
  (let [config (opts->rate-limiter-config opts)
        rl (.rateLimiter registry name config)]
    (fn [& args]
      (let [ctx (vc/get-or-create-context system)]
        (-> (pe/run! #(when-not (.acquirePermission rl 1)
                        (ex/raise :type :ratelimit
                                  :code :timeout
                                  :context {:name name})))
            (vc/handle-on-context)
            (p/bind (fn [_] (p/do! (apply f args)))))))))

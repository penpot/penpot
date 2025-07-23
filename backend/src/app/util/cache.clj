;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.cache
  "In-memory cache backed by Caffeine"
  (:refer-clojure :exclude [get])
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [promesa.exec :as px])
  (:import
   com.github.benmanes.caffeine.cache.AsyncCache
   com.github.benmanes.caffeine.cache.Cache
   com.github.benmanes.caffeine.cache.Caffeine
   com.github.benmanes.caffeine.cache.RemovalListener
   com.github.benmanes.caffeine.cache.stats.CacheStats
   java.time.Duration
   java.util.concurrent.Executor
   java.util.function.Function))

(set! *warn-on-reflection* true)

(defprotocol ICache
  (get [_ k] [_ k load-fn] "get cache entry")
  (invalidate! [_] [_ k] "invalidate cache"))

(defprotocol ICacheStats
  (stats [_] "get stats"))

(defn- create-listener
  [f]
  (reify RemovalListener
    (onRemoval [_ key val cause]
      (when val
        (f key val cause)))))

(defn- get-stats
  [^Cache cache]
  (let [^CacheStats stats (.stats cache)]
    {:hit-rate (.hitRate stats)
     :hit-count (.hitCount stats)
     :req-count (.requestCount stats)
     :miss-count (.missCount stats)
     :miss-rate  (.missRate stats)}))

(defn create
  [& {:keys [executor on-remove max-size keepalive]}]
  (let [cache (as-> (Caffeine/newBuilder) builder
                (if (fn? on-remove) (.removalListener builder (create-listener on-remove)) builder)
                (if executor  (.executor builder ^Executor (px/resolve-executor executor)) builder)
                (if keepalive (.expireAfterAccess builder ^Duration (ct/duration keepalive)) builder)
                (if (int? max-size) (.maximumSize builder (long max-size)) builder)
                (.recordStats builder)
                (.buildAsync builder))
        cache (.synchronous ^AsyncCache cache)]
    (reify
      ICache
      (get [_ k]
        (.getIfPresent ^Cache cache ^Object k))
      (get [_ k load-fn]
        (.get ^Cache cache
              ^Object k
              ^Function (reify Function
                          (apply [_ k]
                            (load-fn k)))))
      (invalidate! [_]
        (.invalidateAll ^Cache cache))
      (invalidate! [_ k]
        (.invalidateAll ^Cache cache ^Object k))

      ICacheStats
      (stats [_]
        (get-stats cache)))))

(defn cache?
  [o]
  (satisfies? ICache o))

(sm/register!
 {:type ::cache
  :pred cache?
  :type-properties
  {:title "cache instance"}})

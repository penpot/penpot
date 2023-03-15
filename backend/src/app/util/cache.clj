;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.cache
  "In-memory cache backed by Caffeine"
  (:refer-clojure :exclude [get])
  (:require
   [app.util.time :as dt]
   [promesa.core :as p]
   [promesa.exec :as px])
  (:import
   com.github.benmanes.caffeine.cache.AsyncCache
   com.github.benmanes.caffeine.cache.AsyncLoadingCache
   com.github.benmanes.caffeine.cache.CacheLoader
   com.github.benmanes.caffeine.cache.Caffeine
   com.github.benmanes.caffeine.cache.RemovalListener
   java.time.Duration
   java.util.concurrent.Executor
   java.util.function.Function))

(set! *warn-on-reflection* true)

(defn create-listener
  [f]
  (reify RemovalListener
    (onRemoval [_ key val cause]
      (when val
        (f key val cause)))))

(defn create-loader
  [f]
  (reify CacheLoader
    (load [_ key]
      (f key))))

(defn create
  [& {:keys [executor on-remove load-fn keepalive]}]
  (as-> (Caffeine/newBuilder) builder
    (if on-remove (.removalListener builder (create-listener on-remove)) builder)
    (if executor (.executor builder ^Executor (px/resolve-executor executor)) builder)
    (if keepalive (.expireAfterAccess builder ^Duration (dt/duration keepalive)) builder)
    (if load-fn
      (.buildAsync builder ^CacheLoader (create-loader load-fn))
      (.buildAsync builder))))

(defn invalidate-all!
  [^AsyncCache cache]
  (.invalidateAll (.synchronous cache)))

(defn get
  ([cache key]
   (assert (instance? AsyncLoadingCache cache) "should be AsyncLoadingCache instance")
   (p/await! (.get ^AsyncLoadingCache cache ^Object key)))
  ([cache key not-found-fn]
   (assert (instance? AsyncCache cache) "should be AsyncCache instance")
   (p/await! (.get ^AsyncCache cache
                   ^Object key
                   ^Function (reify
                               Function
                               (apply [_ key]
                                 (not-found-fn key)))))))

(defn cache?
  [o]
  (or (instance? AsyncCache o)
      (instance? AsyncLoadingCache o)))

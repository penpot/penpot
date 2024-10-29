;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.helpers
  "General purpose RPC helpers."
  (:refer-clojure :exclude [with-meta])
  (:require
   [app.common.data.macros :as dm]
   [app.http :as-alias http]
   [app.rpc :as-alias rpc]
   [yetti.response :as-alias yres]))

;; A utilty wrapper object for wrap service responses that does not
;; implements the IObj interface that make possible attach metadata to
;; it.

(deftype MetadataWrapper [obj ^:unsynchronized-mutable metadata]
  clojure.lang.IDeref
  (deref [_] obj)

  clojure.lang.IObj
  (withMeta [_ meta]
    (MetadataWrapper. obj meta))

  (meta [_] metadata))

(defn wrap
  "Conditionally wrap a value into MetadataWrapper instance. If the
  object already implements IObj interface it will be returned as is."
  ([] (wrap nil))
  ([o]
   (if (instance? clojure.lang.IObj o)
     o
     (MetadataWrapper. o {})))
  ([o m]
   (if (instance? clojure.lang.IObj o)
     (vary-meta o merge m)
     (MetadataWrapper. o m))))

(defn wrapped?
  [o]
  (instance? MetadataWrapper o))

(defn unwrap
  [o]
  (if (wrapped? o) @o o))

(defn with-header
  "Add a http header to the RPC result."
  [mdw key val]
  (vary-meta mdw update ::http/headers assoc key val))

(defn with-transform
  "Adds a http response transform to the RPC result."
  [mdw transform-fn]
  (vary-meta mdw update ::rpc/response-transform-fns conj transform-fn))

(defn with-defer
  "Defer execution of the function until request is finished."
  [mdw hook-fn]
  (vary-meta mdw update ::rpc/before-complete-fns conj hook-fn))

(defn with-meta
  [mdw mdata]
  (vary-meta mdw merge mdata))

(defn assoc-meta
  [mdw k v]
  (vary-meta mdw assoc k v))

(defn with-http-cache
  [mdw max-age]
  (vary-meta mdw update ::rpc/response-transform-fns conj
             (fn [_ response]
               (let [exp (if (integer? max-age) max-age (inst-ms max-age))
                     val (dm/fmt "max-age=%" (int (/ exp 1000.0)))]
                 (update response ::yres/headers assoc "cache-control" val)))))

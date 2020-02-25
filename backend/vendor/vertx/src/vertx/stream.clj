;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019-2020 Andrey Antukh <niwi@niwi.nz>

(ns vertx.stream
  "A stream abstraction on top of core.async with awareness of vertx
  execution context."
  (:refer-clojure :exclude [loop])
  (:require
   [clojure.spec.alpha :as s]
   [clojure.core.async :as a]
   [clojure.core :as c]
   [promesa.core :as p]
   [vertx.impl :as impl]
   [vertx.util :as vu]))

;; --- Streams

(defmacro loop
  [& args]
  `(let [ctx# (vu/current-context)]
     (binding [p/*loop-run-fn* #(vu/run-on-context! ctx# %)]
       (p/loop ~@args))))

(defn stream
  ([] (a/chan))
  ([b] (a/chan b))
  ([b c] (a/chan b c))
  ([b c e] (a/chan b c e)))

(defn take!
  [c]
  (let [d (p/deferred)
        ctx (vu/current-context)]
    (a/take! c (fn [res]
                 (vu/run-on-context! ctx #(p/resolve! d res))))
    d))

(defn poll!
  [c]
  (a/poll! c))

(defn put!
  [c v]
  (let [d (p/deferred)
        ctx (vu/current-context)]
    (a/put! c v (fn [res]
                  (vu/run-on-context! ctx #(p/resolve! d res))))
    d))

(defn offer!
  [c v]
  (a/offer! c v))

(defn alts!
  ([ports] (alts! ports {}))
  ([ports opts]
   (let [d (p/deferred)
         ctx (vu/current-context)
         deliver #(vu/run-on-context! ctx (fn [] (p/resolve! d %)))
         ret (a/do-alts deliver ports opts)]
     (if ret
       (p/resolved @ret)
       d))))

(defn close!
  [c]
  (a/close! c))

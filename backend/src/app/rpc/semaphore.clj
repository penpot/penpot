;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.semaphore
  "Resource usage limits (in other words: semaphores)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.metrics :as mtx]
   [app.util.locks :as locks]
   [app.util.services :as-alias sv]
   [promesa.core :as p]))

(defprotocol IAsyncSemaphore
  (acquire! [_])
  (release! [_]))

(defn create
  [& {:keys [permits metrics name]}]
  (let [name   (d/name name)
        used   (volatile! 0)
        queue  (volatile! (d/queue))
        labels (into-array String [name])
        lock   (locks/create)]

    (reify IAsyncSemaphore
      (acquire! [_]
        (let [d (p/deferred)]
          (locks/locking lock
            (if (< @used permits)
              (do
                (vswap! used inc)
                (p/resolve! d))
              (vswap! queue conj d)))

          (mtx/run! metrics {:id :rpc-semaphore-used-permits :val @used :labels labels })
          (mtx/run! metrics {:id :rpc-semaphore-queued-submissions :val (count @queue) :labels labels})
          (mtx/run! metrics {:id :rpc-semaphore-acquires-total :inc 1 :labels labels})
          d))

      (release! [_]
        (locks/locking lock
          (if-let [item (peek @queue)]
            (do
              (vswap! queue pop)
              (p/resolve! item))
            (when (pos? @used)
              (vswap! used dec))))

        (mtx/run! metrics {:id :rpc-semaphore-used-permits :val @used :labels labels})
        (mtx/run! metrics {:id :rpc-semaphore-queued-submissions :val (count @queue) :labels labels})))))

(defn wrap
  [{:keys [metrics executors] :as cfg} f mdata]
  (if-let [permits (::permits mdata)]
    (let [sem (create {:permits permits
                       :metrics metrics
                       :name (::sv/name mdata)})]
      (l/debug :hint "wrapping semaphore" :handler (::sv/name mdata) :permits permits)
      (fn [cfg params]
        (-> (acquire! sem)
            (p/then (fn [_] (f cfg params)) (:default executors))
            (p/finally (fn [_ _] (release! sem))))))
    f))


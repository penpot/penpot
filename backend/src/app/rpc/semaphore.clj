;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.semaphore
  "Resource usage limits (in other words: semaphores)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.util.locks :as locks]
   [app.util.time :as ts]
   [app.worker :as-alias wrk]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]
   [promesa.core :as p]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ASYNC SEMAPHORE IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IAsyncSemaphore
  (acquire! [_])
  (release! [_ tp]))

(defn create
  [& {:keys [permits metrics name executor]}]
  (let [used    (volatile! 0)
        queue   (volatile! (d/queue))
        labels  (into-array String [(d/name name)])
        lock    (locks/create)
        permits (or permits Long/MAX_VALUE)]

    (when (>= permits Long/MAX_VALUE)
      (l/warn :hint "permits value too high" :permits permits :semaphore name))

    ^{::wrk/executor executor
      ::name name}
    (reify IAsyncSemaphore
      (acquire! [_]
        (let [d (p/deferred)]
          (locks/locking lock
            (if (< @used permits)
              (do
                (vswap! used inc)
                (p/resolve! d))
              (vswap! queue conj d)))

          (mtx/run! metrics
                    :id :semaphore-used-permits
                    :val @used
                    :labels labels)
          (mtx/run! metrics
                    :id :semaphore-queued-submissions
                    :val (count @queue)
                    :labels labels)
          d))

      (release! [_ tp]
        (locks/locking lock
          (if-let [item (peek @queue)]
            (do
              (vswap! queue pop)
              (p/resolve! item))
            (when (pos? @used)
              (vswap! used dec))))

        (mtx/run! metrics
                  :id :semaphore-timing
                  :val (inst-ms (tp))
                  :labels labels)
        (mtx/run! metrics
                  :id :semaphore-used-permits
                  :val @used
                  :labels labels)
        (mtx/run! metrics
                  :id :semaphore-queued-submissions
                  :val (count @queue)
                  :labels labels)))))

(defn semaphore?
  [v]
  (satisfies? IAsyncSemaphore v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PREDEFINED SEMAPHORES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/def ::semaphore semaphore?)
(s/def ::semaphores
  (s/map-of ::us/keyword ::semaphore))

(defmethod ig/pre-init-spec ::rpc/semaphores [_]
  (s/keys :req-un [::mtx/metrics]))

(defn- create-default-semaphores
  [metrics executor]
  [(create :permits (cf/get :semaphore-process-font)
           :metrics metrics
           :name :process-font
           :executor executor)
   (create :permits (cf/get :semaphore-update-file)
           :metrics metrics
           :name :update-file
           :executor executor)
   (create :permits (cf/get :semaphore-process-image)
           :metrics metrics
           :name :process-image
           :executor executor)
   (create :permits (cf/get :semaphore-auth)
           :metrics metrics
           :name :auth
           :executor executor)])

(defmethod ig/init-key ::rpc/semaphores
  [_ {:keys [metrics executor]}]
  (->> (create-default-semaphores metrics executor)
       (d/index-by (comp ::name meta))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmacro with-dispatch
  [queue & body]
  `(let [tpoint#   (ts/tpoint)
         queue#    ~queue
         executor# (-> queue# meta ::wrk/executor)]
     (-> (acquire! queue#)
         (p/then (fn [_#] ~@body) executor#)
         (p/finally (fn [_# _#]
                      (release! queue# tpoint#))))))

(defn wrap
  [{:keys [semaphores]} f {:keys [::queue]}]
  (let [queue' (get semaphores queue)]
    (if (semaphore? queue')
      (fn [cfg params]
        (with-dispatch queue'
          (f cfg params)))
      (do
        (when (some? queue)
          (l/warn :hint "undefined semaphore" :name queue))
        f))))

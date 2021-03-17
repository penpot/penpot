;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.tasks
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [app.worker]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(s/def ::name ::us/string)
(s/def ::delay
  (s/or :int ::us/integer
        :duration dt/duration?))
(s/def ::queue ::us/string)

(s/def ::task-options
  (s/keys :req-un [::name]
          :opt-un [::delay ::props ::queue]))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, clock_timestamp() + ?)
   returning id")

(defn submit!
  [conn {:keys [name delay props queue priority max-retries]
         :or {delay 0 props {} queue "default" priority 100 max-retries 3}
         :as options}]
  (us/verify ::task-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (db/tjson props)
        id        (uuid/next)]
    (log/debugf "submit task '%s' to be executed in '%s'" name (str duration))
    (db/exec-one! conn [sql:insert-new-task id name props queue priority max-retries interval])
    id))

(defn- instrument!
  [registry]
  (mtx/instrument-vars!
   [#'submit!]
   {:registry registry
    :type :counter
    :labels ["name"]
    :name "tasks_submit_total"
    :help "A counter of task submissions."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [conn params]
                  (let [tname (:name params)]
                    (mobj :inc [tname])
                    (origf conn params)))
                {::original origf})))})

  (mtx/instrument-vars!
   [#'app.worker/run-task]
   {:registry registry
    :type :summary
    :quantiles []
    :name "tasks_checkout_timing"
    :help "Latency measured between scheduld_at and execution time."
    :wrap (fn [rootf mobj]
            (let [mdata (meta rootf)
                  origf (::original mdata rootf)]
              (with-meta
                (fn [tasks item]
                  (let [now (inst-ms (dt/now))
                        sat (inst-ms (:scheduled-at item))]
                    (mobj :observe (- now sat))
                    (origf tasks item)))
                {::original origf})))}))

;; --- STATE INIT: REGISTRY

(s/def ::tasks
  (s/map-of keyword? fn?))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req-un [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [metrics tasks]}]
  (instrument! (:registry metrics))
  (let [mobj (mtx/create
              {:registry (:registry metrics)
               :type :summary
               :labels ["name"]
               :quantiles []
               :name "tasks_timing"
               :help "Background task execution timing."})]
    (reduce-kv (fn [res k v]
                 (let [tname (name k)]
                   (log/debugf "registring task '%s'" tname)
                   (assoc res tname (mtx/wrap-summary v mobj [tname]))))
               {}
               tasks)))

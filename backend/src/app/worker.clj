;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker
  "Async tasks abstraction (impl)."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASKS REGISTRY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wrap-with-metrics
  [f metrics tname]
  (let [labels (into-array String [tname])]
    (fn [params]
      (let [tp (dt/tpoint)]
        (try
          (f params)
          (finally
            (mtx/run! metrics
                      {:id :tasks-timing
                       :val (inst-ms (tp))
                       :labels labels})))))))

(s/def ::registry (s/map-of ::us/string fn?))
(s/def ::tasks (s/map-of keyword? fn?))

(defmethod ig/pre-init-spec ::registry [_]
  (s/keys :req [::mtx/metrics ::tasks]))

(defmethod ig/init-key ::registry
  [_ {:keys [::mtx/metrics ::tasks]}]
  (l/inf :hint "registry initialized" :tasks (count tasks))
  (reduce-kv (fn [registry k f]
               (let [tname (name k)]
                 (l/trc :hint "register task" :name tname)
                 (assoc registry tname (wrap-with-metrics f metrics tname))))
             {}
             tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-props
  [options]
  (let [cns (namespace ::sample)]
    (persistent!
     (reduce-kv (fn [res k v]
                  (cond-> res
                    (not= (namespace k) cns)
                    (assoc! k v)))
                (transient {})
                options))))

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, label, priority, max_retries, scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, now() + ?)
   returning id")

(def ^:private
  sql:remove-not-started-tasks
  "DELETE FROM task
    WHERE name=?
      AND queue=?
      AND label=?
      AND status = 'new'
      AND scheduled_at > now()")

(s/def ::label string?)
(s/def ::task (s/or :kw keyword? :str string?))
(s/def ::queue (s/or :kw keyword? :str string?))
(s/def ::delay (s/or :int integer? :duration dt/duration?))
(s/def ::conn (s/or :pool ::db/pool :connection some?))
(s/def ::priority integer?)
(s/def ::max-retries integer?)
(s/def ::dedupe boolean?)

(s/def ::submit-options
  (s/and
   (s/keys :req [::task ::conn]
           :opt [::label ::delay ::queue ::priority ::max-retries ::dedupe])
   (fn [{:keys [::dedupe ::label] :or {label ""}}]
     (if dedupe
       (not= label "")
       true))))

(defn submit!
  [& {:keys [::task ::delay ::queue ::priority ::max-retries ::conn ::dedupe ::label]
      :or {delay 0 queue :default priority 100 max-retries 3 label ""}
      :as options}]

  (us/verify! ::submit-options options)
  (let [duration  (dt/duration delay)
        interval  (db/interval duration)
        props     (-> options extract-props db/tjson)
        id        (uuid/next)
        tenant    (cf/get :tenant)
        task      (d/name task)
        queue     (str/ffmt "%:%" tenant (d/name queue))
        deleted   (when dedupe
                    (-> (db/exec-one! conn [sql:remove-not-started-tasks task queue label])
                        :next.jdbc/update-count))]
    (l/trc :hint "submit task"
           :name task
           :task-id (str id)
           :queue queue
           :label label
           :dedupe (boolean dedupe)
           :delay (dt/format-duration duration)
           :replace (or deleted 0))


    (db/exec-one! conn [sql:insert-new-task id task props queue
                        label priority max-retries interval])
    id))

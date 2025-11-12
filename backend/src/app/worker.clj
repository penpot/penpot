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
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.metrics :as mtx]
   [cuerdas.core :as str]
   [integrant.core :as ig]))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASKS REGISTRY
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IRegistry
  (get-task [_ name]))

(defn- wrap-with-metrics
  [f metrics tname]
  (let [labels (into-array String [tname])]
    (fn [params]
      (let [tp (ct/tpoint)]
        (try
          (f params)
          (finally
            (mtx/run! metrics
                      {:id :tasks-timing
                       :val (inst-ms (tp))
                       :labels labels})))))))

(def ^:private schema:tasks
  [:map-of :keyword ::sm/fn])

(def ^:private valid-tasks?
  (sm/validator schema:tasks))

(defmethod ig/assert-key ::registry
  [_ params]
  (assert (mtx/metrics? (::mtx/metrics params)) "expected valid metrics instance")
  (assert (valid-tasks? (::tasks params)) "expected a valid map of tasks"))

(defmethod ig/init-key ::registry
  [_ {:keys [::mtx/metrics ::tasks]}]
  (l/inf :hint "registry initialized" :tasks (count tasks))
  (let [tasks (reduce-kv (fn [registry k f]
                           (let [tname (name k)]
                             (l/trc :hint "register task" :name tname)
                             (assoc registry tname (wrap-with-metrics f metrics tname))))
                         {}
                         tasks)]
    (reify
      clojure.lang.Counted
      (count [_] (count tasks))

      IRegistry
      (get-task [_ name]
        (get tasks (d/name name))))))

(sm/register!
 {:type ::registry
  :pred #(satisfies? IRegistry %)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SUBMIT API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:insert-new-task
  "insert into task (id, name, props, queue, label, priority, max_retries, created_at, modified_at, scheduled_at)
   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
   returning id")

(def ^:private
  sql:remove-not-started-tasks
  "DELETE FROM task
    WHERE name=?
      AND queue=?
      AND label=?
      AND status = 'new'
      AND scheduled_at > ?")

(def ^:private schema:options
  [:map {:title "submit-options"}
   [::task [:or ::sm/text :keyword]]
   [::label {:optional true} ::sm/text]
   [::delay {:optional true}
    [:or ::sm/int ::ct/duration]]
   [::queue {:optional true} [:or ::sm/text :keyword]]
   [::priority {:optional true} ::sm/int]
   [::max-retries {:optional true} ::sm/int]
   [::dedupe {:optional true} ::sm/boolean]])

(def check-options!
  (sm/check-fn schema:options))

(defn submit!
  [& {:keys [::params ::task ::delay ::queue ::priority ::max-retries ::dedupe ::label]
      :or {delay 0 queue :default priority 100 max-retries 3 label ""}
      :as options}]

  (check-options! options)

  (let [delay        (ct/duration delay)
        now          (ct/now)
        scheduled-at (-> (ct/plus now delay)
                         (ct/truncate :millisecond))
        props        (db/tjson params)
        id           (uuid/next)
        tenant       (cf/get :tenant)
        task         (d/name task)
        queue        (str/ffmt "%:%" tenant (d/name queue))
        conn         (db/get-connectable options)
        deleted      (when dedupe
                       (-> (db/exec-one! conn [sql:remove-not-started-tasks task queue label now])
                           (db/get-update-count)))]

    (l/trc :hint "submit task"
           :name task
           :task-id (str id)
           :queue queue
           :label label
           :dedupe (boolean dedupe)
           :delay (ct/format-duration delay)
           :replace (or deleted 0))

    (db/exec-one! conn [sql:insert-new-task id task props queue
                        label priority max-retries
                        now now scheduled-at])

    id))

(defn invoke!
  [{:keys [::task ::params] :as cfg}]
  (assert (contains? cfg :app.worker/registry)
          "missing worker registry on `cfg`")
  (let [registry (get cfg ::registry)
        task-fn  (get-task registry task)]
    (task-fn {:props params})))

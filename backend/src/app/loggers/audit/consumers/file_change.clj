;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.file-change
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(def ^:private xf:filter-file-ids
  (comp
   (filter (fn [{:keys [name source]}]
             (and (= "backend" source)
                  (or (= name "update-file")
                      (= name "create-file-snapshot")))))
   (map consumers/decode-row)
   (filter (fn [row]
             (= "app" (-> row :context :initiator))))
   (keep (fn [{:keys [name props]}]
           (let [file-id (case name
                           "update-file"
                           (when (contains? #{"saved" "partial"} (:outcome props))
                             (:id props))

                           "create-file-snapshot"
                           (:file-id props))]
             (when (uuid? file-id)
               file-id))))))

(defn- process-chunk
  [cfg rows]
  (let [pending
        (into [] xf:filter-file-ids rows)

        submit-fragment-gc?
        (contains? cf/flags :feature-fdata-pointer-map)]

    (run! (fn [file-id]
            (jobs/submit cfg {::jobs/name :index-file-change-refs-scheduler
                              ::jobs/params {:file-id file-id}
                              ::jobs/label (str file-id)
                              ::jobs/dedupe true})
            (when submit-fragment-gc?
              (jobs/submit cfg {::jobs/name :file-fragments-gc
                                ::jobs/params {:file-id file-id}
                                ::jobs/label (str file-id)
                                ::jobs/delay (ct/duration {:minutes 10})
                                ::jobs/debounce true})))
          (distinct pending))

    {:processed (count pending)}))

(def consumer
  {:id      consumers/file-change-refs
   :always  true
   :label   "file-change-refs"
   :process process-chunk})

(defmethod ig/assert-key ::consumer
  [_ cfg]
  (assert (db/pool? (::db/pool cfg)) "expected a valid database pool")
  (assert (mtx/metrics? (::mtx/metrics cfg)) "expected a valid metrics instance"))

(defmethod ig/init-key ::consumer
  [_ cfg]
  (consumers/init cfg consumer))

(defmethod ig/halt-key! ::consumer
  [_ thread]
  (consumers/halt thread))

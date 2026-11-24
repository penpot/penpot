;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.loggers.audit.consumers.file-change
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.jobs :as jobs]
   [app.loggers.audit.consumers :as consumers]
   [app.metrics :as mtx]
   [integrant.core :as ig]))

(def ^:private xf:backend-event
  (filter #(= "backend" (get % :source))))

(def ^:private xf:decode-row
  (map consumers/decode-row))

(def ^:private xf:filter-initiator
  (filter #(= "app" (-> % :context :initiator))))

(defn- make-file-event-xf
  [event-name get-file-id]
  (comp
   xf:backend-event
   (filter #(= event-name (get % :name)))
   xf:decode-row
   xf:filter-initiator
   (map (comp get-file-id :props))
   (filter uuid?)))

(def ^:private xf:saved-file-ids
  (make-file-event-xf "update-file"
                      (fn [{:keys [id outcome]}]
                        (when (contains? #{"saved" "partial"} outcome)
                          id))))

(def ^:private xf:snapshot-file-ids
  (make-file-event-xf "create-file-snapshot" :file-id))

(defn- process-chunk
  [cfg rows]
  (let [saved-ids            (into #{} xf:saved-file-ids rows)
        snapshot-ids         (into #{} xf:snapshot-file-ids rows)
        snapshot-only-ids    (remove saved-ids snapshot-ids)
        fragment-gc-enabled? (contains? cf/flags :feature-fdata-pointer-map)]
    (doseq [file-id (into saved-ids snapshot-ids)]
      (jobs/submit cfg {::jobs/name :index-file-change-refs-scheduler
                        ::jobs/params {:file-id file-id}
                        ::jobs/label (str file-id)
                        ::jobs/dedupe true}))

    (when fragment-gc-enabled?
      (let [delay (cf/get-file-clean-delay)]
        (doseq [file-id saved-ids]
          (jobs/submit cfg {::jobs/name :file-fragments-gc
                            ::jobs/params {:file-id file-id}
                            ::jobs/label (str file-id)
                            ::jobs/delay delay
                            ::jobs/debounce true})))

      ;; A snapshot does not reset inactivity or move pending GC.
      (doseq [file-id snapshot-only-ids]
        (jobs/submit cfg {::jobs/name :file-fragments-gc
                          ::jobs/params {:file-id file-id}
                          ::jobs/label (str file-id)
                          ::jobs/delay (ct/duration "10m")
                          ::jobs/dedupe true})))

    (when (or (seq saved-ids) (seq snapshot-ids))
      (let [files-with-saves    (count saved-ids)
            snapshot-only-files (count snapshot-only-ids)]
        (db/after-commit
         #(l/dbg :hint "file change batch processed"
                 :files-with-saves files-with-saves
                 :snapshot-only-files snapshot-only-files
                 :fragment-gc-enabled fragment-gc-enabled?))))

    {:processed (count rows)}))

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

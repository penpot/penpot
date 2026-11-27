;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.index-file-change-refs
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.features.file-change-refs :as refs]
   [app.jobs :as jobs]
   [app.storage :as sto]
   [app.worker :as wrk]
   [integrant.core :as ig]))

(def ^:private retry-delay (ct/duration "1m"))

(defn execute
  "Index a single change in its own transaction."
  [cfg {:keys [file-id change-id]}]
  (try
    (let [indexed? (db/tx-run! cfg refs/index-change file-id change-id)]
      {:file-id file-id
       :change-id change-id
       :indexed indexed?})
    (catch Exception cause
      (let [{:keys [type code hint]} (ex-data cause)]
        (if (or (instance? InterruptedException cause)
                (= :interrupt type))
          (throw cause)
          (ex/raise :type ::wrk/retry
                    :code (or code :file-change-index-failed)
                    :hint (or hint "unable to index file change references")
                    :file-id file-id
                    :change-id change-id
                    :delay retry-delay
                    :cause cause))))))

(def ^:private schema:params
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:change-id ::sm/uuid]])

(def ^:private schema:result
  [:map {:closed true}
   [:file-id ::sm/uuid]
   [:change-id ::sm/uuid]
   [:indexed ::sm/boolean]])

(def ^:private valid-config?
  (sm/validator [:map ::db/pool ::sto/storage]))

(defmethod ig/init-key ::job-def
  [_ cfg]
  (assert (valid-config? cfg) "expected a database pool and storage")
  {::jobs/name          :index-file-change-refs
   ::jobs/schema        schema:params
   ::jobs/handler       (fn [_context params]
                          (execute cfg params))
   ::jobs/decoder       (sm/decoder schema:params sm/json-transformer)
   ::jobs/validator     (sm/validator schema:params)
   ::jobs/result-schema schema:result})

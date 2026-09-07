;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.tasks.upload-session-gc
  "A maintenance task that deletes stalled (incomplete) upload sessions.

  An upload session is considered stalled when it was created more than
  `max-age` ago without being completed (i.e. the session row still
  exists because `assemble-chunks` was never called to clean it up).
  The default max-age is 1 hour."
  (:require
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.db :as db]
   [app.jobs :as jobs]
   [integrant.core :as ig]))

(def ^:private sql:delete-stalled-sessions
  "DELETE FROM upload_session
    WHERE created_at < ?::timestamptz")

(def schema:upload-session-gc-params
  "Params map (no params needed; config-derived only)."
  [:map {:closed true}])

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (merge {::max-age (ct/duration {:hours 1})} v)})

(declare execute-upload-session-gc!)

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_]
    (execute-upload-session-gc! cfg)))

(defmethod ig/init-key ::upload-session-gc-job-def
  [_ cfg]
  {::jobs/name      :upload-session-gc
   ::jobs/schema    schema:upload-session-gc-params
   ::jobs/handler   (partial execute-upload-session-gc! cfg)
   ::jobs/decoder   (sm/decoder schema:upload-session-gc-params sm/json-transformer)
   ::jobs/validator (sm/validator schema:upload-session-gc-params)})

(defn execute-upload-session-gc!
  "Plain job handler: delete stalled upload sessions."
  ([cfg] (execute-upload-session-gc! cfg nil))
  ([cfg _params]
   (db/tx-run! cfg
               (fn [{:keys [::db/conn]}]
                 (let [threshold (ct/minus (ct/now) (::max-age cfg))
                       result    (-> (db/exec-one! conn [sql:delete-stalled-sessions threshold])
                                     (db/get-update-count))]
                   (l/debug :hint "task finished" :deleted result)
                   {:deleted result})))))

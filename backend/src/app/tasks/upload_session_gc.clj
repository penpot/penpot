;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.upload-session-gc
  "A maintenance task that deletes stalled (incomplete) upload sessions.

  An upload session is considered stalled when it was created more than
  `max-age` ago without being completed (i.e. the session row still
  exists because `assemble-chunks` was never called to clean it up).
  The default max-age is 1 hour."
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.db :as db]
   [integrant.core :as ig]))

(def ^:private sql:delete-stalled-sessions
  "DELETE FROM upload_session
    WHERE created_at < ?::timestamptz")

(defmethod ig/assert-key ::handler
  [_ params]
  (assert (db/pool? (::db/pool params)) "expected a valid database pool"))

(defmethod ig/expand-key ::handler
  [k v]
  {k (merge {::max-age (ct/duration {:hours 1})} v)})

(defmethod ig/init-key ::handler
  [_ {:keys [::max-age] :as cfg}]
  (fn [_]
    (db/tx-run! cfg
                (fn [{:keys [::db/conn]}]
                  (let [threshold (ct/minus (ct/now) max-age)
                        result    (-> (db/exec-one! conn [sql:delete-stalled-sessions threshold])
                                      (db/get-update-count))]
                    (l/debug :hint "task finished" :deleted result)
                    {:deleted result})))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-xlog-gc
  "A maintenance task that performs a garbage collection of the file
  change (transaction) log."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def ^:private
  sql:delete-files-xlog
  "delete from file_change
    where created_at < now() - ?::interval")

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (assoc cfg ::min-age (dt/duration {:hours 72})))

(defmethod ig/init-key ::handler
  [_ {:keys [::db/pool] :as cfg}]
  (fn [params]
    (let [min-age (or (:min-age params) (::min-age cfg))]
      (db/with-atomic [conn pool]
        (let [interval (db/interval min-age)
              result   (db/exec-one! conn [sql:delete-files-xlog interval])
              result   (db/get-update-count result)]

          (l/info :hint "task finished" :min-age (dt/format-duration min-age) :total result)

          (when (:rollback? params)
            (db/rollback! conn))

          result)))))

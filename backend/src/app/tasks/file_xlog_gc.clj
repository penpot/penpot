;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.tasks.file-xlog-gc
  "A maintenance task that performs a garbage collection of the file
  change (transaction) log."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(declare sql:delete-files-xlog)

(s/def ::min-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool]
          :opt-un [::min-age]))

(defmethod ig/prep-key ::handler
  [_ cfg]
  (merge {:min-age (dt/duration {:hours 72})}
         (d/without-nils cfg)))

(defmethod ig/init-key ::handler
  [_ {:keys [pool] :as cfg}]
  (fn [params]
    (let [min-age (or (:min-age params) (:min-age cfg))]
      (db/with-atomic [conn pool]
        (let [interval (db/interval min-age)
              result   (db/exec-one! conn [sql:delete-files-xlog interval])
              result   (:next.jdbc/update-count result)]
          (l/info :hint "task finished" :min-age (dt/format-duration min-age) :total result)

          (when (:rollback? params)
            (db/rollback! conn))

          result)))))

(def ^:private
  sql:delete-files-xlog
  "delete from file_change
    where created_at < now() - ?::interval")

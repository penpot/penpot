;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.tasks.file-xlog-gc
  "A maintenance task that performs a garbage collection of the file
  change (transaction) log."
  (:require
   [app.db :as db]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]))

(declare sql:delete-files-xlog)

(s/def ::max-age ::dt/duration)

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req-un [::db/pool ::max-age]))

(defmethod ig/init-key ::handler
  [_ {:keys [pool max-age] :as cfg}]
  (fn [_]
    (db/with-atomic [conn pool]
      (let [interval (db/interval max-age)
            result   (db/exec-one! conn [sql:delete-files-xlog interval])
            result   (:next.jdbc/update-count result)]
        (log/debugf "removed %s rows from file-change table" result)
        result))))

(def ^:private
  sql:delete-files-xlog
  "delete from file_change
    where created_at < now() - ?::interval")

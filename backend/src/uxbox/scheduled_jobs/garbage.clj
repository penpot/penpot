;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.scheduled-jobs.garbage
  "Garbage Collector related tasks."
  (:require [suricatta.core :as sc]
            [uxbox.db :as db]
            [uxbox.util.quartz :as qtz]))

;; --- Delete projects

;; TODO: move inline sql into resources/sql directory

(defn clean-deleted-projects
  "Task that cleans the deleted projects."
  {::qtz/repeat? true
   ::qtz/interval (* 1000 3600 24)
   ::qtz/job true}
  []
  (with-open [conn (db/connection)]
    (sc/atomic conn
      (let [sql (str "DELETE FROM projects "
                     " WHERE deleted_at is not null AND "
                     "       (now()-deleted_at)::interval > '10 day'::interval;")]
        (sc/execute conn sql)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.jobs.gc
  (:require
   [promesa.core :as p]
   [uxbox.core :refer [system]]
   [uxbox.db :as db]
   [uxbox.util.jobs :as uj]
   [mount.core :as mount :refer [defstate]]))

;; TODO: add images-gc
;; TODO: add icons-gc
;; TODO: add pages-gc

;; --- Delete Projects

(def ^:private clean-deleted-projects-sql
  "DELETE FROM projects
    WHERE deleted_at is not null
      AND (now()-deleted_at)::interval > '10 day'::interval
    RETURNING id;")

(defn clean-deleted-projects
  "Clean deleted projects."
  [opts]
  (db/with-atomic [conn db/pool]
    (-> (db/query-one conn clean-deleted-projects-sql)
        (p/then (constantly nil)))))

(defstate projects-cleaner-task
  :start (uj/schedule! system #'clean-deleted-projects {::uj/interval 3600000})) ;; 1h



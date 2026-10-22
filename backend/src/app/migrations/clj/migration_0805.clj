;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.migrations.clj.migration-0805
  "Fill `file_branch_change.changes_count` from the stored change vectors
  and make it required."
  (:require
   [app.common.logging :as l]
   [app.db :as db]
   [app.util.blob :as blob]))

(def ^:private sql:get-rows
  "SELECT id, changes FROM file_branch_change ORDER BY created_at")

(defn- stamp-count!
  [conn {:keys [id changes]}]
  (db/update! conn :file-branch-change
              {:changes-count (count (blob/decode changes))}
              {:id id}
              {::db/return-keys false}))

(defn migrate
  [conn]
  (let [rows (db/plan conn [sql:get-rows])
        total (reduce (fn [total row]
                        (stamp-count! conn row)
                        (inc total))
                      0
                      rows)]
    (l/inf :hint "stamped op log row counts" :rows total)
    ;; `NOT NULL` is the guard on the writers: an insert that omits the
    ;; count fails rather than reading as a row that holds nothing.
    (db/exec! conn ["ALTER TABLE file_branch_change
                       ALTER COLUMN changes_count SET NOT NULL
                     , ALTER COLUMN changes_count DROP DEFAULT"])))

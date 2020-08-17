;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.tasks.gc
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [cuerdas.core :as str]
   [postal.core :as postal]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.config :as cfg]
   [uxbox.db :as db]
   [uxbox.tasks :as tasks]
   [uxbox.media-storage :as mst]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Remove deleted media
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The main purpose of this task is analize the `pending_to_delete`
;; table. This table stores the references to the physical files on
;; the file system thanks to `handle_delete()` trigger.

;; Example:
;; (1) You delete an media-object. (2) This media object is marked as
;; deleted. (3) A task (`delete-object`) is scheduled for permanent
;; delete the object.  - If that object stores media, the database
;; will execute the `handle_delete()` trigger which will place
;; filesystem paths into the `pendint_to_delete` table. (4) This
;; task (`remove-deleted-media`) permanently delete the file from the
;; filesystem when is executed (by scheduler).

(def ^:private
  sql:retrieve-peding-to-delete
  "with items_part as (
     select i.id
       from pending_to_delete as i
      order by i.created_at
      limit ?
      for update skip locked
   )
   delete from pending_to_delete
    where id in (select id from items_part)
   returning *")

(defn remove-deleted-media
  [{:keys [props] :as task}]
  (letfn [(decode-row [{:keys [data] :as row}]
            (cond-> row
              (db/pgobject? data) (assoc :data (db/decode-pgobject data))))
          (retrieve-items [conn]
            (->> (db/exec! conn [sql:retrieve-peding-to-delete 10])
                 (map decode-row)
                 (map :data)))
          (remove-media [rows]
            (run! (fn [item]
                    (let [path (get item "path")]
                      (ust/delete! mst/media-storage path)))
                  rows))]
    (loop []
      (let [rows (retrieve-items db/pool)]
        (when-not (empty? rows)
          (remove-media rows)
          (recur))))))



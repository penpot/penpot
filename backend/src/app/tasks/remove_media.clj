;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.tasks.remove-media
  "TODO: pending to be refactored together with the storage
  subsystem."
  (:require
   [app.common.spec :as us]
   [app.db :as db]
   ;; [app.media-storage :as mst]
   ;; [app.metrics :as mtx]
   ;; [app.util.storage :as ust]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Remove Media
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Task responsible of explicit action of removing a media from file
;; system. Mainly used for profile photo change; when we really know
;; that the previous photo becomes unused.

;; (s/def ::path ::us/not-empty-string)
;; (s/def ::props
;;   (s/keys :req-un [::path]))

;; (defn handler
;;   [{:keys [props] :as task}]
;;   (us/verify ::props props)
;;   (when (ust/exists? mst/media-storage (:path props))
;;     (ust/delete! mst/media-storage (:path props))
;;     (log/debug "Media " (:path props) " removed.")))

;; (mtx/instrument-with-summary!
;;  {:var #'handler
;;   :id "tasks__remove_media"
;;   :help "Timing of remove-media task."})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task: Trim Media Storage
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

;; (def ^:private
;;   sql:retrieve-peding-to-delete
;;   "with items_part as (
;;      select i.id
;;        from pending_to_delete as i
;;       order by i.created_at
;;       limit ?
;;       for update skip locked
;;    )
;;    delete from pending_to_delete
;;     where id in (select id from items_part)
;;    returning *")

;; (defn trim-media-storage
;;   [_task]
;;   (letfn [(decode-row [{:keys [data] :as row}]
;;             (cond-> row
;;               (db/pgobject? data) (assoc :data (db/decode-json-pgobject data))))
;;           (retrieve-items [conn]
;;             (->> (db/exec! conn [sql:retrieve-peding-to-delete 10])
;;                  (map decode-row)
;;                  (map :data)))
;;           (remove-media [rows]
;;             (run! (fn [item]
;;                     (let [path (get item "path")]
;;                       (ust/delete! mst/media-storage path)))
;;                   rows))]
;;     (loop []
;;       (let [rows (retrieve-items db/pool)]
;;         (when-not (empty? rows)
;;           (remove-media rows)
;;           (recur))))))

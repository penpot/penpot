;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

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
   [uxbox.media-storage :as mst]
   [uxbox.util.blob :as blob]
   [uxbox.util.storage :as ust]))

(def ^:private sql:delete-items
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

(defn- impl-remove-media
  [result]
  (run! (fn [item]
          (let [path1 (get item "path")
                path2 (get item "thumb_path")]
            (ust/delete! mst/media-storage path1)
            (ust/delete! mst/media-storage path2)))
        result))

(defn- decode-row
  [{:keys [data] :as row}]
  (cond-> row
    (db/pgobject? data) (assoc :data (db/decode-pgobject data))))

(defn- get-items
  [conn]
  (->> (db/exec! conn [sql:delete-items 10])
       (map decode-row)
       (map :data)))

(defn remove-media
  [{:keys [props] :as task}]
  (db/with-atomic [conn db/pool]
    (loop [result (get-items conn)]
      (when-not (empty? result)
        (impl-remove-media result)
        (recur (get-items conn))))))


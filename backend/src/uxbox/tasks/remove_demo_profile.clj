;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.tasks.remove-demo-profile
  "Demo accounts garbage collector."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [promesa.core :as p]
   [uxbox.common.exceptions :as ex]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.media :as media]
   [uxbox.util.storage :as ust]
   [vertx.util :as vu]))

(declare remove-file-images)
(declare remove-images)
(declare remove-profile)

(s/def ::id ::us/uuid)
(s/def ::props
  (s/keys :req-un [::id]))

(defn handler
  [{:keys [props] :as task}]
  (us/verify ::props props)
  (db/with-atomic [conn db/pool]
    (remove-file-images conn (:id props))
    (remove-images conn (:id props))
    (remove-profile conn (:id props))))

(defn- remove-files
  [files]
  (doseq [item files]
    (ust/delete! media/media-storage (:path item))
    (ust/delete! media/media-storage (:thumb-path item)))
  files)

(def ^:private sql:delete-file-images
  "with images_part as (
     select pfi.id
       from project_file_images as pfi
      inner join project_files as pf on (pf.id = pfi.file_id)
      inner join projects as p on (p.id = pf.project_id)
      where p.user_id = $1
      limit 10
   )
   delete from project_file_images
    where id in (select id from images_part)
   returning id, path, thumb_path")

(defn remove-file-images
  [conn id]
  (vu/loop []
    (-> (db/query conn [sql:delete-file-images id])
        (p/then (vu/wrap-blocking remove-files))
        (p/then (fn [images]
                  (when (not (empty? images))
                    (p/recur)))))))

(def ^:private sql:delete-images
  "with images_part as (
     select img.id
       from images as img
      where img.user_id = $1
      limit 10
   )
   delete from images
    where id in (select id from images_part)
   returning id, path, thumb_path")

(defn- remove-images
  [conn id]
  (vu/loop []
    (-> (db/query conn [sql:delete-images id])
        (p/then (vu/wrap-blocking remove-files))
        (p/then (fn [images]
                  (when (not (empty? images))
                    (p/recur)))))))

(defn remove-profile
  [conn id]
  (let [sql "delete from users where id=$1"]
    (db/query conn [sql id])))



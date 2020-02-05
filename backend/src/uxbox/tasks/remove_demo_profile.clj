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
  (prn "handler" props (.getName (Thread/currentThread)))
  (db/with-atomic [conn db/pool]
    (remove-file-images conn (:id props))
    (remove-images conn (:id props))
    (remove-profile conn (:id props))
    (prn "finished" (.getName (Thread/currentThread)))))

(def ^:private sql:file-images-to-delete
  "select pfi.id, pfi.path, pfi.thumb_path
     from project_file_images as pfi
    inner join project_files as pf on (pf.id = pfi.file_id)
    inner join projects as p on (p.id = pf.project_id)
    where p.user_id = $1
    limit 2")

(defn remove-file-images
  [conn id]
  (p/loop []
    (p/let [files (db/query conn [sql:file-images-to-delete id])]
      (prn "remove-file-images" files)
      (when-not (empty? files)
        (-> (vu/blocking
             (doseq [item files]
               (ust/delete! media/media-storage (:path item))
               (ust/delete! media/media-storage (:thumb-path item))))
            (p/then' #(p/recur)))))))

(def ^:private sql:images
  "select img.id, img.path, img.thumb_path
     from images as img
    where img.user_id = $1
    limit 5")

(defn remove-files
  [files]
  (prn "remove-files" (.getName (Thread/currentThread)))
  (doseq [item files]
    (ust/delete! media/media-storage (:path item))
    (ust/delete! media/media-storage (:thumb-path item)))
  files)

(defn remove-images
  [conn id]
  (prn "remove-images" (.getName (Thread/currentThread)))
  (vu/loop [i 0]
    (prn "remove-images loop" i (.getName (Thread/currentThread)))
    (-> (db/query conn [sql:images id])
        (p/then (vu/wrap-blocking remove-files))
        (p/then (fn [images]
                  (prn "ending" (.getName (Thread/currentThread)))
                  (when (and (not (empty? images))
                             (< i 1000))
                    (p/recur (inc i))))))))

(defn remove-profile
  [conn id]
  (let [sql "delete from users where id=$1"]
    (db/query conn [sql id])))



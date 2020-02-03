;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.media-loader
  "Media collections importer (command line helper)."
  (:require
   [clojure.tools.logging :as log]
   [clojure.spec.alpha :as s]
   [clojure.pprint :refer [pprint]]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [promesa.core :as p]
   [mount.core :as mount]
   [datoteka.core :as fs]
   [cuerdas.core :as str]
   [uxbox.config]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.http]
   [uxbox.migrations]
   [uxbox.media :as media]
   [uxbox.util.svg :as svg]
   [uxbox.util.transit :as t]
   [uxbox.util.blob :as blob]
   [uxbox.util.uuid :as uuid]
   [uxbox.util.data :as data]
   [uxbox.services.mutations.images :as images]
   [uxbox.util.storage :as ust])
  (:import
   java.io.Reader
   java.io.PushbackReader
   org.im4java.core.Info))

;; --- Constants & Helpers

(def ^:const +images-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6e")
(def ^:const +icons-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6f")

(s/def ::name ::us/string)
(s/def ::path ::us/string)
(s/def ::regex #(instance? java.util.regex.Pattern %))
(s/def ::import-item
  (s/keys :req-un [::name ::path ::regex]))

(defn exit!
  ([] (exit! 0))
  ([code]
   (System/exit code)))

;; --- Icons Collections Importer

(defn- create-icons-collection
  "Create or replace icons collection by its name."
  [conn {:keys [name] :as item}]
  (log/info "Creating or updating icons collection:" name)
  (let [id (uuid/namespaced +icons-uuid-ns+ name)
        sql "insert into icon_collections (id, user_id, name)
             values ($1, '00000000-0000-0000-0000-000000000000'::uuid, $2)
                 on conflict (id)
                 do update set name = $2
             returning *;"
        sqlv [sql id name]]
    (-> (db/query-one conn [sql id name])
        (p/then' (constantly id)))))

(def sql:create-icon
  "insert into icons (user_id, id, collection_id, name, metadata, content)
   values ('00000000-0000-0000-0000-000000000000'::uuid, $1, $2, $3, $4, $5)
       on conflict (id)
       do update set name = $3,
                     metadata = $4,
                     content = $5,
                     collection_id = $2,
                     user_id = '00000000-0000-0000-0000-000000000000'::uuid
   returning *;")

(defn- create-or-update-icon
  [conn id icon-id localpath]
  (s/assert fs/path? localpath)
  (s/assert ::us/uuid id)
  (s/assert ::us/uuid icon-id)
  (let [filename (fs/name localpath)
        extension (second (fs/split-ext filename))
        data (svg/parse localpath)
        mdata (select-keys data [:width :height :view-box])]
    (db/query-one conn [sql:create-icon
                        icon-id
                        id
                        (:name data filename)
                        (blob/encode mdata)
                        (:content data)])))

(defn- import-icon
  [conn id fpath]
  (s/assert ::us/uuid id)
  (s/assert fs/path? fpath)
  (let [filename (fs/name fpath)
        icon-id (uuid/namespaced +icons-uuid-ns+ (str id filename))]
    (log/info "Creating or updating icon" filename icon-id)
    (-> (create-or-update-icon conn id icon-id fpath)
        (p/then (constantly nil)))))

(defn- import-icons
  [conn coll-id {:keys [path regex] :as item}]
  (p/run! (fn [fpath]
            (when (re-matches regex (str fpath))
              (import-icon conn coll-id fpath)))
          (->> (fs/list-dir path)
               (filter fs/regular-file?))))

(defn- process-icons-collection
  [conn basedir {:keys [path regex] :as item}]
  (s/assert ::import-item item)
  (-> (create-icons-collection conn item)
      (p/then (fn [coll-id]
                (->> (assoc item :path (fs/join basedir path))
                     (import-icons conn coll-id))))))

;; --- Images Collections Importer

(defn- create-images-collection
  "Create or replace image collection by its name."
  [conn {:keys [name] :as item}]
  (log/info "Creating or updating image collection:" name)
  (let [id (uuid/namespaced +images-uuid-ns+ name)
        user uuid/zero
        sql "insert into image_collections (id, user_id, name)
             values ($1, $2, $3)
             on conflict (id) do nothing
             returning *;"]
    (-> (db/query-one db/pool [sql id user name])
        (p/then (constantly id)))))

(defn- image-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from images as i
              where i.id = $1 and i.user_id = '00000000-0000-0000-0000-000000000000'::uuid"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))

(defn- create-image
  [conn id image-id localpath]
  (s/assert fs/path? localpath)
  (s/assert ::us/uuid id)
  (s/assert ::us/uuid image-id)
  (let [filename (fs/name localpath)
        extension (second (fs/split-ext filename))
        file (io/as-file localpath)
        mtype (case extension
                ".jpg"  "image/jpeg"
                ".png"  "image/png"
                ".webp" "image/webp")]

    (images/create-image conn {:content {:path localpath
                                         :name filename
                                         :mtype mtype
                                         :size (.length file)}
                               :id image-id
                               :collection-id id
                               :user uuid/zero
                               :name filename})))

(defn- import-image
  [conn id fpath]
  (s/assert ::us/uuid id)
  (s/assert fs/path? fpath)
  (let [filename (fs/name fpath)
        image-id (uuid/namespaced +images-uuid-ns+ (str id filename))]
    (-> (image-exists? conn image-id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (log/info "Creating image" filename image-id)
                    (create-image conn id image-id fpath))))
        (p/then (constantly nil)))))

(defn- import-images
  [conn coll-id {:keys [path regex] :as item}]
  (p/run! (fn [fpath]
            (when (re-matches regex (str fpath))
              (import-image conn coll-id fpath)))
          (->> (fs/list-dir path)
               (filter fs/regular-file?))))

(defn- process-images-collection
  [conn basedir {:keys [path regex] :as item}]
  (s/assert ::import-item item)
  (-> (create-images-collection conn item)
      (p/then (fn [coll-id]
                (->> (assoc item :path (fs/join basedir path))
                     (import-images conn coll-id))))))

;; --- Entry Point

(defn- validate-path
  [path]
  (when-not path
    (log/error "No path is provided")
    (exit! -1))
  (when-not (fs/exists? path)
    (log/error "Path does not exists.")
    (exit! -1))
  (when (fs/directory? path)
    (log/error "The provided path is a directory.")
    (exit! -1))
  (fs/path path))

(defn- read-file
  [path]
  (let [path (validate-path path)
        reader (java.io.PushbackReader. (io/reader path))]
    [(fs/parent path)
     (read reader)]))

(defn- start-system
  []
  (-> (mount/except #{#'uxbox.http/server})
      (mount/start)))

(defn- stop-system
  []
  (mount/stop))

(defn- importer
  [conn basedir data]
  (let [images (:images data)
        icons (:icons data)]
    (p/do!
     (p/run! #(process-images-collection conn basedir %) images)
     (p/run! #(process-icons-collection conn basedir %) icons))))

(defn -main
  [& [path]]
  (let [[basedir data] (read-file path)]
    (start-system)
    (-> (db/with-atomic [conn db/pool]
          (importer conn basedir data))
        (p/finally (fn [_ _]
                     (stop-system))))))

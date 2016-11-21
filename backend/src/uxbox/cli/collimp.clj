;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.cli.collimp
  "Collection importer command line helper."
  (:require [clojure.spec :as s]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [mount.core :as mount]
            [cuerdas.core :as str]
            [suricatta.core :as sc]
            [storages.core :as st]
            [storages.util :as fs]
            [uxbox.config]
            [uxbox.db :as db]
            [uxbox.migrations]
            [uxbox.media :as media]
            [uxbox.cli.sql :as sql]
            [uxbox.services.svgparse :as svg]
            [uxbox.util.transit :as t]
            [uxbox.util.spec :as us]
            [uxbox.util.cli :as cli]
            [uxbox.util.blob :as blob]
            [uxbox.util.uuid :as uuid]
            [uxbox.util.data :as data])
  (:import [java.io Reader PushbackReader]
           [org.im4java.core Info]))

;; --- Constants & Helpers

(def ^:const +images-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6e")
(def ^:const +icons-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6f")

(s/def ::name string?)
(s/def ::path string?)
(s/def ::regex us/regex?)

(s/def ::import-entry
  (s/keys :req-un [::name ::path ::regex]))

(defn- pushback-reader
  [reader]
  (PushbackReader. ^Reader reader))

;; --- Icons Collections Importer

(defn- create-icons-collection
  "Create or replace image collection by its name."
  [conn {:keys [name] :as entry}]
  (println "Creating or updating icons collection:" name)
  (let [id (uuid/namespaced +icons-uuid-ns+ name)
        sqlv (sql/create-icons-collection {:id id :name name})]
    (sc/execute conn sqlv)
    id))

(defn- retrieve-icon
  [conn id]
  {:pre [(uuid? id)]}
  (let [sqlv (sql/get-icon {:id id})]
    (some->> (sc/fetch-one conn sqlv)
             (data/normalize-attrs))))

(defn- create-icon
  [conn collid iconid localpath]
  {:pre [(fs/path? localpath)
         (uuid? collid)
         (uuid? iconid)]}
  (let [filename (fs/base-name localpath)
        extension (second (fs/split-ext filename))
        data (svg/parse localpath)
        params {:name (:name data filename)
                :content (:content data)
                :metadata (-> {:width (:width data)
                               :height (:height data)
                               :view-box (:view-box data)}
                              t/encode blob/encode)
                :collection collid
                :id iconid}
        sqlv (sql/create-icon params)]
    (sc/execute conn sqlv)))

(defn- import-icon
  [conn id fpath]
  {:pre [(uuid? id) (fs/path? fpath)]}
  (let [filename (fs/base-name fpath)
        iconid (uuid/namespaced +icons-uuid-ns+ (str id filename))]
    (when-not (retrieve-icon conn iconid)
      (println "Importing icon:" (str fpath))
      (create-icon conn id iconid fpath))))

(defn- process-icons-entry
  [conn basedir {:keys [path regex] :as entry}]
  {:pre [(us/valid? ::import-entry entry)]}
  (let [id (create-icons-collection conn entry)
        path (fs/resolve basedir path)]
    (doseq [fpath (fs/list-files path)]
      (when (re-matches regex (str fpath))
        (import-icon conn id fpath)))))

;; --- Images Collections Importer

(defn- create-images-collection
  "Create or replace image collection by its name."
  [conn {:keys [name] :as entry}]
  (println "Creating or updating image collection:" name)
  (let [id (uuid/namespaced +images-uuid-ns+ name)
        sqlv (sql/create-images-collection {:id id :name name})]
    (sc/execute conn sqlv)
    id))

(defn- retrieve-image-size
  [path]
  (let [info (Info. (str path) true)]
    [(.getImageWidth info) (.getImageHeight info)]))

(defn- retrieve-image
  [conn id]
  {:pre [(uuid? id)]}
  (let [sqlv (sql/get-image {:id id})]
    (some->> (sc/fetch-one conn sqlv)
             (data/normalize-attrs))))

(defn- delete-image
  [conn {:keys [id path] :as image}]
  {:pre [(uuid? id)
         (fs/path? path)]}
  (let [sqlv (sql/delete-image {:id id})
        storage media/images-storage]
    @(st/delete storage path)
    (sc/execute conn sqlv)))

(defn- create-image
  [conn collid imageid localpath]
  {:pre [(fs/path? localpath)
         (uuid? collid)
         (uuid? imageid)]}
  (let [filename (fs/base-name localpath)
        storage media/images-storage
        [width height] (retrieve-image-size localpath)
        extension (second (fs/split-ext filename))
        path @(st/save storage filename localpath)
        params {:name filename
                :path (str path)
                :mimetype (case extension
                            ".jpg" "image/jpeg"
                            ".png" "image/png")
                :width width
                :height height
                :collection collid
                :id imageid}
        sqlv (sql/create-image params)]
    (sc/execute conn sqlv)))

(defn- import-image
  [conn id fpath]
  {:pre [(uuid? id) (fs/path? fpath)]}
  (let [filename (fs/base-name fpath)
        imageid (uuid/namespaced +images-uuid-ns+ (str id filename))]
    (when-not (retrieve-image conn imageid)
      (println "Importing image:" (str fpath))
      (create-image conn id imageid fpath))))

    #_(if-let [image (retrieve-image conn imageid)]
      (do
        (delete-image conn image)
        (create-image conn id imageid fpath))
      (create-image conn id imageid fpath))

(defn- process-images-entry
  [conn basedir {:keys [path regex] :as entry}]
  {:pre [(us/valid? ::import-entry entry)]}
  (let [id (create-images-collection conn entry)
        path (fs/resolve basedir path)]
    (doseq [fpath (fs/list-files path)]
      (when (re-matches regex (str fpath))
        (import-image conn id fpath)))))

;; --- Entry Point

(defn- check-path!
  [path]
  (when-not path
    (cli/print-err! "No path is provided.")
    (cli/exit! -1))
  (when-not (fs/exists? path)
    (cli/print-err! "Path does not exists.")
    (cli/exit! -1))
  (when (fs/directory? path)
    (cli/print-err! "The provided path is a directory.")
    (cli/exit! -1))
  (fs/path path))

(defn- read-import-file
  [path]
  (let [path (check-path! path)
        parent (fs/parent path)
        reader (pushback-reader (io/reader path))]
    [parent (read reader)]))

(defn- start-system
  []
  (mount/start))

(defn- stop-system
  []
  (mount/stop))

(defn- run-importer
  [conn basedir data]
  (let [images (:images data)
        icons (:icons data)]
    (run! #(process-images-entry conn basedir %) images)
    (run! #(process-icons-entry conn basedir %) icons)
    #_(throw (ex-info "" {}))))

(defn -main
  [& [path]]
  (let [[basedir data] (read-import-file path)]
    (start-system)
    (try
      (with-open [conn (db/connection)]
        (sc/apply-atomic conn run-importer basedir data))
      (finally
        (stop-system)))))

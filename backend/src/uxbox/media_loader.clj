;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2016-2020 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.media-loader
  "Media libraries importer (command line helper)."
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
   [uxbox.common.uuid :as uuid]
   [uxbox.util.data :as data]
   [uxbox.services.mutations.colors :as colors]
   [uxbox.services.mutations.icons :as icons]
   [uxbox.services.mutations.images :as images]
   [uxbox.util.storage :as ust])
  (:import
   java.io.Reader
   java.io.PushbackReader
   org.im4java.core.Info))

;; --- Constants & Helpers

(def ^:const +images-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6a")
(def ^:const +icons-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6b")
(def ^:const +colors-uuid-ns+ #uuid "3642a582-565f-4070-beba-af797ab27a6c")

(s/def ::id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::path ::us/string)
(s/def ::regex #(instance? java.util.regex.Pattern %))

(s/def ::colors (s/every ::us/color :kind set?))

(s/def ::import-item-media
  (s/keys :req-un [::name ::path ::regex]))

(s/def ::import-item-color
  (s/keys :req-un [::name ::id ::colors]))

(defn exit!
  ([] (exit! 0))
  ([code]
   (System/exit code)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Icons Libraries Importer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- icon-library-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from icon_library where id = $1"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))


(defn- create-icons-library
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +icons-uuid-ns+ name)]
    (log/info "Creating icons library:" name)
    (icons/create-library conn {:team-id uuid/zero
                                :id id
                                :name name})))

(defn- create-icons-library-if-not-exists
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +icons-uuid-ns+ name)]
    (-> (icon-library-exists? conn id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (create-icons-library conn item))))
        (p/then (constantly id)))))

(defn- create-icon
  [conn library-id icon-id localpath]
  (s/assert fs/path? localpath)
  (s/assert ::us/uuid library-id)
  (s/assert ::us/uuid icon-id)
  (let [filename (fs/name localpath)
        extension (second (fs/split-ext filename))
        data (svg/parse localpath)
        mdata (select-keys data [:width :height :view-box])]

    (log/info "Creating or updating icon" filename icon-id)
    (icons/create-icon conn {:id icon-id
                             :library-id library-id
                             :name (:name data filename)
                             :content (:content data)
                             :metadata mdata})))

(defn- icon-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from icon where id = $1"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))

(defn- import-icon-if-not-exists
  [conn library-id fpath]
  (s/assert ::us/uuid library-id)
  (s/assert fs/path? fpath)
  (let [icon-id (uuid/namespaced +icons-uuid-ns+ (str library-id (fs/name fpath)))]
    (-> (icon-exists? conn icon-id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (create-icon conn library-id icon-id fpath))))
        (p/then (constantly icon-id)))))

(defn- import-icons
  [conn library-id {:keys [path regex] :as item}]
  (p/run! (fn [fpath]
            (when (re-matches regex (str fpath))
              (import-icon-if-not-exists conn library-id fpath)))
          (->> (fs/list-dir path)
               (filter fs/regular-file?))))

(defn- process-icons-library
  [conn basedir {:keys [path regex] :as item}]
  (s/assert ::import-item-media item)
  (-> (create-icons-library-if-not-exists conn item)
      (p/then (fn [library-id]
                (->> (assoc item :path (fs/join basedir path))
                     (import-icons conn library-id))))))


;; --- Images Libraries Importer

(defn- image-library-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from image_library where id = $1"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))

(defn- create-images-library
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +images-uuid-ns+ name)]
    (log/info "Creating image library:" name)
    (images/create-library conn {:id id
                                 :team-id uuid/zero
                                 :name name})))


(defn- create-images-library-if-not-exists
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +images-uuid-ns+ name)]
    (-> (image-library-exists? conn id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (create-images-library conn item))))
        (p/then (constantly id)))))


(defn- create-image
  [conn library-id image-id localpath]
  (s/assert fs/path? localpath)
  (s/assert ::us/uuid library-id)
  (s/assert ::us/uuid image-id)
  (let [filename (fs/name localpath)
        extension (second (fs/split-ext filename))
        file (io/as-file localpath)
        mtype (case extension
                ".jpg"  "image/jpeg"
                ".png"  "image/png"
                ".webp" "image/webp")]
    (log/info "Creating image" filename image-id)
    (images/create-image conn {:content {:path localpath
                                         :name filename
                                         :mtype mtype
                                         :size (.length file)}
                               :id image-id
                               :library-id library-id
                               :user uuid/zero
                               :name filename})))

(defn- image-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from image where id = $1"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))

(defn- import-image-if-not-exists
  [conn library-id fpath]
  (s/assert ::us/uuid library-id)
  (s/assert fs/path? fpath)
  (let [image-id (uuid/namespaced +images-uuid-ns+ (str library-id (fs/name fpath)))]
    (-> (image-exists? conn image-id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (create-image conn library-id image-id fpath))))
        (p/then (constantly image-id)))))

(defn- import-images
  [conn library-id {:keys [path regex] :as item}]
  (p/run! (fn [fpath]
            (when (re-matches regex (str fpath))
              (import-image-if-not-exists conn library-id fpath)))
          (->> (fs/list-dir path)
               (filter fs/regular-file?))))

(defn- process-images-library
  [conn basedir {:keys [path regex] :as item}]
  (s/assert ::import-item-media item)
  (-> (create-images-library-if-not-exists conn item)
      (p/then (fn [library-id]
                (->> (assoc item :path (fs/join basedir path))
                     (import-images conn library-id))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Colors Libraries Importer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- color-library-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [sql "select id from color_library where id = $1"]
    (-> (db/query-one conn [sql id])
        (p/then (fn [row] (if row true false))))))

(defn- create-colors-library
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +colors-uuid-ns+ name)]
    (log/info "Creating color library:" name)
    (colors/create-library conn {:id id
                                 :team-id uuid/zero
                                 :name name})))


(defn- create-colors-library-if-not-exists
  [conn {:keys [name] :as item}]
  (let [id (uuid/namespaced +colors-uuid-ns+ name)]
    (-> (color-library-exists? conn id)
        (p/then (fn [exists?]
                  (when-not exists?
                    (create-colors-library conn item))))
        (p/then (constantly id)))))

(defn- create-color
  [conn library-id content]
  (s/assert ::us/uuid library-id)
  (s/assert ::us/color content)

  (let [color-id (uuid/namespaced +colors-uuid-ns+ (str library-id content))]
    (log/info "Creating color" content color-id)
    (-> (colors/create-color conn {:id color-id
                                   :library-id library-id
                                   :name content
                                   :content content})
        (p/then' (constantly color-id)))))

(defn- prune-colors
  [conn library-id]
  (-> (db/query-one conn ["delete from color where library_id=$1" library-id])
      (p/then (constantly nil))))

(defn- import-colors
  [conn library-id {:keys [colors] :as item}]
  (us/verify ::import-item-color item)
  (p/do!
   (prune-colors conn library-id)
   (p/run! #(create-color conn library-id %) colors)))

(defn- process-colors-library
  [conn {:keys [name id colors] :as item}]
  (us/verify ::import-item-color item)
  (-> (create-colors-library-if-not-exists conn item)
      (p/then #(import-colors conn % item))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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
  (let [reader (java.io.PushbackReader. (io/reader path))]
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
        icons (:icons data)
        colors (:colors data)]
    (p/do!
     (p/run! #(process-images-library conn basedir %) images)
     (p/run! #(process-icons-library conn basedir %) icons)
     (p/run! #(process-colors-library conn %) colors)
     nil)))

(defn run
  [path]
  (p/let [[basedir data] (read-file path)]
    (db/with-atomic [conn db/pool]
      (importer conn basedir data))))

(defn -main
  [& [path]]
  (let [path (validate-path path)]
    (start-system)
    (-> (run path)
        (p/finally (fn [_ _] (stop-system))))))


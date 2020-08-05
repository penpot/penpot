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
   [mount.core :as mount]
   [datoteka.core :as fs]
   [cuerdas.core :as str]
   [uxbox.config]
   [uxbox.common.spec :as us]
   [uxbox.db :as db]
   [uxbox.http]
   [uxbox.migrations]
   [uxbox.util.svg :as svg]
   [uxbox.util.transit :as t]
   [uxbox.util.blob :as blob]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.data :as data]
   [uxbox.services.mutations.projects :as projects]
   [uxbox.services.mutations.files :as files]
   [uxbox.services.mutations.colors :as colors]
   [uxbox.services.mutations.media :as media]
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

;; (s/def ::colors
;;   (s/* (s/cat :name ::us/string :color ::us/color)))
;;
;; (s/def ::import-item-media
;;   (s/keys :req-un [::name ::path ::regex]))
;;
;; (s/def ::import-item-color
;;   (s/keys :req-un [::name ::id ::colors]))

(s/def ::import-images
  (s/keys :req-un [::path ::regex]))

(s/def ::import-color
  (s/* (s/cat :name ::us/string :color ::us/color)))

(s/def ::import-colors (s/coll-of ::import-color))

(s/def ::import-library
  (s/keys :req-un [::name]
          :opt-un [::import-images ::import-colors]))

(defn exit!
  ([] (exit! 0))
  ([code]
   (System/exit code)))

;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ;; Icons Libraries Importer
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; (defn- icon-library-exists?
;;   [conn id]
;;   (s/assert ::us/uuid id)
;;   (let [row (db/get-by-id conn :icon-library id)]
;;     (if row true false)))
;;
;; (defn- create-icons-library
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +icons-uuid-ns+ name)]
;;     (log/info "Creating icons library:" name)
;;     (icons/create-library conn {:team-id uuid/zero
;;                                 :id id
;;                                 :name name})))
;;
;; (defn- create-icons-library-if-not-exists
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +icons-uuid-ns+ name)]
;;     (when-not (icon-library-exists? conn id)
;;       (create-icons-library conn item))
;;     id))
;;
;; (defn- create-icon
;;   [conn library-id icon-id localpath]
;;   (s/assert fs/path? localpath)
;;   (s/assert ::us/uuid library-id)
;;   (s/assert ::us/uuid icon-id)
;;   (let [filename (fs/name localpath)
;;         extension (second (fs/split-ext filename))
;;         data (svg/parse localpath)
;;         mdata (select-keys data [:width :height :view-box])]
;;
;;     (log/info "Creating or updating icon" filename icon-id)
;;     (icons/create-icon conn {:id icon-id
;;                              :library-id library-id
;;                              :name (:name data filename)
;;                              :content (:content data)
;;                              :metadata mdata})))
;;
;; (defn- icon-exists?
;;   [conn id]
;;   (s/assert ::us/uuid id)
;;   (let [row (db/get-by-id conn :icon id)]
;;     (if row true false)))
;;
;; (defn- import-icon-if-not-exists
;;   [conn library-id fpath]
;;   (s/assert ::us/uuid library-id)
;;   (s/assert fs/path? fpath)
;;   (let [icon-id (uuid/namespaced +icons-uuid-ns+ (str library-id (fs/name fpath)))]
;;     (when-not (icon-exists? conn icon-id)
;;       (create-icon conn library-id icon-id fpath))
;;     icon-id))
;;
;; (defn- import-icons
;;   [conn library-id {:keys [path regex] :as item}]
;;   (run! (fn [fpath]
;;           (when (re-matches regex (str fpath))
;;             (import-icon-if-not-exists conn library-id fpath)))
;;         (->> (fs/list-dir path)
;;              (filter fs/regular-file?))))
;;
;; (defn- process-icons-library
;;   [conn basedir {:keys [path regex] :as item}]
;;   (s/assert ::import-item-media item)
;;   (let [library-id (create-icons-library-if-not-exists conn item)]
;;     (->> (assoc item :path (fs/join basedir path))
;;          (import-icons conn library-id))))
;;
;;
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ;; --- Images Libraries Importer
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; (defn- image-library-exists?
;;   [conn id]
;;   (s/assert ::us/uuid id)
;;   (let [row (db/get-by-id conn :image-library id)]
;;     (if row true false)))
;;
;; (defn- create-images-library
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +images-uuid-ns+ name)]
;;     (log/info "Creating image library:" name)
;;     (media/create-library conn {:id id
;;                                  :team-id uuid/zero
;;                                  :name name})))
;;
;; (defn- create-images-library-if-not-exists
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +images-uuid-ns+ name)]
;;     (when-not (image-library-exists? conn id)
;;       (create-images-library conn item)
;;       id)))
;;
;; (defn- create-image
;;   [conn library-id image-id localpath]
;;   (s/assert fs/path? localpath)
;;   (s/assert ::us/uuid library-id)
;;   (s/assert ::us/uuid image-id)
;;   (let [filename (fs/name localpath)
;;         extension (second (fs/split-ext filename))
;;         file (io/as-file localpath)
;;         mtype (case extension
;;                 ".jpg"  "image/jpeg"
;;                 ".png"  "image/png"
;;                 ".webp" "image/webp")]
;;     (log/info "Creating image" filename image-id)
;;     (media/create-image conn {:content {:tempfile localpath
;;                                          :filename filename
;;                                          :content-type mtype
;;                                          :size (.length file)}
;;                                :id image-id
;;                                :library-id library-id
;;                                :user uuid/zero
;;                                :name filename})))
;;
;; (defn- image-exists?
;;   [conn id]
;;   (s/assert ::us/uuid id)
;;   (let [row (db/get-by-id conn :image id)]
;;     (if row true false)))
;;
;; (defn- import-image-if-not-exists
;;   [conn library-id fpath]
;;   (s/assert ::us/uuid library-id)
;;   (s/assert fs/path? fpath)
;;   (let [image-id (uuid/namespaced +images-uuid-ns+ (str library-id (fs/name fpath)))]
;;     (when-not (image-exists? conn image-id)
;;       (create-image conn library-id image-id fpath))
;;     image-id))
;;
;; (defn- import-images
;;   [conn library-id {:keys [path regex] :as item}]
;;   (run! (fn [fpath]
;;           (when (re-matches regex (str fpath))
;;             (import-image-if-not-exists conn library-id fpath)))
;;         (->> (fs/list-dir path)
;;              (filter fs/regular-file?))))
;;
;; (defn- process-images-library
;;   [conn basedir {:keys [path regex] :as item}]
;;   (s/assert ::import-item-media item)
;;   (let [library-id (create-images-library-if-not-exists conn item)]
;;     (->> (assoc item :path (fs/join basedir path))
;;          (import-images conn library-id))))
;;
;;
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ;; Colors Libraries Importer
;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; (defn- color-library-exists?
;;   [conn id]
;;   (s/assert ::us/uuid id)
;;   (let [row (db/get-by-id conn :file id)]
;;     (if row true false)))
;;
;; (defn- create-colors-library
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +colors-uuid-ns+ name)]
;;     (log/info "Creating color library:" name)
;;     (colors/create-library conn {:id id
;;                                  :team-id uuid/zero
;;                                  :name name})))
;;
;;
;; (defn- create-colors-library-if-not-exists
;;   [conn {:keys [name] :as item}]
;;   (let [id (uuid/namespaced +colors-uuid-ns+ name)]
;;     (when-not (color-library-exists? conn id)
;;       (create-colors-library conn item))
;;     id))
;;
;; (defn- create-color
;;   [conn library-id name content]
;;   (s/assert ::us/uuid library-id)
;;   (s/assert ::us/color content)
;;   (let [color-id (uuid/namespaced +colors-uuid-ns+ (str library-id content))]
;;     (log/info "Creating color" color-id "-" name content)
;;     (colors/create-color conn {:id color-id
;;                                :library-id library-id
;;                                :name name
;;                                :content content})
;;     color-id))
;;
;; (defn- import-colors
;;   [conn library-id {:keys [colors] :as item}]
;;   (db/delete! conn :color {:library-id library-id})
;;   (run! (fn [[name content]]
;;           (create-color conn library-id name content))
;;         (partition-all 2 colors)))
;;
;; (defn- process-colors-library
;;   [conn {:keys [name id colors] :as item}]
;;   (us/verify ::import-item-color item)
;;   (let [library-id (create-colors-library-if-not-exists conn item)]
;;     (import-colors conn library-id item)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Images Importer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-image
  [conn file-id image-id localpath]
  (s/assert fs/path? localpath)
  (s/assert ::us/uuid file-id)
  (s/assert ::us/uuid image-id)
  (let [filename (fs/name localpath)
        extension (second (fs/split-ext filename))
        file (io/as-file localpath)
        mtype (case extension
                ".jpg"  "image/jpeg"
                ".png"  "image/png"
                ".webp" "image/webp"
                ".svg"  "image/svg+xml")]
    (log/info "Creating image" filename image-id)
    (media/create-media-object conn {:content {:tempfile localpath
                                               :filename filename
                                               :content-type mtype
                                               :size (.length file)}
                                     :id image-id
                                     :file-id file-id
                                     :name filename
                                     :is-local false})))

(defn- image-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [row (db/get-by-id conn :media-object id)]
    (if row true false)))

(defn- import-image-if-not-exists
  [conn file-id fpath]
  (s/assert ::us/uuid file-id)
  (s/assert fs/path? fpath)
  (let [image-id (uuid/namespaced +images-uuid-ns+ (str file-id (fs/name fpath)))]
    (when-not (image-exists? conn image-id)
      (create-image conn file-id image-id fpath))
    image-id))

(defn- import-images
  [conn file-id {:keys [path regex] :as images}]
  (run! (fn [fpath]
          (when (re-matches regex (str fpath))
            (import-image-if-not-exists conn file-id fpath)))
        (->> (fs/list-dir path)
             (filter fs/regular-file?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Colors Importer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-color
  [conn file-id name content]
  (s/assert ::us/uuid file-id)
  (s/assert ::us/color content)
  (let [color-id (uuid/namespaced +colors-uuid-ns+ (str file-id content))]
    (log/info "Creating color" color-id "-" name content)
    (colors/create-color conn {:id color-id
                               :file-id file-id
                               :name name
                               :content content})
    color-id))

(defn- import-colors
  [conn file-id colors]
  (db/delete! conn :color {:file-id file-id})
  (run! (fn [[name content]]
          (create-color conn file-id name content))
        (partition-all 2 colors)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Library files Importer
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- library-file-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [row (db/get-by-id conn :file id)]
    (if row true false)))

(defn- create-library-file-if-not-exists
  [conn project-id {:keys [name] :as library-file}]
  (let [id (uuid/namespaced +colors-uuid-ns+ name)]
    (when-not (library-file-exists? conn id)
      (log/info "Creating library-file:" name)
      (files/create-file conn {:id id
                               :profile-id uuid/zero
                               :project-id project-id
                               :name name
                               :shared? true})
      (files/create-page conn {:file-id id}))
    id))

(defn- process-library
  [conn basedir project-id {:keys [name images colors] :as library}]
  (us/verify ::import-library library)
  (let [library-file-id (create-library-file-if-not-exists conn project-id library)]
    (when images
      (->> (assoc images :path (fs/join basedir (:path images)))
           (import-images conn library-file-id)))
    (when colors
      (import-colors conn library-file-id colors))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- project-exists?
  [conn id]
  (s/assert ::us/uuid id)
  (let [row (db/get-by-id conn :project id)]
    (if row true false)))

(defn- create-project-if-not-exists
  [conn {:keys [name] :as project}]
  (let [id (uuid/namespaced +colors-uuid-ns+ name)]
    (when-not (project-exists? conn id)
      (log/info "Creating project" name)
      (projects/create-project conn {:id id
                                     :team-id uuid/zero
                                     :name name
                                     :default? false}))
    id))

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

;; (defn- importer
;;   [conn basedir data]
;;   (let [images (:images data)
;;         icons (:icons data)
;;         colors (:colors data)]
;;     (run! #(process-images-library conn basedir %) images)
;;     (run! #(process-icons-library conn basedir %) icons)
;;     (run! #(process-colors-library conn %) colors)))

(defn run
  [path]
  (let [[basedir libraries] (read-file path)]
    (db/with-atomic [conn db/pool]
      (let [project-id (create-project-if-not-exists conn {:name "System libraries"})]
        (run! #(process-library conn basedir project-id %) libraries)))))

(defn -main
  [& [path]]
  (let [path (validate-path path)]
    (try
      (start-system)
      (run path)
      (finally
        (stop-system)))))


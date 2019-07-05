;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2017 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.media
  "A media storage impl for uxbox."
  (:require [mount.core :refer [defstate]]
            [clojure.java.io :as io]
            [cuerdas.core :as str]
            [datoteka.core :as fs]
            [datoteka.proto :as stp]
            [datoteka.storages :as st]
            [datoteka.storages.local :refer [localfs]]
            [datoteka.storages.misc :refer [hashed scoped]]
            [uxbox.config :refer [config]]))

;; --- Backends

(defn- normalize-filename
  [path]
  (let [parent (or (fs/parent path) "")
        [name ext] (fs/split-ext (fs/name path))]
    (fs/path parent (str (str/uslug name) ext))))

(defrecord FilenameSlugifiedBackend [storage]
  stp/IPublicStorage
  (-public-uri [_ path]
    (stp/-public-uri storage path))

  stp/IStorage
  (-save [_ path content]
    (let [^Path path (normalize-filename path)]
      (stp/-save storage path content)))

  (-delete [_ path]
    (stp/-delete storage path))

  (-exists? [this path]
    (stp/-exists? storage path))

  (-lookup [_ path]
    (stp/-lookup storage path)))

;; --- State

(defstate assets-storage
  :start (localfs {:basedir (:assets-directory config)
                   :baseuri (:assets-uri config)
                   :transform-filename str/uslug}))

(defstate media-storage
  :start (localfs {:basedir (:media-directory config)
                   :baseuri (:media-uri config)
                   :transform-filename str/uslug}))

(defstate images-storage
  :start (-> media-storage
             (scoped "images")
             (hashed)
             (->FilenameSlugifiedBackend)))

(defstate thumbnails-storage
  :start (-> media-storage
             (scoped "thumbs")))

;; --- Public Api

(defn resolve-asset
  [path]
  (str (st/public-url assets-storage path)))

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
            [datoteka.storages :as st]
            [datoteka.storages.local :refer [localfs]]
            [datoteka.storages.misc :refer [hashed scoped]]
            [uxbox.config :refer [config]]))

;; --- State

(defstate assets-storage
  :start (localfs {:basedir (:assets-directory config)
                   :baseuri (:assets-uri config)}))

(defstate media-storage
  :start (localfs {:basedir (:media-directory config)
                   :baseuri (:media-uri config)}))

(defstate images-storage
  :start (-> media-storage
             (scoped "images")
             (hashed)))

(defstate thumbnails-storage
  :start (scoped media-storage "thumbs"))

;; --- Public Api

(defn resolve-asset
  [path]
  (str (st/public-url assets-storage path)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.media-storage
  "A media storage impl for app."
  (:require
   [app.config :refer [config]]
   [app.util.storage :as ust]
   [mount.core :refer [defstate]]))

;; --- State

(declare assets-storage)

(defstate assets-storage
  :start (ust/create {:base-path (:assets-directory config)
                      :base-uri (:assets-uri config)}))

(declare media-storage)

(defstate media-storage
  :start (ust/create {:base-path (:media-directory config)
                      :base-uri (:media-uri config)
                      :xf (comp ust/random-path
                                ust/slugify-filename)}))

;; --- Public Api

(defn resolve-asset
  [path]
  (str (ust/public-uri assets-storage path)))

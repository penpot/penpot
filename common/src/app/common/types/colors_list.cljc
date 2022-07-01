;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.colors-list
  (:require
    [app.common.data :as d]))

(defn colors-seq
  [file-data]
  (vals (:colors file-data)))

(defn add-color
  [file-data color]
  (update file-data :colors assoc (:id color) color))

(defn get-color
  [file-data color-id]
  (get-in file-data [:colors color-id]))

(defn update-color
  [file-data color-id f]
  (update-in file-data [:colors color-id] f))


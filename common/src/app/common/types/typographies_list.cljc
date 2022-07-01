;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.typographies-list
  (:require
    [app.common.data :as d]))

(defn typographies-seq
  [file-data]
  (vals (:typographies file-data)))

(defn add-typography
  [file-data typography]
  (update file-data :typographies assoc (:id typography) typography))

(defn get-typography
  [file-data typography-id]
  (get-in file-data [:typographies typography-id]))

(defn update-typography
  [file-data typography-id f]
  (update-in file-data [:typographies typography-id] f))


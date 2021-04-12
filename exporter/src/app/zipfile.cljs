;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.zipfile
  (:require
   ["jszip" :as jszip]))

(defn create
  []
  (new jszip))

(defn add!
  [zfile name data]
  (.file ^js zfile name data)
  zfile)


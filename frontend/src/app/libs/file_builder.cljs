;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.libs.file-builder
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]))

(deftype File [^:mutable file]
  Object
  (addPage [self name]
    (set! file (fb/add-page file name))
    (str (:current-page-id file))))


(defn create-file-export [^string name]
  (File. (fb/create-file name)))

(defn exports []
  #js { :createFile    create-file-export })

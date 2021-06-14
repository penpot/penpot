;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.libs.file-builder
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]
   [cuerdas.core :as str]))

(defn parse-data [data]
  (as-> data $
    (js->clj $ :keywordize-keys true)
    ;; Transforms camelCase to kebab-case
    (d/deep-mapm
     (fn [[k v]]
       [(-> k d/name str/kebab keyword) v]) $)))

(deftype File [^:mutable file]
  Object

  (addPage
    ([self name]
     (addPage self name nil))

    ([self name options]
     (set! file (fb/add-page file {:name name :options options}))
     (str (:current-page-id file))))

  (closePage [self]
    (set! file (fb/close-page file)))

  (addArtboard [self data]
    (set! file (fb/add-artboard file (parse-data data)))
    (str (:last-id file)))

  (closeArtboard [self data]
    (set! file (fb/close-artboard file)))

  (addGroup [self data]
    (set! file (fb/add-group file (parse-data data)))
    (str (:last-id file)))

  (closeGroup [self]
    (set! file (fb/close-group file)))

  (createRect [self data]
    (set! file (fb/create-rect file (parse-data data))))

  (createCircle [self data]
    (set! file (fb/create-circle file (parse-data data))))

  (createPath [self data]
    (set! file (fb/create-path file (parse-data data))))

  (createText [self data]
    (set! file (fb/create-text file (parse-data data))))

  (createImage [self data]
    (set! file (fb/create-image file (parse-data data))))

  (createSVG [self data]
    (set! file (fb/create-svg-raw file (parse-data data))))

  (closeSVG [self]
    (set! file (fb/close-svg-raw file))))

(defn create-file-export [^string name]
  (File. (fb/create-file name)))

(defn exports []
  #js { :createFile    create-file-export })

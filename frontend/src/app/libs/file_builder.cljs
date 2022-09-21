;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

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
     (fn [[key value]]
       (let [value (if (= (type value) js/Symbol)
                     (keyword (js/Symbol.keyFor value))
                     value)
             key (-> key d/name str/kebab keyword)]
         [key value])) $)))

(deftype File [^:mutable file]
  Object

  (addPage [_ name]
    (set! file (fb/add-page file {:name name}))
    (str (:current-page-id file)))

  (addPage [_ name options]
    (set! file (fb/add-page file {:name name :options options}))
    (str (:current-page-id file)))

  (closePage [_]
    (set! file (fb/close-page file)))

  (addArtboard [_ data]
    (set! file (fb/add-artboard file (parse-data data)))
    (str (:last-id file)))

  (closeArtboard [_]
    (set! file (fb/close-artboard file)))

  (addGroup [_ data]
    (set! file (fb/add-group file (parse-data data)))
    (str (:last-id file)))

  (closeGroup [_]
    (set! file (fb/close-group file)))

  (createRect [_ data]
    (set! file (fb/create-rect file (parse-data data)))
    (str (:last-id file)))

  (createCircle [_ data]
    (set! file (fb/create-circle file (parse-data data)))
    (str (:last-id file)))

  (createPath [_ data]
    (set! file (fb/create-path file (parse-data data)))
    (str (:last-id file)))

  (createText [_ data]
    (set! file (fb/create-text file (parse-data data)))
    (str (:last-id file)))

  (createImage [_ data]
    (set! file (fb/create-image file (parse-data data)))
    (str (:last-id file)))

  (createSVG [_ data]
    (set! file (fb/create-svg-raw file (parse-data data)))
    (str (:last-id file)))

  (closeSVG [_]
    (set! file (fb/close-svg-raw file)))

  (asMap [_]
    (clj->js file)))

(defn create-file-export [^string name]
  (File. (fb/create-file name)))

(defn exports []
  #js { :createFile    create-file-export })

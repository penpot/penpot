;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.file-repair
  (:require
   [app.common.types.container :as ctn]
   [app.common.types.pages-list :as ctpl]))

(defn- update-shape-in-file
  [file page-id shape-id f]
  (update file :data
          (fn [data]
            (ctpl/update-page data page-id
                              (fn [page]
                                (ctn/update-shape page shape-id f))))))

(defmulti repair-error
  (fn [code _error _file _libraries] code))

(defmethod repair-error :nested-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (assoc shape :component-root true))]
 
    (update-shape-in-file {:id (:id file) ;; WTF
                           :data file} page-id (:id shape) repair-shape)))

;; (defmethod repair-error :component-not-found
;;   "Detach the shape and convert it to non instance."
;;   [_ {:keys [shape page-id]} file _]
;;   (let [repair-shape
;;         (fn [shape]
;;           (ctk/detach-shape shape))]
;; 
;;   (update-shape-in-file file page-id (:id shape) repair-shape)))

(defmethod repair-error :default
  [_ error file _]
  (prn "unknown error code" (:code error))
  file)

(defn repair-file
  [file libraries errors]
  (reduce (fn [file error]
            (repair-error (:code error)
                          error
                          file
                          libraries))
          file
          errors))

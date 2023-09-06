;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.file-repair
  (:require
   [app.common.logging :as log]
   [app.common.pages.helpers :as cph]
   [app.common.types.container :as ctn]
   [app.common.types.pages-list :as ctpl]
   [app.common.uuid :as uuid]))

(log/set-level! :debug)

(defn- update-shape-in-file
  [file-data page-id shape-id f]
  (ctpl/update-page file-data page-id
                    (fn [page]
                      (ctn/update-shape page shape-id f))))

(defmulti repair-error
  (fn [code _error _file-data _libraries] code))

;; (defmethod repair-error :parent-not-found
;;   [_ {:keys [shape page-id] :as error} file-data _]
;;   (let [repair-shape
;;         (fn [shape]
;;           ; Set parent to root frame.
;;           (log/debug :hint "  -> Set to " :parent-id uuid/zero)
;;           (assoc shape :parent-id uuid/zero))]
;;  
;;     (log/info :hint "Repairing shape :parent-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
;;     (update-shape-in-file file-data page-id (:id shape) repair-shape)))
;; 
;; (defmethod repair-error :frame-not-found
;;   [_ {:keys [shape page-id] :as error} file-data _]
;;   (let [repair-shape
;;         (fn [shape]
;;           ; Locate the first frame in parents and set frame-id to it.
;;           (let [page     (ctpl/get-page file-data page-id)
;;                 frame    (cph/get-frame (:objects page) (:parent-id shape))
;;                 frame-id (or (:id frame) uuid/zero)]
;;             (log/debug :hint "  -> Set to " :frame-id frame-id)
;;             (assoc shape :frame-id frame-id)))]
;;  
;;     (log/info :hint "Repairing shape :frame-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
;;     (update-shape-in-file file-data page-id (:id shape) repair-shape)))

(defmethod repair-error :root-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy root.
          (log/debug :hint "  -> Unset :component-root")
          (dissoc shape :component-root))]
 
    (log/info :hint "Repairing shape :root-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (update-shape-in-file file-data page-id (:id shape) repair-shape)))

(defmethod repair-error :should-not-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy root.
          (log/debug :hint "  -> Unset :component-root")
          (dissoc shape :component-root))]
 
    (log/info :hint "Repairing shape :should-not-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (update-shape-in-file file-data page-id (:id shape) repair-shape)))

(defmethod repair-error :nested-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> Set :component-root")
          (assoc shape :component-root true))]
 
    (log/info :hint "Repairing shape :nested-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (update-shape-in-file file-data page-id (:id shape) repair-shape)))

(defmethod repair-error :should-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> Set :component-root")
          (assoc shape :component-root true))]
 
    (log/info :hint "Repairing shape :should-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (update-shape-in-file file-data page-id (:id shape) repair-shape)))

;; (defmethod repair-error :component-not-found
;;   "Detach the shape and convert it to non instance."
;;   [_ {:keys [shape page-id]} file _]
;;   (let [repair-shape
;;         (fn [shape]
;;           (ctk/detach-shape shape))]
;; 
;;   (update-shape-in-file file-data page-id (:id shape) repair-shape)))

(defmethod repair-error :component-not-found
  [_ _ file _]
  file)

(defmethod repair-error :missing-component-root
  [_ _ file _]
  file)

(defmethod repair-error :not-head-main-not-allowed
  [_ _ file _]
  file)

(defmethod repair-error :default
  [_ error file _]
  ;; (log/warn :hint "Unknown error code, don't know how to repair" :code (:code error))
  file)

(defn repair-file
  [file-data libraries errors]
  (log/info :hint "Repairing file" :id (:id file-data) :error-count (count errors))
  (reduce (fn [file-data error]
            (repair-error (:code error)
                          error
                          file-data
                          libraries))
          file-data
          errors))

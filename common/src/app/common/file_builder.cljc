;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.file-builder
  "A version parsing helper."
  (:require
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.pages.init :as init]
   [app.common.pages.changes :as ch]
   ))

(def root-frame uuid/zero)

(defn create-file
  ([name]
   (let [id (uuid/next)]
     {:id id
      :name name
      :data (-> init/empty-file-data
                (assoc :id id))

      ;; We keep the changes so we can send them to the backend
      :changes []})))

;; TODO: Change to `false`
(def verify-on-commit? true)

(defn commit-change [file change]
  (-> file
      (update :changes conj change)
      (update :data ch/process-changes [change] verify-on-commit?)))

(defn add-page
  [file name]

  (let [page-id (uuid/next)]
    (-> file
        (commit-change
         {:type :add-page
          :id page-id
          :name name
          :page (-> init/empty-page-data
                    (assoc :name name))})

        ;; Current page being edited
        (assoc :current-page-id page-id)

        ;; Current parent stack we'll be nesting
        (assoc :parent-stack [root-frame]))))

(defn add-artboard [file data])

(defn close-artboard [file])

(defn add-group [file data])
(defn close-group [file data]) 

(defn create-rect [file data])
(defn create-circle [file data])
(defn create-path [file data])
(defn create-text [file data])
(defn create-image [file data])

(defn close-page [file])

(defn generate-changes [file])

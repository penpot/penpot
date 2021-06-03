;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.file-builder
  "A version parsing helper."
  (:require
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.changes :as ch]
   [app.common.pages.init :as init]
   [app.common.pages.spec :as spec]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]))

(def root-frame uuid/zero)

;; This flag controls if we should execute spec validation after every commit
(def verify-on-commit? true)

(defn- commit-change [file change]
  (when verify-on-commit?
    (us/assert ::spec/change change))
  (-> file
      (update :changes conj change)
      (update :data ch/process-changes [change] verify-on-commit?)))

(defn- lookup-objects
  ([file]
   (lookup-objects file (:current-page-id file)))

  ([file page-id]
   (get-in file [:data :pages-index page-id :objects])))

(defn- lookup-shape [file shape-id]
  (-> (lookup-objects file)
      (get shape-id)))

(defn- commit-shape [file obj]
  (let [page-id (:current-page-id file)
        frame-id (:current-frame-id file)
        parent-id (-> file :parent-stack peek)]
    (-> file
        (commit-change
         {:type :add-obj
          :id (:id obj)
          :page-id page-id
          :frame-id frame-id
          :parent-id parent-id
          :obj obj}))))

;; PUBLIC API

(defn create-file
  ([name]
   (let [id (uuid/next)]
     {:id id
      :name name
      :data (-> init/empty-file-data
                (assoc :id id))

      ;; We keep the changes so we can send them to the backend
      :changes []})))

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

        ;; Current frame-id
        (assoc :current-frame-id root-frame)

        ;; Current parent stack we'll be nesting
        (assoc :parent-stack [root-frame]))))

(defn add-artboard [file data]
  (let [obj (-> (init/make-minimal-shape :frame)
                (merge data))]
    (-> file
        (commit-shape obj)
        (assoc :current-frame-id (:id obj))
        (update :parent-stack conj (:id obj)))))

(defn close-artboard [file]
  (-> file
      (assoc :current-frame-id root-frame)
      (update :parent-stack pop)))

(defn add-group [file data]
  (let [frame-id (:current-frame-id file)
        selrect init/empty-selrect
        name (:name data)
        obj (-> (init/make-minimal-group frame-id selrect name)
                (merge data))]
    (-> file
        (commit-shape obj)
        (update :parent-stack conj (:id obj)))))

(defn close-group [file]
  (let [group-id (-> file :parent-stack peek)
        group    (lookup-shape file group-id)
        shapes   (->> group :shapes (mapv #(lookup-shape file %)))
        selrect  (gsh/selection-rect shapes)
        points   (gsh/rect->points selrect)]

    (-> file
        (commit-change
         {:type :mod-obj
          :page-id (:current-page-id file)
          :id group-id
          :operations
          [{:type :set :attr :selrect :val selrect}
           {:type :set :attr :points  :val points}]})
        (update :parent-stack pop))))

(defn create-shape [file type data]
  (let [frame-id (:current-frame-id file)
        frame (when-not (= frame-id root-frame)
                (lookup-shape file frame-id))
        obj (-> (init/make-minimal-shape type)
                (merge data)
                (d/without-nils)
                (cond-> frame
                  (gsh/translate-from-frame frame)))]
    (commit-shape file obj)))

(defn create-rect [file data]
  (create-shape file :rect data))

(defn create-circle [file data]
  (create-shape file :circle data))

(defn create-path [file data]
  (create-shape file :path data))

(defn create-text [file data]
  (create-shape file :text data))

(defn create-image [file data]
  (create-shape file :image data))

(defn close-page [file]
  (-> file
      (dissoc :current-page-id)
      (dissoc :parent-stack)))

(defn generate-changes
  [file]
  (:changes file))

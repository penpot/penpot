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
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(def root-frame uuid/zero)

;; This flag controls if we should execute spec validation after every commit
(def verify-on-commit? true)

(defn- commit-change [file change]
  (when verify-on-commit?
    (us/assert ::spec/change change))
  (-> file
      (update :changes (fnil conj []) change)
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

(defn generate-name
  [type data]
  (if (= type :svg-raw)
    (let [tag (get-in data [:content :tag])]
      (str "svg-" (cond (string? tag) tag
                        (keyword? tag) (d/name tag)
                        (nil? tag) "node"
                        :else (str tag))))
    (str/capital (d/name type))))

(defn check-name
  "Given a tag returns its layer name"
  [data file type]

  (cond-> data
    (nil? (:name data))
    (assoc :name (generate-name type data))

    :always
    (update :name d/unique-name (:unames file))))

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
  [file data]
  (let [page-id (uuid/next)
        page (-> init/empty-page-data
                 (assoc :id page-id)
                 (d/deep-merge data))]
    (-> file
        (commit-change
         {:type :add-page
          :page page})

        ;; Current page being edited
        (assoc :current-page-id page-id)

        ;; Current frame-id
        (assoc :current-frame-id root-frame)

        ;; Current parent stack we'll be nesting
        (assoc :parent-stack [root-frame])

        ;; Last object id added
        (assoc :last-id nil)

        ;; Current used names
        (assoc :unames #{}))))

(defn close-page [file]
  (-> file
      (dissoc :current-page-id)
      (dissoc :parent-stack)
      (dissoc :last-id)
      (dissoc :unames)))

(defn add-artboard [file data]
  (let [obj (-> (init/make-minimal-shape :frame)
                (merge data)
                (check-name file :frame)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :current-frame-id (:id obj))
        (assoc :last-id (:id obj))
        (update :unames conj (:name obj))
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
                (merge data)
                (check-name file :group)
                (d/without-nils))]
    (-> file
        (commit-shape obj)
        (assoc :last-id (:id obj))
        (update :unames conj (:name obj))
        (update :parent-stack conj (:id obj)))))

(defn close-group [file]
  (let [group-id (-> file :parent-stack peek)
        group    (lookup-shape file group-id)
        shapes   (->> group :shapes (mapv #(lookup-shape file %)))
        selrect  (gsh/selection-rect shapes)
        points   (gsh/rect->points selrect)]

    (-> file
        (cond-> (not (empty? shapes))
          (commit-change
           {:type :mod-obj
            :page-id (:current-page-id file)
            :id group-id
            :operations
            [{:type :set :attr :selrect :val selrect}
             {:type :set :attr :points  :val points}]}))
        (update :parent-stack pop))))

(defn create-shape [file type data]
  (let [frame-id (:current-frame-id file)
        frame (when-not (= frame-id root-frame)
                (lookup-shape file frame-id))
        obj (-> (init/make-minimal-shape type)
                (merge data)
                (check-name file :type)
                (d/without-nils))
        obj (cond-> obj
              frame (gsh/translate-from-frame frame))]
    (-> file
        (commit-shape obj)
        (assoc :last-id (:id obj))
        (update :unames conj (:name obj)))))

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

(declare close-svg-raw)

(defn create-svg-raw [file data]
  (let [file (as-> file $
               (create-shape $ :svg-raw data)
               (update $ :parent-stack conj (:last-id $)))

        create-child
        (fn [file child]
          (-> file
              (create-svg-raw (assoc data :content child))
              (close-svg-raw)))]

    ;; First :content is the the shape attribute, the other content is the
    ;; XML children
    (reduce create-child file (get-in data [:content :content]))))

(defn close-svg-raw [file]
  (-> file
      (update :parent-stack pop)))


(defn generate-changes
  [file]
  (:changes file))

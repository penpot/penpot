;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.state-helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.path.commands :as upc]
   [app.common.uuid :as uuid]))

(defn lookup-page
  ([state]
   (lookup-page state (:current-page-id state)))
  ([state page-id]
   (get-in state [:workspace-data :pages-index page-id])))

(defn lookup-data-objects
  [data page-id]
  (dm/get-in data [:pages-index page-id :objects]))

(defn lookup-page-objects
  ([state]
   (lookup-page-objects state (:current-page-id state)))
  ([state page-id]
   (dm/get-in state [:workspace-data :pages-index page-id :objects])))

(defn lookup-viewer-objects
  ([state page-id]
   (dm/get-in state [:viewer :pages page-id :objects])))

(defn lookup-page-options
  ([state]
   (lookup-page-options state (:current-page-id state)))
  ([state page-id]
   (dm/get-in state [:workspace-data :pages-index page-id :options])))

(defn lookup-local-components
  ([state]
   (dm/get-in state [:workspace-data :components])))

(defn process-selected-shapes
  ([objects selected]
   (process-selected-shapes objects selected nil))

  ([objects selected {:keys [omit-blocked?] :or {omit-blocked? false}}]
   (letfn [(selectable? [id]
             (and (contains? objects id)
                  (or (not omit-blocked?)
                      (not (get-in objects [id :blocked] false)))))]
     (let [selected (->> selected (cph/clean-loops objects))]
       (into (d/ordered-set)
             (filter selectable?)
             selected)))))

(defn lookup-selected-raw
  [state]
  (dm/get-in state [:workspace-local :selected]))

(defn lookup-selected
  ([state]
   (lookup-selected state nil))
  ([state options]
   (lookup-selected state (:current-page-id state) options))
  ([state page-id options]
   (let [objects  (lookup-page-objects state page-id)
         selected (dm/get-in state [:workspace-local :selected])]
     (process-selected-shapes objects selected options))))

(defn lookup-shape
  ([state id]
   (lookup-shape state (:current-page-id state) id))

  ([state page-id id]
   (let [objects (lookup-page-objects state page-id)]
     (get objects id))))

(defn lookup-shapes
  ([state ids]
   (lookup-shapes state (:current-page-id state) ids))
  ([state page-id ids]
   (let [objects (lookup-page-objects state page-id)]
     (into [] (keep (d/getf objects)) ids))))

(defn filter-shapes
  ([state filter-fn]
   (filter-shapes state (:current-page-id state) filter-fn))
  ([state page-id filter-fn]
   (let [objects (lookup-page-objects state page-id)]
     (into [] (filter filter-fn) (vals objects)))))

(defn get-local-file
  "Get the data content of the file you are currently working with."
  [state]
  (get state :workspace-data))

(defn get-file
  "Get the data content of the given file (it may be the current file
  or one library)."
  [state file-id]
  (if (= file-id (:current-file-id state))
    (get state :workspace-data)
    (dm/get-in state [:workspace-libraries file-id :data])))

(defn get-libraries
  "Retrieve all libraries, including the local file."
  [state]
  (let [{:keys [id] :as local} (:workspace-data state)]
    (-> (:workspace-libraries state)
        (assoc id {:id id
                   :data local}))))

(defn- set-content-modifiers [state]
  (fn [id shape]
    (let [content-modifiers (dm/get-in state [:workspace-local :edit-path id :content-modifiers])]
      (if (some? content-modifiers)
        (update shape :content upc/apply-content-modifiers content-modifiers)
        shape))))

(defn select-bool-children
  [parent-id state]
  (let [objects   (lookup-page-objects state)
        modifiers (:workspace-modifiers state)
        children-ids (cph/get-children-ids objects parent-id)
        children
        (-> (select-keys objects children-ids)
            (update-vals
             (fn [child]
               (cond-> child
                 (contains? modifiers (:id child))
                 (gsh/transform-shape (get-in modifiers [(:id child) :modifiers]))))))]

    (as-> children $
      (d/mapm (set-content-modifiers state) $))))

(defn viewport-center
  [state]
  (let [{:keys [x y width height]} (get-in state [:workspace-local :vbox])]
    (gpt/point (+ x (/ width 2)) (+ y (/ height 2)))))

(defn find-orphan-shapes
  ([state]
   (find-orphan-shapes state (:current-page-id state)))
  ([state page-id]
   (let [objects  (lookup-page-objects state page-id)
         objects (filter (fn [item]
                           (and
                            (not= (key item) uuid/zero)
                            (not (contains? objects (:parent-id (val item))))))
                         objects)]
     objects)))

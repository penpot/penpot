;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.helpers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.svg.path.command :as upc]))

(defn lookup-profile
  ([state]
   (:profile state))
  ([state profile-id]
   (dm/get-in state [:profiles profile-id])))

(defn lookup-libraries
  "Retrieve all libraries, including the local file."
  [state]
  (:files state))

(defn lookup-file
  ([state]
   (lookup-file state (:current-file-id state)))
  ([state file-id]
   (dm/get-in state [:files file-id])))

(defn lookup-file-data
  ([state]
   (lookup-file-data state (:current-file-id state)))
  ([state file-id]
   (dm/get-in state [:files file-id :data])))

(defn get-page
  [fdata page-id]
  (dm/get-in fdata [:pages-index page-id]))

(defn lookup-page
  ([state]
   (let [file-id (:current-file-id state)
         page-id (:current-page-id state)]
     (lookup-page state file-id page-id)))
  ([state page-id]
   (let [file-id (:current-file-id state)]
     (lookup-page state file-id page-id)))
  ([state file-id page-id]
   (dm/get-in state [:files file-id :data :pages-index page-id])))

(defn lookup-page-objects
  ([state]
   (lookup-page-objects state
                        (:current-file-id state)
                        (:current-page-id state)))
  ([state page-id]
   (lookup-page-objects state
                        (:current-file-id state)
                        page-id))
  ([state file-id page-id]
   (-> (lookup-page state file-id page-id)
       (get :objects))))

(defn process-selected-shapes
  ([objects selected]
   (process-selected-shapes objects selected nil))

  ([objects selected {:keys [omit-blocked?] :or {omit-blocked? false}}]
   (letfn [(selectable? [id]
             (and (contains? objects id)
                  (or (not omit-blocked?)
                      (not (get-in objects [id :blocked] false)))))]
     (let [selected (->> selected (cfh/clean-loops objects))]
       (into (d/ordered-set)
             (filter selectable?)
             selected)))))

;; DEPRECATED
(defn lookup-selected-raw
  [state]
  (dm/get-in state [:workspace-local :selected]))

(defn get-selected-ids
  [state]
  (dm/get-in state [:workspace-local :selected]))

(defn lookup-selected
  ([state]
   (lookup-selected state (:current-page-id state) nil))
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

(defn update-file
  ([state f]
   (update-file state (:current-file-id state) f))
  ([state file-id f]
   (d/update-in-when state [:files file-id] f)))

(defn update-page
  ([state f]
   (update-page state
                (:current-file-id state)
                (:current-page-id state)
                f))
  ([state page-id f]
   (update-page state
                (:current-file-id state)
                page-id
                f))
  ([state file-id page-id f]
   (d/update-in-when state [:files file-id :data :pages-index page-id] f)))

(defn filter-shapes
  ([state filter-fn]
   (filter-shapes state (:current-page-id state) filter-fn))
  ([state page-id filter-fn]
   (let [objects (lookup-page-objects state page-id)]
     (into [] (filter filter-fn) (vals objects)))))

(defn select-bool-children
  [state parent-id]
  (let [objects (lookup-page-objects state)

        shape-modifiers
        (:workspace-modifiers state)

        content-modifiers
        (dm/get-in state [:workspace-local :edit-path])]

    (reduce (fn [result id]
              (if-let [shape (get objects id)]
                (let [modifiers (dm/get-in shape-modifiers [id :modifiers])
                      shape     (if (some? modifiers)
                                  (gsh/transform-shape shape modifiers)
                                  shape)
                      modifiers (dm/get-in content-modifiers [id :content-modifiers])
                      shape     (if (some? modifiers)
                                  (update shape :content upc/apply-content-modifiers modifiers)
                                  shape)]
                  (assoc result id shape))
                result))
            {}
            (cfh/get-children-ids objects parent-id))))

(defn get-viewport-center
  [state]
  (when-let [{:keys [x y width height]} (get-in state [:workspace-local :vbox])]
    (gpt/point (+ x (/ width 2)) (+ y (/ height 2)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.bool
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.geom.shapes :as gsh]
   [app.common.pages :as cp]
   [app.common.pages.changes-builder :as cb]
   [app.common.path.shapes-to-path :as stp]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defn selected-shapes
  [state]
  (let [objects  (wsh/lookup-page-objects state)]
    (->> (wsh/lookup-selected state)
         (cp/clean-loops objects)
         (map #(get objects %))
         (filter #(not= :frame (:type %)))
         (map #(assoc % ::index (cp/position-on-parent (:id %) objects)))
         (sort-by ::index))))

(defn create-bool-data
  [bool-type name shapes objects]
  (let [shapes (mapv #(stp/convert-to-path % objects) shapes)
        head (if (= bool-type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (nil? (:fill-color head)))
               (assoc :fill-color clr/black))

        head-data (select-keys head stp/style-properties)

        bool-shape
        (-> {:id (uuid/next)
             :type :bool
             :bool-type bool-type
             :frame-id (:frame-id head)
             :parent-id (:parent-id head)
             :name name
             :shapes (->> shapes (mapv :id))}
            (merge head-data)
            (gsh/update-bool-selrect shapes objects))]

    [bool-shape (cp/position-on-parent (:id head) objects)]))

(defn group->bool
  [group bool-type objects]

  (let [shapes (->> (:shapes group)
                    (map #(get objects %))
                    (mapv #(stp/convert-to-path % objects)))
        head (if (= bool-type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (nil? (:fill-color head)))
               (assoc :fill-color clr/black))
        head-data (select-keys head stp/style-properties)]

    (-> group
        (assoc :type :bool)
        (assoc :bool-type bool-type)
        (merge head-data)
        (gsh/update-bool-selrect shapes objects))))

(defn bool->group
  [shape objects]

  (let [children (->> (:shapes shape)
                      (mapv #(get objects %)))]
    (-> shape
        (assoc :type :group)
        (dissoc :bool-type)
        (d/without-keys stp/style-group-properties)
        (gsh/update-group-selrect children))))

(defn create-bool
  [bool-type]
  (ptk/reify ::create-bool-union
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            objects (wsh/lookup-page-objects state)
            base-name (-> bool-type d/name str/capital (str "-1"))
            name (-> (dwc/retrieve-used-names objects)
                     (dwc/generate-unique-name base-name))
            shapes  (selected-shapes state)]

        (when-not (empty? shapes)
          (let [[boolean-data index] (create-bool-data bool-type name shapes objects)
                shape-id (:id boolean-data)
                changes (-> (cb/empty-changes it page-id)
                            (cb/with-objects objects)
                            (cb/add-obj boolean-data index)
                            (cb/change-parent shape-id shapes))]
            (rx/of (dch/commit-changes changes)
                   (dwc/select-shapes (d/ordered-set shape-id)))))))))

(defn group-to-bool
  [shape-id bool-type]
  (ptk/reify ::group-to-bool
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            change-to-bool
            (fn [shape] (group->bool shape bool-type objects))]
        (rx/of (dch/update-shapes [shape-id] change-to-bool {:reg-objects? true}))))))

(defn bool-to-group
  [shape-id]
  (ptk/reify ::bool-to-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            change-to-group
            (fn [shape] (bool->group shape objects))]
        (rx/of (dch/update-shapes [shape-id] change-to-group {:reg-objects? true}))))))


(defn change-bool-type
  [shape-id bool-type]
  (ptk/reify ::change-bool-type
    ptk/WatchEvent
    (watch [_ _ _]
      (let [change-type
            (fn [shape] (assoc shape :bool-type bool-type))]
        (rx/of (dch/update-shapes [shape-id] change-type {:reg-objects? true}))))))

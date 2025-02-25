;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.bool
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cph]
   [app.common.geom.shapes :as gsh]
   [app.common.svg.path.shapes-to-path :as stp]
   [app.common.types.component :as ctc]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shapes :as dwsh]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn selected-shapes-idx
  [state]
  (let [objects (dsh/lookup-page-objects state)]
    (->> (dsh/lookup-selected state)
         (cph/clean-loops objects))))

(defn create-bool-data
  [bool-type name shapes objects]
  (let [shapes (mapv #(stp/convert-to-path % objects) shapes)
        head (if (= bool-type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (empty? (:fills head)))
               (assoc :fills stp/default-bool-fills))

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
            (cts/setup-shape)
            (gsh/update-bool-selrect shapes objects))]

    [bool-shape (cph/get-position-on-parent objects (:id head))]))

(defn group->bool
  [group bool-type objects]

  (let [shapes (->> (:shapes group)
                    (map #(get objects %))
                    (mapv #(stp/convert-to-path % objects)))
        head (if (= bool-type :difference) (first shapes) (last shapes))
        head (cond-> head
               (and (contains? head :svg-attrs) (empty? (:fills head)))
               (assoc :fills stp/default-bool-fills))
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
  ([bool-type]
   (create-bool bool-type nil nil))
  ([bool-type ids {:keys [id-ret]}]
   (assert (or (nil? ids) (set? ids)))
   (ptk/reify ::create-bool-union
     ptk/WatchEvent
     (watch [it state _]
       (let [page-id (:current-page-id state)
             objects (dsh/lookup-page-objects state)
             name (-> bool-type d/name str/capital)
             ids  (->> (or ids (dsh/lookup-selected state))
                       (cph/clean-loops objects))
             ordered-indexes (cph/order-by-indexed-shapes objects ids)
             shapes (->> ordered-indexes
                         (map (d/getf objects))
                         (remove cph/frame-shape?)
                         (remove ctc/is-variant?)
                         (remove #(ctn/has-any-copy-parent? objects %)))]

         (when-not (empty? shapes)
           (let [[boolean-data index] (create-bool-data bool-type name (reverse shapes) objects)
                 index (inc index)
                 shape-id (:id boolean-data)
                 changes (-> (pcb/empty-changes it page-id)
                             (pcb/with-objects objects)
                             (pcb/add-object boolean-data {:index index})
                             (pcb/update-shapes (map :id shapes) ctl/remove-layout-item-data)
                             (pcb/change-parent shape-id shapes))]
             (when id-ret
               (reset! id-ret shape-id))

             (rx/of (dch/commit-changes changes)
                    (dws/select-shapes (d/ordered-set shape-id))))))))))

(defn group-to-bool
  [shape-id bool-type]
  (ptk/reify ::group-to-bool
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            change-to-bool
            (fn [shape] (group->bool shape bool-type objects))]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] change-to-bool {:reg-objects? true})))))))

(defn bool-to-group
  [shape-id]
  (ptk/reify ::bool-to-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            change-to-group
            (fn [shape] (bool->group shape objects))]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] change-to-group {:reg-objects? true})))))))


(defn change-bool-type
  [shape-id bool-type]
  (ptk/reify ::change-bool-type
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (dsh/lookup-page-objects state)
            change-type
            (fn [shape] (assoc shape :bool-type bool-type))]
        (when-not (ctn/has-any-copy-parent? objects (get objects shape-id))
          (rx/of (dwsh/update-shapes [shape-id] change-type {:reg-objects? true})))))))

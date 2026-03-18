;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.path.shapes-to-path
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cph]
   [app.common.geom.shapes :as gsh]
   [app.common.types.container :as ctn]
   [app.common.types.path :as path]
   [app.common.types.shape :as cts]
   [app.common.types.text :as txt]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.features :as features]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(def ^:private dissoc-attrs
  [:x :y :width :height
   :rx :ry :r1 :r2 :r3 :r4
   :metadata])

(defn convert-selected-to-path
  ([]
   (convert-selected-to-path nil))
  ([ids]
   (ptk/reify ::convert-selected-to-path
     ptk/WatchEvent
     (watch [it state _]
       (if (features/active-feature? state "render-wasm/v1")
         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected
               (->> (or ids (dsh/lookup-selected state))
                    (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               children-ids
               (into #{}
                     (mapcat #(cph/get-children-ids objects %))
                     selected)

               changes
               (-> (pcb/empty-changes it page-id)
                   (pcb/with-objects objects)
                   (pcb/update-shapes
                    selected
                    (fn [shape]
                      (let [content (wasm.api/shape-to-path (:id shape))]
                        (-> shape
                            (assoc :type :path)
                            (cond-> (cph/text-shape? shape)
                              (assoc :fills
                                     (->> (txt/node-seq txt/is-text-node? (:content shape))
                                          (map :fills)
                                          (first))))
                            (cond-> (cph/image-shape? shape)
                              (assoc :fill-image (get shape :metadata)))
                            (d/without-keys dissoc-attrs)
                            (path/update-geometry content)))))
                   (pcb/remove-objects children-ids))]
           (rx/of (dch/commit-changes changes)))

         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected (->> (or ids (dsh/lookup-selected state))
                             (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               children-ids
               (into #{}
                     (mapcat #(cph/get-children-ids objects %))
                     selected)

               changes
               (-> (pcb/empty-changes it page-id)
                   (pcb/with-objects objects)
                   (pcb/update-shapes selected path/convert-to-path {:with-objects? true})
                   (pcb/remove-objects children-ids))]

           (rx/of (dch/commit-changes changes))))))))

(defn- stroke->fill
  "Converts stroke color properties to fill color properties."
  [stroke]
  (d/without-nils
   {:fill-color           (:stroke-color stroke)
    :fill-opacity         (:stroke-opacity stroke)
    :fill-color-gradient  (:stroke-color-gradient stroke)
    :fill-image           (:stroke-image stroke)
    :fill-color-ref-id    (:stroke-color-ref-id stroke)
    :fill-color-ref-file  (:stroke-color-ref-file stroke)}))

(defn- make-stroke-paths
  "Given a shape with strokes, returns a vector of new path shapes
   created from each stroke. Uses the provided parent-id and frame-id."
  [shape parent-id frame-id]
  (into []
        (keep-indexed
         (fn [idx stroke]
           (let [content (wasm.api/stroke-to-path (:id shape) idx)]
             (when (some? content)
               (cts/setup-shape
                {:type      :path
                 :id        (uuid/next)
                 :name      (str (:name shape) " (stroke)")
                 :parent-id parent-id
                 :frame-id  frame-id
                 :content   content
                 :fills     [(stroke->fill stroke)]
                 :strokes   []})))))
        (:strokes shape)))

(defn convert-selected-strokes-to-path
  "For each selected shape, converts each stroke into a new sibling
   path shape. When the selected shape is a group/frame with stroked
   descendants, a new group is created as a sibling containing all
   the stroke paths. Strokes are then removed from processed shapes."
  ([]
   (convert-selected-strokes-to-path nil))
  ([ids]
   (ptk/reify ::convert-selected-strokes-to-path
     ptk/WatchEvent
     (watch [it state _]
       (when (features/active-feature? state "render-wasm/v1")
         (let [page-id  (:current-page-id state)
               objects  (dsh/lookup-page-objects state)
               selected (->> (or ids (dsh/lookup-selected state))
                             (remove #(ctn/has-any-copy-parent? objects (get objects %))))

               result
               (reduce
                (fn [acc shape-id]
                  (let [shape (get objects shape-id)]
                    (if (seq (:strokes shape))
                      ;; Shape itself has strokes: create stroke paths as siblings
                      (let [position   (cph/get-position-on-parent objects shape-id)
                            new-shapes (make-stroke-paths shape (:parent-id shape) (:frame-id shape))]
                        (-> acc
                            (update :entries into (map-indexed #(hash-map :new-shape %2 :index (+ (inc position) %1)) new-shapes))
                            (update :updated-ids conj shape-id)))

                      ;; Check descendants for strokes (groups, SVGs, etc.)
                      (let [child-ids  (->> (cph/get-children-ids objects shape-id)
                                            (filter #(seq (:strokes (get objects %)))))
                            group-id   (uuid/next)
                            new-shapes (into []
                                             (mapcat (fn [cid]
                                                       (make-stroke-paths (get objects cid)
                                                                          group-id
                                                                          (:frame-id shape))))
                                             child-ids)]
                        (if (seq new-shapes)
                          ;; Wrap all stroke paths in a new group
                          (let [position (cph/get-position-on-parent objects shape-id)
                                selrect  (gsh/shapes->rect new-shapes)
                                group    (cts/setup-shape
                                          {:id        group-id
                                           :type      :group
                                           :name      (str (:name shape) " (strokes)")
                                           :shapes    (mapv :id new-shapes)
                                           :selrect   selrect
                                           :x         (:x selrect)
                                           :y         (:y selrect)
                                           :width     (:width selrect)
                                           :height    (:height selrect)
                                           :parent-id (:parent-id shape)
                                           :frame-id  (:frame-id shape)})]
                            (-> acc
                                (update :groups conj {:group group :children new-shapes :index (inc position)})
                                (update :updated-ids into child-ids)))
                          acc)))))
                {:entries     []
                 :groups      []
                 :updated-ids []}
                selected)

               new-shape-ids (into []
                                   (concat
                                    (map (comp :id :new-shape) (:entries result))
                                    (map (comp :id :group) (:groups result))))

               changes
               (as-> (pcb/empty-changes it page-id) changes
                 (pcb/with-objects changes objects)

                 ;; Add ungrouped stroke path shapes as siblings
                 (reduce
                  (fn [changes {:keys [new-shape index]}]
                    (pcb/add-object changes new-shape {:index index}))
                  changes
                  (:entries result))

                 ;; Add groups with their stroke path children
                 (reduce
                  (fn [changes {:keys [group children index]}]
                    (as-> changes changes
                      (pcb/add-object changes group {:index index})
                      (reduce
                       (fn [changes child]
                         (pcb/add-object changes child {:parent-id (:id group)}))
                       changes
                       children)))
                  changes
                  (:groups result))

                 ;; Remove strokes from original shapes
                 (pcb/update-shapes changes
                                    (:updated-ids result)
                                    (fn [shape] (assoc shape :strokes []))))]

           (rx/of (dch/commit-changes changes)
                  (dws/select-shapes (into (d/ordered-set) new-shape-ids)))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.modifiers
  "Events related with shapes transformations"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.modifiers :as gm]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.shape.attrs :refer [editable-attrs]]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.constants :refer [zoom-half-pixel-precision]]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.comments :as-alias dwcm]
   [app.main.data.workspace.guides :as-alias dwg]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; -- temporary modifiers -------------------------------------------

;; During an interactive transformation of shapes (e.g. when resizing or rotating
;; a group with the mouse), there are a lot of objects that need to be modified
;; (in this case, the group and all its children).
;;
;; To avoid updating the shapes theirselves, and forcing redraw of all components
;; that depend on the "objects" global state, we set a "modifiers" structure, with
;; the changes that need to be applied, and store it in :workspace-modifiers global
;; variable. The viewport reads this and merges it into the objects list it uses to
;; paint the viewport content, redrawing only the objects that have new modifiers.
;;
;; When the interaction is finished (e.g. user releases mouse button), the
;; apply-modifiers event is done, that consolidates all modifiers into the base
;; geometric attributes of the shapes.

(defn- check-delta
  "If the shape is a component instance, check its relative position and rotation respect
  the root of the component, and see if it changes after applying a transformation."
  [shape root transformed-shape transformed-root]
  (let [shape-delta
        (when root
          (gpt/point (- (gsh/left-bound shape) (gsh/left-bound root))
                     (- (gsh/top-bound shape) (gsh/top-bound root))))

        transformed-shape-delta
        (when transformed-root
          (gpt/point (- (gsh/left-bound transformed-shape) (gsh/left-bound transformed-root))
                     (- (gsh/top-bound transformed-shape) (gsh/top-bound transformed-root))))

        distance
        (if (and shape-delta transformed-shape-delta)
          (gpt/distance-vector shape-delta transformed-shape-delta)
          (gpt/point 0 0))

        rotation-delta
        (if (and (some? (:rotation shape)) (some? (:rotation shape)))
          (- (:rotation transformed-shape) (:rotation shape))
          0)

        selrect (:selrect shape)
        transformed-selrect (:selrect transformed-shape)]

    ;; There are cases in that the coordinates change slightly (e.g. when rounding
    ;; to pixel, or when recalculating text positions in different zoom levels).
    ;; To take this into account, we ignore movements smaller than 1 pixel.
    ;;
    ;; When the change is a resize, also has a transformation that may have the
    ;; shape position unchanged. But in this case we do not want to ignore it.
    (and (and (< (:x distance) 1) (< (:y distance) 1))
         (mth/close? (:width selrect) (:width transformed-selrect))
         (mth/close? (:height selrect) (:height transformed-selrect))
         (mth/close? rotation-delta 0))))

(defn calculate-ignore-tree
  "Retrieves a map with the flag `ignore-geometry?` given a tree of modifiers"
  [modif-tree objects]

  (letfn [(get-ignore-tree
            ([ignore-tree shape]
             (let [shape-id (dm/get-prop shape :id)
                   transformed-shape (gsh/transform-shape shape (dm/get-in modif-tree [shape-id :modifiers]))

                   root
                   (if (:component-root shape)
                     shape
                     (ctn/get-component-shape objects shape {:allow-main? true}))

                   transformed-root
                   (if (:component-root shape)
                     transformed-shape
                     (gsh/transform-shape root (dm/get-in modif-tree [(:id root) :modifiers])))]

               (get-ignore-tree ignore-tree shape transformed-shape root transformed-root)))

            ([ignore-tree shape root transformed-root]
             (let [shape-id (dm/get-prop shape :id)
                   transformed-shape (gsh/transform-shape shape (dm/get-in modif-tree [shape-id :modifiers]))]
               (get-ignore-tree ignore-tree shape transformed-shape root transformed-root)))

            ([ignore-tree shape transformed-shape root transformed-root]
             (let [shape-id (dm/get-prop shape :id)

                   ignore-tree
                   (cond-> ignore-tree
                     (and (some? root) (ctk/in-component-copy? shape))
                     (assoc
                      shape-id
                      (check-delta shape root transformed-shape transformed-root)))

                   set-child
                   (fn [ignore-tree child]
                     (get-ignore-tree ignore-tree child root transformed-root))]

               (->> (:shapes shape)
                    (map (d/getf objects))
                    (reduce set-child ignore-tree)))))]

    ;; we check twice because we want only to search parents of components but once the
    ;; tree is traversed we only want to process the objects in components
    (->> (keys modif-tree)
         (map #(get objects %))
         (reduce get-ignore-tree nil))))

(defn assoc-position-data
  [shape position-data old-shape]
  (let [deltav (gpt/to-vec (gpt/point (:selrect old-shape))
                           (gpt/point (:selrect shape)))
        position-data
        (-> position-data
            (gsh/move-position-data deltav))]
    (cond-> shape
      (d/not-empty? position-data)
      (assoc :position-data position-data))))

(defn update-grow-type
  [shape old-shape]
  (let [auto-width? (= :auto-width (:grow-type shape))
        auto-height? (= :auto-height (:grow-type shape))

        changed-width? (> (mth/abs (- (:width shape) (:width old-shape))) 0.1)
        changed-height? (> (mth/abs (- (:height shape) (:height old-shape))) 0.1)

        change-to-fixed? (or (and auto-width? (or changed-height? changed-width?))
                             (and auto-height? changed-height?))]
    (cond-> shape
      change-to-fixed?
      (assoc :grow-type :fixed))))

(defn- clear-local-transform []
  (ptk/reify ::clear-local-transform
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (dissoc :workspace-modifiers)
          (dissoc :app.main.data.workspace.transforms/current-move-selected)))))

(defn create-modif-tree
  [ids modifiers]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))
  (into {} (map #(vector % {:modifiers modifiers})) ids))

(defn build-modif-tree
  [ids objects get-modifier]
  (dm/assert!
   "expected valid coll of uuids"
   (every? uuid? ids))
  (into {} (map #(vector % {:modifiers (get-modifier (get objects %))})) ids))

(defn modifier-remove-from-parent
  [modif-tree objects shapes]
  (->> shapes
       (reduce
        (fn [modif-tree child-id]
          (let [parent-id (get-in objects [child-id :parent-id])]
            (update-in modif-tree [parent-id :modifiers] ctm/remove-children [child-id])))
        modif-tree)))

(defn add-grid-children-modifiers
  [modifiers frame-id shapes objects [row column :as cell]]
  (let [frame (get objects frame-id)
        ids (set shapes)

        ;; Temporary remove the children when moving them
        frame (-> frame
                  (update :shapes #(d/removev ids %))
                  (ctl/assign-cells objects))

        ids (->> ids
                 (remove #(ctl/position-absolute? objects %))
                 (ctst/sort-z-index objects)
                 reverse)

        frame (-> frame
                  (update :shapes d/concat-vec ids)
                  (cond-> (some? cell)
                    (ctl/push-into-cell ids row column))
                  (ctl/assign-cells objects))]
    (-> modifiers
        (ctm/change-property :layout-grid-rows (:layout-grid-rows frame))
        (ctm/change-property :layout-grid-columns (:layout-grid-columns frame))
        (ctm/change-property :layout-grid-cells (:layout-grid-cells frame)))))

(defn build-change-frame-modifiers
  [modif-tree objects selected target-frame-id drop-index cell-data]

  (let [origin-frame-ids (->> selected (group-by #(get-in objects [% :frame-id])))
        child-set (set (get-in objects [target-frame-id :shapes]))

        target-frame        (get objects target-frame-id)
        target-flex-layout? (ctl/flex-layout? target-frame)
        target-grid-layout? (ctl/grid-layout? target-frame)

        children-ids (concat (:shapes target-frame) selected)

        set-parent-ids
        (fn [modif-tree shapes target-frame-id]
          (reduce
           (fn [modif-tree id]
             (update-in
              modif-tree
              [id :modifiers]
              #(-> %
                   (ctm/change-property :frame-id target-frame-id)
                   (ctm/change-property :parent-id target-frame-id))))
           modif-tree
           shapes))

        update-frame-modifiers
        (fn [modif-tree [original-frame shapes]]
          (let [shapes (->> shapes (d/removev #(= target-frame-id %)))
                shapes (cond->> shapes
                         (and (or target-grid-layout? target-flex-layout?)
                              (= original-frame target-frame-id))
                         ;; When movining inside a layout frame remove the shapes that are not immediate children
                         (filterv #(contains? child-set %)))
                children-ids (->> (dm/get-in objects [original-frame :shapes])
                                  (remove (set selected)))
                h-sizing? (and (ctl/flex-layout? objects original-frame)
                               (ctl/change-h-sizing? original-frame objects children-ids))
                v-sizing? (and (ctl/flex-layout? objects original-frame)
                               (ctl/change-v-sizing? original-frame objects children-ids))]
            (cond-> modif-tree
              (not= original-frame target-frame-id)
              (-> (modifier-remove-from-parent objects shapes)
                  (update-in [target-frame-id :modifiers] ctm/add-children shapes drop-index)
                  (set-parent-ids shapes target-frame-id)
                  (cond-> h-sizing?
                    (update-in [original-frame :modifiers] ctm/change-property :layout-item-h-sizing :fix))
                  (cond-> v-sizing?
                    (update-in [original-frame :modifiers] ctm/change-property :layout-item-v-sizing :fix)))

              (and target-flex-layout? (= original-frame target-frame-id))
              (update-in [target-frame-id :modifiers] ctm/add-children shapes drop-index)

              ;; Add the object to the cell
              target-grid-layout?
              (update-in [target-frame-id :modifiers] add-grid-children-modifiers target-frame-id shapes objects cell-data))))]

    (as-> modif-tree $
      (reduce update-frame-modifiers $ origin-frame-ids)
      (cond-> $
        ;; Set fix position to target frame (horizontal)
        (and (ctl/flex-layout? objects target-frame-id)
             (ctl/change-h-sizing? target-frame-id objects children-ids))
        (update-in [target-frame-id :modifiers] ctm/change-property :layout-item-h-sizing :fix)

        ;; Set fix position to target frame (vertical)
        (and (ctl/flex-layout? objects target-frame-id)
             (ctl/change-v-sizing? target-frame-id objects children-ids))
        (update-in [target-frame-id :modifiers] ctm/change-property :layout-item-v-sizing :fix)))))

(defn modif->js
  [modif-tree objects]
  (clj->js (into {}
                 (map (fn [[k v]]
                        [(get-in objects [k :name]) v]))
                 modif-tree)))

(defn apply-text-modifier
  [shape {:keys [width height]}]
  (cond-> shape
    (some? width)
    (gsh/transform-shape (ctm/change-dimensions-modifiers shape :width width {:ignore-lock? true}))

    (some? height)
    (gsh/transform-shape (ctm/change-dimensions-modifiers shape :height height {:ignore-lock? true}))))

(defn apply-text-modifiers
  [objects text-modifiers]
  (loop [modifiers (seq text-modifiers)
         result objects]
    (if (empty? modifiers)
      result
      (let [[id text-modifier] (first modifiers)]
        (recur (rest modifiers)
               (d/update-when result id apply-text-modifier text-modifier))))))

#_(defn apply-path-modifiers
    [objects path-modifiers]
    (letfn [(apply-path-modifier
              [shape {:keys [content-modifiers]}]
              (let [shape (update shape :content upc/apply-content-modifiers content-modifiers)
                    [points selrect] (helpers/content->points+selrect shape (:content shape))]
                (assoc shape :selrect selrect :points points)))]
      (loop [modifiers (seq path-modifiers)
             result objects]
        (if (empty? modifiers)
          result
          (let [[id path-modifier] (first modifiers)]
            (recur (rest modifiers)
                   (update objects id apply-path-modifier path-modifier)))))))

(defn- calculate-modifiers
  ([state modif-tree]
   (calculate-modifiers state false false modif-tree))

  ([state ignore-constraints ignore-snap-pixel modif-tree]
   (calculate-modifiers state ignore-constraints ignore-snap-pixel modif-tree nil))

  ([state ignore-constraints ignore-snap-pixel modif-tree params]
   (let [objects
         (dsh/lookup-page-objects state)

         snap-pixel?
         (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

         zoom (dm/get-in state [:workspace-local :zoom])
         snap-precision (if (>= zoom zoom-half-pixel-precision) 0.5 1)]

     (as-> objects $
       (apply-text-modifiers $ (get state :workspace-text-modifier))
       ;;(apply-path-modifiers $ (get-in state [:workspace-local :edit-path]))
       (gm/set-objects-modifiers modif-tree $ (merge
                                               params
                                               {:ignore-constraints ignore-constraints
                                                :snap-pixel? snap-pixel?
                                                :snap-precision snap-precision}))))))

(defn- calculate-update-modifiers
  [old-modif-tree state ignore-constraints ignore-snap-pixel modif-tree]
  (let [objects
        (dsh/lookup-page-objects state)

        snap-pixel?
        (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

        zoom (dm/get-in state [:workspace-local :zoom])

        snap-precision (if (>= zoom zoom-half-pixel-precision) 0.5 1)
        objects
        (-> objects
            (apply-text-modifiers (get state :workspace-text-modifier)))]

    (gm/set-objects-modifiers
     old-modif-tree
     modif-tree
     objects
     {:ignore-constraints ignore-constraints
      :snap-pixel? snap-pixel?
      :snap-precision snap-precision})))

(defn update-modifiers
  ([modif-tree]
   (update-modifiers modif-tree false))

  ([modif-tree ignore-constraints]
   (update-modifiers modif-tree ignore-constraints false))

  ([modif-tree ignore-constraints ignore-snap-pixel]
   (ptk/reify ::update-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (update state :workspace-modifiers calculate-update-modifiers state ignore-constraints ignore-snap-pixel modif-tree)))))

(defn set-modifiers
  ([modif-tree]
   (set-modifiers modif-tree false))

  ([modif-tree ignore-constraints]
   (set-modifiers modif-tree ignore-constraints false))

  ([modif-tree ignore-constraints ignore-snap-pixel]
   (set-modifiers modif-tree ignore-constraints ignore-snap-pixel nil))

  ([modif-tree ignore-constraints ignore-snap-pixel params]
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (assoc state :workspace-modifiers (calculate-modifiers state ignore-constraints ignore-snap-pixel modif-tree params))))))

(def ^:private
  xf-rotation-shape
  (comp
   (remove #(get % :blocked false))
   (filter #(:rotation (get editable-attrs (:type %))))
   (map :id)))

;; Rotation use different algorithm to calculate children
;; modifiers (and do not use child constraints).
(defn set-rotation-modifiers
  ([angle shapes]
   (set-rotation-modifiers angle shapes (-> shapes gsh/shapes->rect grc/rect->center)))

  ([angle shapes center]
   (ptk/reify ::set-rotation-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects (dsh/lookup-page-objects state)
             ids     (sequence xf-rotation-shape shapes)

             get-modifier
             (fn [shape]
               (ctm/rotation-modifiers shape center angle))

             modif-tree
             (-> (build-modif-tree ids objects get-modifier)
                 (gm/set-objects-modifiers objects))]

         (assoc state :workspace-modifiers modif-tree))))))

;; This function is similar to set-rotation-modifiers but:
;; - It consideres the center for everyshape instead of the center of the total selrect
;; - The angle param is the desired final value, not a delta
(defn set-delta-rotation-modifiers
  [angle shapes {:keys [center delta?] :or {center nil delta? false}}]
  (ptk/reify ::set-delta-rotation-modifiers
    ptk/UpdateEvent
    (update [_ state]
      (let [objects (dsh/lookup-page-objects state)
            ids
            (->> shapes
                 (remove #(get % :blocked false))
                 (filter #(contains? (get editable-attrs (:type %)) :rotation))
                 (map :id))

            get-modifier
            (fn [shape]
              (let [delta  (if delta? angle (- angle (:rotation shape)))
                    center (or center (gsh/shape->center shape))]
                (ctm/rotation-modifiers shape center delta)))

            modif-tree
            (-> (build-modif-tree ids objects get-modifier)
                (gm/set-objects-modifiers objects))]

        (assoc state :workspace-modifiers modif-tree)))))

(defn apply-modifiers
  ([]
   (apply-modifiers nil))

  ([{:keys [modifiers undo-transation? stack-undo? ignore-constraints
            ignore-snap-pixel ignore-touched undo-group]
     :or {undo-transation? true stack-undo? false ignore-constraints false
          ignore-snap-pixel false ignore-touched false}}]
   (ptk/reify ::apply-modifiers
     ptk/WatchEvent
     (watch [_ state _]
       (let [text-modifiers    (get state :workspace-text-modifier)
             objects           (dsh/lookup-page-objects state)

             object-modifiers
             (if (some? modifiers)
               (calculate-modifiers state ignore-constraints ignore-snap-pixel modifiers)
               (get state :workspace-modifiers))

             ids
             (into []
                   (remove #(= % uuid/zero))
                   (keys object-modifiers))

             ids-with-children
             (into ids
                   (mapcat (partial cfh/get-children-ids objects))
                   ids)

             ignore-tree
             (calculate-ignore-tree object-modifiers objects)

             undo-id     (js/Symbol)]

         (rx/concat
          (if undo-transation?
            (rx/of (dwu/start-undo-transaction undo-id))
            (rx/empty))
          (rx/of (ptk/event ::dwg/move-frame-guides {:ids ids-with-children :modifiers object-modifiers})
                 (ptk/event ::dwcm/move-frame-comment-threads ids-with-children)
                 (dwsh/update-shapes
                  ids
                  (fn [shape]
                    (let [modif (get-in object-modifiers [(:id shape) :modifiers])
                          text-shape? (cfh/text-shape? shape)
                          position-data (when text-shape?
                                          (dm/get-in text-modifiers [(:id shape) :position-data]))]
                      (-> shape
                          (gsh/transform-shape modif)
                          (cond-> (d/not-empty? position-data)
                            (assoc-position-data position-data shape))
                          (cond-> text-shape?
                            (update-grow-type shape)))))
                  {:reg-objects? true
                   :stack-undo? stack-undo?
                   :ignore-tree ignore-tree
                   :ignore-touched ignore-touched
                   :undo-group undo-group
                   ;; Attributes that can change in the transform. This way we don't have to check
                   ;; all the attributes
                   :attrs [:selrect
                           :points
                           :x
                           :y
                           :r1
                           :r2
                           :r3
                           :r4
                           :shadow
                           :blur
                           :strokes
                           :width
                           :height
                           :content
                           :transform
                           :transform-inverse
                           :rotation
                           :flip-x
                           :flip-y
                           :grow-type
                           :position-data
                           :layout-gap
                           :layout-padding
                           :layout-item-h-sizing
                           :layout-item-margin
                           :layout-item-max-h
                           :layout-item-max-w
                           :layout-item-min-h
                           :layout-item-min-w
                           :layout-item-v-sizing
                           :layout-padding-type
                           :layout-gap
                           :layout-item-margin
                           :layout-item-margin-type
                           :layout-grid-cells
                           :layout-grid-columns
                           :layout-grid-rows]})
                 ;; We've applied the text-modifier so we can dissoc the temporary data
                 (fn [state]
                   (update state :workspace-text-modifier #(apply dissoc % ids))))
          (if (nil? modifiers)
            (rx/of (clear-local-transform))
            (rx/empty))
          (if undo-transation?
            (rx/of (dwu/commit-undo-transaction undo-id))
            (rx/empty))))))))

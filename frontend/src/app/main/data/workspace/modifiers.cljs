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
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.common :as cpc]
   [app.common.pages.helpers :as cph]
   [app.common.types.container :as ctn]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.constants :refer [zoom-half-pixel-precision]]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.comments :as-alias dwcm]
   [app.main.data.workspace.guides :as-alias dwg]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.core :as rx]
   [potok.core :as ptk]))

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
  "If the shape is a component instance, check its relative position respect the
  root of the component, and see if it changes after applying a transformation."
  [shape root transformed-shape transformed-root objects modif-tree]
  (let [root
        (cond
          (:component-root? shape)
          shape

          (nil? root)
          (ctn/get-component-shape objects shape {:allow-main? true})

          :else root)

        transformed-root
        (cond
          (:component-root? transformed-shape)
          transformed-shape

          (nil? transformed-root)
          (as-> (ctn/get-component-shape objects transformed-shape {:allow-main? true}) $
            (gsh/transform-shape (merge $ (get modif-tree (:id $)))))

          :else transformed-root)

        shape-delta
        (when root
          (gpt/point (- (gsh/left-bound shape) (gsh/left-bound root))
                     (- (gsh/top-bound shape) (gsh/top-bound root))))

        transformed-shape-delta
        (when transformed-root
          (gpt/point (- (gsh/left-bound transformed-shape) (gsh/left-bound transformed-root))
                     (- (gsh/top-bound transformed-shape) (gsh/top-bound transformed-root))))

        distance (if (and shape-delta transformed-shape-delta)
                   (gpt/distance-vector shape-delta transformed-shape-delta)
                   (gpt/point 0 0))

        selrect (:selrect shape)
        transformed-selrect (:selrect transformed-shape)

        ;; There are cases in that the coordinates change slightly (e.g. when rounding
        ;; to pixel, or when recalculating text positions in different zoom levels).
        ;; To take this into account, we ignore movements smaller than 1 pixel.
        ;;
        ;; When the change is a resize, also has a transformation that may have the
        ;; shape position unchanged. But in this case we do not want to ignore it.
        ignore-geometry? (and (and (< (:x distance) 1) (< (:y distance) 1))
                              (mth/close? (:width selrect) (:width transformed-selrect))
                              (mth/close? (:height selrect) (:height transformed-selrect)))]
    [root transformed-root ignore-geometry?]))

(defn- get-ignore-tree
  "Retrieves a map with the flag `ignore-geometry?` given a tree of modifiers"
  ([modif-tree objects shape]
   (get-ignore-tree modif-tree objects shape nil nil {}))

  ([modif-tree objects shape root transformed-root ignore-tree]
   (let [children (map (d/getf objects) (:shapes shape))

         shape-id (:id shape)
         transformed-shape (gsh/transform-shape shape (dm/get-in modif-tree [shape-id :modifiers]))

         [root transformed-root ignore-geometry?]
         (check-delta shape root transformed-shape transformed-root objects modif-tree)

         ignore-tree (assoc ignore-tree shape-id ignore-geometry?)

         set-child
         (fn [ignore-tree child]
           (get-ignore-tree modif-tree objects child root transformed-root ignore-tree))]

     (reduce set-child ignore-tree children))))

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

(defn build-change-frame-modifiers
  [modif-tree objects selected target-frame-id drop-index]

  (let [origin-frame-ids (->> selected (group-by #(get-in objects [% :frame-id])))
        child-set (set (get-in objects [target-frame-id :shapes]))

        target-frame        (get objects target-frame-id)
        target-flex-layout? (ctl/flex-layout? target-frame)

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
                         (and target-flex-layout? (= original-frame target-frame-id))
                         ;; When movining inside a layout frame remove the shapes that are not immediate children
                         (filterv #(contains? child-set %)))
                children-ids (->> (dm/get-in objects [original-frame :shapes])
                                  (remove (set selected)))

                h-sizing? (ctl/change-h-sizing? original-frame objects children-ids)
                v-sizing? (ctl/change-v-sizing? original-frame objects children-ids)]
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
              (update-in [target-frame-id :modifiers] ctm/add-children shapes drop-index))))]

    (as-> modif-tree $
      (reduce update-frame-modifiers $ origin-frame-ids)
      (cond-> $
        (ctl/change-h-sizing? target-frame-id objects children-ids)
        (update-in [target-frame-id :modifiers] ctm/change-property :layout-item-h-sizing :fix))
      (cond-> $
        (ctl/change-v-sizing? target-frame-id objects children-ids)
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
         (wsh/lookup-page-objects state)

         snap-pixel?
         (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

         zoom (dm/get-in state [:workspace-local :zoom])
         snap-precision (if (>= zoom zoom-half-pixel-precision) 0.5 1)]

     (as-> objects $
       (apply-text-modifiers $ (get state :workspace-text-modifier))
       ;;(apply-path-modifiers $ (get-in state [:workspace-local :edit-path]))
       (gsh/set-objects-modifiers modif-tree $ (merge
                                                params
                                                {:ignore-constraints ignore-constraints
                                                 :snap-pixel? snap-pixel?
                                                 :snap-precision snap-precision}))))))

(defn- calculate-update-modifiers
  [old-modif-tree state ignore-constraints ignore-snap-pixel modif-tree]
  (let [objects
        (wsh/lookup-page-objects state)

        snap-pixel?
        (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

        zoom (dm/get-in state [:workspace-local :zoom])

        snap-precision (if (>= zoom zoom-half-pixel-precision) 0.5 1)
        objects
        (-> objects
            (apply-text-modifiers (get state :workspace-text-modifier)))]
    (gsh/set-objects-modifiers old-modif-tree modif-tree objects {:ignore-constraints ignore-constraints :snap-pixel? snap-pixel? :snap-precision snap-precision})))

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

;; Rotation use different algorithm to calculate children modifiers (and do not use child constraints).
(defn set-rotation-modifiers
  ([angle shapes]
   (set-rotation-modifiers angle shapes (-> shapes gsh/selection-rect gsh/center-selrect)))

  ([angle shapes center]
   (ptk/reify ::set-rotation-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects     (wsh/lookup-page-objects state)
             ids
             (->> shapes
                  (remove #(get % :blocked false))
                  (filter #((cpc/editable-attrs (:type %)) :rotation))
                  (map :id))

             get-modifier
             (fn [shape]
               (ctm/rotation-modifiers shape center angle))

             modif-tree
             (-> (build-modif-tree ids objects get-modifier)
                 (gsh/set-objects-modifiers objects))]

         (assoc state :workspace-modifiers modif-tree))))))

;; This function is similar to set-rotation-modifiers but:
;; - It consideres the center for everyshape instead of the center of the total selrect
;; - The angle param is the desired final value, not a delta
(defn set-delta-rotation-modifiers
  ([angle shapes]
   (ptk/reify ::set-delta-rotation-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects     (wsh/lookup-page-objects state)
             ids
             (->> shapes
                  (remove #(get % :blocked false))
                  (filter #((cpc/editable-attrs (:type %)) :rotation))
                  (map :id))

             get-modifier
             (fn [shape]
               (let [delta  (- angle (:rotation shape))
                     center (gsh/center-shape shape)]
                 (ctm/rotation-modifiers shape center delta)))

             modif-tree
             (-> (build-modif-tree ids objects get-modifier)
                 (gsh/set-objects-modifiers objects))]

         (assoc state :workspace-modifiers modif-tree))))))

(defn apply-modifiers
  ([]
   (apply-modifiers nil))

  ([{:keys [modifiers undo-transation? stack-undo?] :or {undo-transation? true stack-undo? false}}]
   (ptk/reify ::apply-modifiers
     ptk/WatchEvent
     (watch [_ state _]
       (let [text-modifiers    (get state :workspace-text-modifier)
             objects           (wsh/lookup-page-objects state)
             object-modifiers  (if modifiers
                                 (calculate-modifiers state modifiers)
                                 (get state :workspace-modifiers))

             ids (or (keys object-modifiers) [])
             ids-with-children (into (vec ids) (mapcat #(cph/get-children-ids objects %)) ids)

             shapes            (map (d/getf objects) ids)
             ignore-tree       (->> (map #(get-ignore-tree object-modifiers objects %) shapes)
                                    (reduce merge {}))
             undo-id (js/Symbol)]

         (rx/concat
          (if undo-transation?
            (rx/of (dwu/start-undo-transaction undo-id))
            (rx/empty))
          (rx/of (ptk/event ::dwg/move-frame-guides ids-with-children)
                 (ptk/event ::dwcm/move-frame-comment-threads ids-with-children)
                 (dch/update-shapes
                  ids
                  (fn [shape]
                    (let [modif (get-in object-modifiers [(:id shape) :modifiers])
                          text-shape? (cph/text-shape? shape)
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
                   ;; Attributes that can change in the transform. This way we don't have to check
                   ;; all the attributes
                   :attrs [:selrect
                           :points
                           :x
                           :y
                           :rx
                           :ry
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
                           ]})
                 ;; We've applied the text-modifier so we can dissoc the temporary data
                 (fn [state]
                   (update state :workspace-text-modifier #(apply dissoc % ids)))
                 (clear-local-transform))
          (if undo-transation?
            (rx/of (dwu/commit-undo-transaction undo-id))
            (rx/empty))))))))

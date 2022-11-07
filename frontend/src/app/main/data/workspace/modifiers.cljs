;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.modifiers
  "Events related with shapes transformations"
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.pages.common :as cpc]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.comments :as-alias dwcm]
   [app.main.data.workspace.guides :as-alias dwg]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
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
          (cph/get-root-shape objects shape)

          :else root)

        transformed-root
        (cond
          (:component-root? transformed-shape)
          transformed-shape

          (nil? transformed-root)
          (as-> (cph/get-root-shape objects transformed-shape) $
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

        ;; There are cases in that the coordinates change slightly (e.g. when
        ;; rounding to pixel, or when recalculating text positions in different
        ;; zoom levels). To take this into account, we ignore movements smaller
        ;; than 1 pixel.
        distance (if (and shape-delta transformed-shape-delta)
                   (gpt/distance-vector shape-delta transformed-shape-delta)
                   (gpt/point 0 0))

        ignore-geometry? (and (< (:x distance) 1) (< (:y distance) 1))]

    [root transformed-root ignore-geometry?]))

(defn- get-ignore-tree
  "Retrieves a map with the flag `ignore-geometry?` given a tree of modifiers"
  ([modif-tree objects shape]
   (get-ignore-tree modif-tree objects shape nil nil {}))

  ([modif-tree objects shape root transformed-root ignore-tree]
   (let [children (map (d/getf objects) (:shapes shape))

         shape-id (:id shape)
         transformed-shape (gsh/transform-shape shape (get modif-tree shape-id))

         [root transformed-root ignore-geometry?]
         (check-delta shape root transformed-shape transformed-root objects modif-tree)

         ignore-tree (assoc ignore-tree shape-id ignore-geometry?)

         set-child
         (fn [ignore-tree child]
           (get-ignore-tree modif-tree objects child root transformed-root ignore-tree))]

     (reduce set-child ignore-tree children))))

(defn- update-grow-type
  [shape old-shape]
  (let [auto-width? (= :auto-width (:grow-type shape))
        auto-height? (= :auto-height (:grow-type shape))

        changed-width? (not (mth/close? (:width shape) (:width old-shape)))
        changed-height? (not (mth/close? (:height shape) (:height old-shape)))

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
          (dissoc ::current-move-selected)))))

(defn create-modif-tree
  [ids modifiers]
  (us/verify (s/coll-of uuid?) ids)
  (into {} (map #(vector % {:modifiers modifiers})) ids))

(defn build-modif-tree
  [ids objects get-modifier]
  (us/verify (s/coll-of uuid?) ids)
  (into {} (map #(vector % {:modifiers (get-modifier (get objects %))})) ids))

(defn build-change-frame-modifiers
  [modif-tree objects selected target-frame drop-index]

  (let [origin-frame-ids (->> selected (group-by #(get-in objects [% :frame-id])))
        child-set (set (get-in objects [target-frame :shapes]))
        layout? (ctl/layout? objects target-frame)

        update-frame-modifiers
        (fn [modif-tree [original-frame shapes]]
          (let [shapes (->> shapes (d/removev #(= target-frame %)))
                shapes (cond->> shapes
                         (and layout? (= original-frame target-frame))
                         ;; When movining inside a layout frame remove the shapes that are not immediate children
                         (filterv #(contains? child-set %)))]
            (cond-> modif-tree
              (not= original-frame target-frame)
              (-> (update-in [original-frame :modifiers] ctm/remove-children shapes)
                  (update-in [target-frame :modifiers] ctm/add-children shapes drop-index))

              (and layout? (= original-frame target-frame))
              (update-in [target-frame :modifiers] ctm/add-children shapes drop-index))))]

    (reduce update-frame-modifiers modif-tree origin-frame-ids)))

(defn modif->js
     [modif-tree objects]
     (clj->js (into {}
                    (map (fn [[k v]]
                           [(get-in objects [k :name]) v]))
                    modif-tree)))

(defn set-modifiers
  ([modif-tree]
   (set-modifiers modif-tree false))

  ([modif-tree ignore-constraints]
   (set-modifiers modif-tree ignore-constraints false))

  ([modif-tree ignore-constraints ignore-snap-pixel]
   (ptk/reify ::set-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects
             (wsh/lookup-page-objects state)

             snap-pixel?
             (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

             modif-tree
             (gsh/set-objects-modifiers modif-tree objects ignore-constraints snap-pixel?)]

         (assoc state :workspace-modifiers modif-tree))))))

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
                 (gsh/set-objects-modifiers objects false false))]

         (assoc state :workspace-modifiers modif-tree))))))


(defn apply-modifiers
  ([]
   (apply-modifiers nil))

  ([{:keys [undo-transation?] :or {undo-transation? true}}]
   (ptk/reify ::apply-modifiers
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects           (wsh/lookup-page-objects state)
             object-modifiers  (get state :workspace-modifiers)

             ids (or (keys object-modifiers) [])
             ids-with-children (into (vec ids) (mapcat #(cph/get-children-ids objects %)) ids)

             shapes            (map (d/getf objects) ids)
             ignore-tree       (->> (map #(get-ignore-tree object-modifiers objects %) shapes)
                                    (reduce merge {}))]

         (rx/concat
          (if undo-transation?
            (rx/of (dwu/start-undo-transaction))
            (rx/empty))
          (rx/of (ptk/event ::dwg/move-frame-guides ids-with-children)
                 (ptk/event ::dwcm/move-frame-comment-threads ids-with-children)
                 (dch/update-shapes
                  ids
                  (fn [shape]
                    (let [modif (get-in object-modifiers [(:id shape) :modifiers])
                          text-shape? (cph/text-shape? shape)]
                      (-> shape
                          (gsh/transform-shape modif)
                          (cond-> text-shape?
                            (update-grow-type shape)))))
                  {:reg-objects? true
                   :ignore-tree ignore-tree
                   ;; Attributes that can change in the transform. This way we don't have to check
                   ;; all the attributes
                   :attrs [:selrect
                           :points
                           :x
                           :y
                           :width
                           :height
                           :content
                           :transform
                           :transform-inverse
                           :rotation
                           :position-data
                           :flip-x
                           :flip-y
                           :grow-type
                           :layout-item-h-sizing
                           :layout-item-v-sizing
                           ]})
                 (clear-local-transform))
          (if undo-transation?
            (rx/of (dwu/commit-undo-transaction))
            (rx/empty))))))))

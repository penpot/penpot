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
   [app.common.geom.shapes.rect :as gpr]
   [app.common.math :as mth]
   [app.common.pages.common :as cpc]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.modifiers :as ctm]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.shape :as cts]
   [app.common.types.shape.layout :as ctl]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.comments :as-alias dwcm]
   [app.main.data.workspace.guides :as-alias dwg]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [beicon.core :as rx]
   [cljs.spec.alpha :as s]
   [potok.core :as ptk]))

;; -- copies --------------------------------------------------------

;; TBD...

(defn- get-copies
  "If one or more of the shapes belongs to a component's main instance, find all copies of
  the component in the same page.
 
  Return a map {<main-root-id> [<main-root> [<copy-root> <copy-root>...]] ...}"
  [shapes objects modif-tree]
  (debug/logjs "==================================" "")
  (debug/logjs "modif-tree" modif-tree)
  (letfn [(get-copies-one [shape]
            (let [root-shape (ctn/get-root-shape objects shape)]
              (when (:main-instance? root-shape)
                (let [children (->> root-shape
                                    :shapes
                                    (map #(get objects %))
                                    ;; (map #(gsh/transform-shape % (get-in modif-tree [(:id %) :modifiers])))
                                    )

                      get-bounds (fn [shape]
                                   (let [modifiers (-> (get modif-tree (:id shape)) :modifiers)]
                                     (cond-> (:points shape)
                                       (some? modifiers)
                                       (gsh/transform-bounds modifiers))))

                      ;; ;; Update the bounds of the root group to accomodate the moved shapes,
                      ;; ;; so its position is also synced to the copy root later.
                      ;; root-shape (gsh/update-group-selrect root-shape children)
                      root-shape (assoc root-shape
                                       :modif-selrect
                                       (-> (mapcat get-bounds children)
                                           (gpr/points->selrect)))
                      ]
                  [(:id root-shape) [root-shape (ctn/get-instances objects root-shape)]]))))]

    (into {} (map get-copies-one shapes))))

(defn- sync-shape
  [main-shape copy-shape main-root copy-root modif-tree]
  (debug/logjs "+++" "")
  (debug/logjs "main-shape" main-shape)
  (debug/logjs "copy-shape" copy-shape)
  (debug/logjs "main-root" main-root)
  (debug/logjs "copy-root" copy-root)
  (let [;root-modif (-> modif-tree (:id main-root) :modifiers)
        ;shape-modif (-> modif-tree (:id main-shape) :modifiers)
        orig (fn [obj] (gpt/point (:x obj) (:y obj)))

        get-displacement (fn [shape]
                           ;; Accumulate all :move modifiers of a shape
                           (let [modifiers (-> (get modif-tree (:id shape)) :modifiers)]
                             (reduce (fn [move modifier]
                                       (if (= (:type modifier) :move)
                                         (gpt/add move (:vector modifier))
                                         move))
                                     (gpt/point 0 0)
                                     (:geometry-child modifiers))))

        ;; Distance from main-root to copy-root
        root-delta (gpt/subtract (orig copy-root) (orig main-root))

        ;; Displacement from main-root to modified main-root
        root-displacement (gpt/subtract (orig (:modif-selrect main-root))
                                        (orig (:selrect main-root)))

        ;; Displacement to apply to the copy shape
        shape-displacement (gpt/subtract (get-displacement main-shape)
                                         root-displacement)

        copy-rotation (fn [acc shape]
                       (let [modifiers (-> (get modif-tree (:id shape)) :modifiers)]
                         (reduce (fn [acc modifier]
                                   (if (= (:type modifier) :rotation)
                                     (let [center (:center modifier)
                                           rotation (:rotation modifier)]
                                       (ctm/rotation acc
                                                     (when (some? center)
                                                       (gpt/add center root-delta))
                                                     rotation))
                                     acc))
                                 acc
                                 (:geometry-child modifiers))))
        ]
    (debug/logjs "root-displacement" root-displacement)
    (debug/logjs "shape-displacement (antes)" (get-displacement main-shape))
    (debug/logjs "shape-displacement" shape-displacement)
    (-> (ctm/empty)
        ;; (ctm/rotation center rotation)
        (ctm/move shape-displacement)
        (copy-rotation main-shape)
        (vary-meta assoc :copied-modifier? true))))

;; $$ algoritmo tipo component sync (reposicionando la shape)
;; (defn- reposition-shape
;;   [shape origin-root dest-root]
;;   ;; (debug.logjs "+++" "")
;;   ;; (debug.logjs "shape" shape)
;;   ;; (debug.logjs "origin-root" origin-root)
;;   ;; (debug.logjs "dest-root" dest-root)
;;   (let [shape-pos (fn [shape]
;;                     (gpt/point (get-in shape [:selrect :x])
;;                                (get-in shape [:selrect :y])))
;;
;;         origin-root-pos (shape-pos origin-root)
;;         dest-root-pos   (shape-pos dest-root)
;;         delta           (gpt/subtract dest-root-pos origin-root-pos)]
;;     (gsh/move shape delta)))
;;
;; (defn- sync-shape
;;   [main-shape copy-shape main-root copy-root]
;;   (debug/logjs "+++" "")
;;   (debug/logjs "main-shape" main-shape)
;;   (debug/logjs "copy-shape" copy-shape)
;;   (debug/logjs "main-root" main-root)
;;   (debug/logjs "copy-root" copy-root)
;;   (if (ctk/touched-group? copy-shape :geometry-group)
;;     {}
;;     (let [modif-shape (reposition-shape (:modif-shape main-shape) main-root copy-root)
;;
;;           ;; _ (when (not= (:name main-shape) "Rect-1xx")
;;           ;;     (debug.logjs "+++" "")
;;           ;;     (debug.logjs "main-shape" main-shape)
;;           ;;     (debug.logjs "copy-shape" copy-shape)
;;           ;;     (debug.logjs "main-root" main-root)
;;           ;;     (debug.logjs "copy-root" copy-root)
;;           ;;     (debug.logjs "modif-shape" modif-shape))
;;           translation (gpt/subtract (gsh/orig-pos modif-shape)
;;                                     (gsh/orig-pos copy-shape))
;;
;;           orig        (gsh/orig-pos copy-shape)
;;           mult-w      (/ (gsh/width modif-shape) (gsh/width copy-shape))
;;           mult-h      (/ (gsh/height modif-shape) (gsh/height copy-shape))
;;           resize      (gpt/point mult-w mult-h)
;;
;;           center      (gsh/center-shape copy-shape)
;;           rotation    (- (:rotation modif-shape)
;;                          (:rotation copy-shape))]
;;
;;         ;; (debug/logjs "..." "")
;;         ;; (debug/logjs "translation" translation)
;;         ;; (debug/logjs "resize" resize)
;;         ;; (debug/logjs "orig" orig)
;;         ;; (debug/logjs "rotation" rotation)
;;         ;; (debug/logjs "center" center)
;;         (-> (ctm/empty)
;;             (ctm/rotation center rotation)
;;             (ctm/move translation)
;;             (ctm/resize resize orig)
;;             (vary-meta assoc :copied-modifier? true)))))

;; =========nofun
;; (defn- shape-delta
;;   [origin-shape dest-shape]
;;   (let [shape-pos (fn [shape]
;;                     (gpt/point (get-in shape [:selrect :x])
;;                                (get-in shape [:selrect :y])))
;;
;;         origin-pos (shape-pos origin-shape)
;;         dest-pos   (shape-pos dest-shape)]
;;     (gpt/subtract dest-pos origin-pos)))
;;
;; (defn- sync-shape
;;   [main-shape copy-shape main-root copy-root]
;;   (let [delta     (shape-delta main-root copy-root)
;;         modifiers (:modifiers main-shape)
;;
;;         copy-geometry
;;         (fn [ops op]
;;           (let [copy-op (case (:type op)
;;                           :move op
;;                           :resize (update op :origin gpt/add delta)
;;                           :rotation (update op :center gpt/add delta)
;;                           nil)]
;;             (if (some? copy-op)
;;               (conj ops op)
;;               ops)))
;;
;;         copy-structure
;;         (fn [ops op]
;;           (conj ops op))]
;;
;;     (cond-> {}
;;       (:geometry-parent modifiers)
;;       (assoc :geometry-parent (reduce copy-geometry [] (:geometry-parent modifiers)))
;;
;;       (:geometry-child modifiers)
;;       (assoc :geometry-child (reduce copy-geometry [] (:geometry-child modifiers)))
;;
;;       (:structure-parent modifiers)
;;       (assoc :structure-parent (reduce copy-structure [] (:structure-parent modifiers)))
;;
;;       (:structure-child modifiers)
;;       (assoc :structure-child (reduce copy-structure [] (:structure-child modifiers))))))
;; =========fin nofun

(defn- process-text-modifiers
  "For texts we only use the displacement because resize
  needs to recalculate the text layout"
  [shape modif-tree]
  modif-tree)
  ;; (cond-> modifiers
  ;;   (= :text (:type shape))
  ;;   (select-keys [:displacement :rotation])))

(defn- add-modifiers
  "Add modifiers to all necessary shapes inside the copies"
  [copies objects modif-tree]
  (letfn [(add-modifiers-shape [modif-tree copy-root copy-shape main-root main-shapes]
            (let [main-shape       (d/seek #(ctk/is-main-of? % copy-shape) main-shapes)
                  modifiers        (sync-shape main-shape copy-shape main-root copy-root modif-tree)
                                   ;; %%(cond-> (sync-shape main-shape-modif copy-shape copy-root main-root)
                                   ;; %% (some? (:rotation (get-in modifiers [(:id main-shape-modif) :modifiers])))
                                   ;; %% (assoc :rotation (:rotation (get-in modifiers [(:id main-shape-modif) :modifiers])))
                                   ;; %% )
                                   ]
              (if (seq modifiers)
                (assoc-in modif-tree [(:id copy-shape) :modifiers] modifiers)
                modif-tree)))

          (add-modifiers-copy [modif-tree copy-root main-root main-shapes]
            ;; For each copy component, get all its shapes and proceed to sync with it
            ;; the changes in the corresponding one in the main component.
            (let [copy-shapes (into [copy-root] (cph/get-children objects (:id copy-root)))]
              (reduce #(add-modifiers-shape %1 copy-root %2 main-root main-shapes)
                      modif-tree
                      copy-shapes)))

          (add-modifiers-component [modif-tree [main-root copy-roots]]
            ;; For each main component, get all its shapes, apply the modifiers to each one and
            ;; then proceed to sync changes with all copies.
            (let [main-shapes (->> (into [main-root] (cph/get-children objects (:id main-root)))
                                   (map (fn [shape]
                                          (let [modifiers (get-in modif-tree [(:id shape) :modifiers])]
                                            (assoc shape
                                                   :modifiers modifiers
                                                   :modif-shape (gsh/transform-shape shape modifiers))))))]
              (reduce #(add-modifiers-copy %1 %2 main-root main-shapes)
                      modif-tree
                      copy-roots)))]

            ;; (let [main-shapes       (into [main-root] (cph/get-children objects (:id main-root)))
            ;;       main-shapes-modif (map (fn [shape]
            ;;                                (let [modifiers (get-in modif-tree [(:id shape) :modifiers])
            ;;                                      points    (:points shape)
            ;;                                      bounds    (gsh/transform-bounds points modifiers)]
            ;;                                  (debug.logjs "..." "")
            ;;                                  (debug.logjs "shape" shape)
            ;;                                  (debug.logjs "modifiers" modifiers)
            ;;                                  (debug.logjs "bounds" bounds)
            ;;                                  (assoc shape :modif-bounds bounds)))
            ;;                                  ;; (->> modifiers
            ;;                                  ;;    (process-text-modifiers shape)
            ;;                                  ;;    (gsh/transform-shape shape))))
            ;;                              main-shapes)]
            ;;   (reduce #(add-modifiers-copy %1 %2 main-root main-shapes-modif)
            ;;           modif-tree
            ;;           copy-roots)))]

    (reduce add-modifiers-component
            modif-tree
            (vals copies))))

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
          (ctn/get-root-shape objects shape)

          :else root)

        transformed-root
        (cond
          (:component-root? transformed-shape)
          transformed-shape

          (nil? transformed-root)
          (as-> (ctn/get-root-shape objects transformed-shape) $
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

(defn update-grow-type
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
          (dissoc :app.main.data.workspace.transforms/current-move-selected)))))

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

        set-parent-ids
        (fn [modif-tree shapes target-frame]
          (reduce
           (fn [modif-tree id]
             (update-in
              modif-tree
              [id :modifiers]
              #(-> %
                   (ctm/change-property :frame-id target-frame)
                   (ctm/change-property :parent-id target-frame))))
           modif-tree
           shapes))

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
                  (update-in [target-frame :modifiers] ctm/add-children shapes drop-index)
                  (set-parent-ids shapes target-frame))

              (and layout? (= original-frame target-frame))
              (update-in [target-frame :modifiers] ctm/add-children shapes drop-index))))]

    (reduce update-frame-modifiers modif-tree origin-frame-ids)))

(defn modif->js
     [modif-tree objects]
     (clj->js (into {}
                    (map (fn [[k v]]
                           [(get-in objects [k :name]) v]))
                    modif-tree)))

(defn apply-text-modifiers
  [objects text-modifiers]
  (letfn [(apply-text-modifier
            [shape {:keys [width height]}]
            (cond-> shape
              (some? width)
              (assoc :width width)

              (some? height)
              (assoc :height height)

              (or (some? width) (some? height))
              (cts/setup-rect-selrect)))]
    (loop [modifiers (seq text-modifiers)
           result objects]
      (if (empty? modifiers)
        result
        (let [[id text-modifier] (first modifiers)]
          (recur (rest modifiers)
                 (update objects id apply-text-modifier text-modifier)))))))

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
   (let [objects
         (wsh/lookup-page-objects state)

         snap-pixel?
         (and (not ignore-snap-pixel) (contains? (:workspace-layout state) :snap-pixel-grid))

         modif-tree
         (as-> objects $
           (apply-text-modifiers $ (get state :workspace-text-modifier))
           ;;(apply-path-modifiers $ (get-in state [:workspace-local :edit-path]))
           (gsh/set-objects-modifiers modif-tree $ ignore-constraints snap-pixel?))

         shapes
         (->> (keys modif-tree)
              (map (d/getf objects)))

         copies
         (get-copies shapes objects modif-tree)
         _ (debug/logjs "copies" copies)

         ;; TODO: mark new modifiers to be ignored in apply-modifiers
         modif-tree (add-modifiers copies objects modif-tree)]

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
       (assoc state :workspace-modifiers (calculate-modifiers state ignore-constraints ignore-snap-pixel modif-tree))))))

;; Rotation use different algorithm to calculate children modifiers (and do not use child constraints).
(defn set-rotation-modifiers
  ([angle shapes]
   (set-rotation-modifiers angle shapes (-> shapes gsh/selection-rect gsh/center-selrect)))

  ([angle shapes center]
   (ptk/reify ::set-rotation-modifiers
     ptk/UpdateEvent
     (update [_ state]
       (let [objects (wsh/lookup-page-objects state)
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
                 (gsh/set-objects-modifiers objects false false))

             shapes
             (->> (keys modif-tree)
                  (map (d/getf objects)))

             copies
             (get-copies shapes objects modif-tree)

             ;; _ (debug.logjs "==================" "")
             ;; _ (debug.logjs "modif-tree" modif-tree)
             ;; _ (debug.logjs "objects" objects)
             ;; _ (debug.logjs "copies" copies)
             modif-tree (add-modifiers copies objects modif-tree)]

         (assoc state :workspace-modifiers modif-tree))))))

(defn apply-modifiers
  ([]
   (apply-modifiers nil))

  ([{:keys [undo-transation? modifiers] :or {undo-transation? true}}]
   (ptk/reify ::apply-modifiers
     ptk/WatchEvent
     (watch [_ state _]
       (let [objects           (wsh/lookup-page-objects state)
             object-modifiers  (if modifiers
                                 (calculate-modifiers state modifiers)
                                 (get state :workspace-modifiers))

             ids (or (keys object-modifiers) [])
             ids-with-children (into (vec ids) (mapcat #(cph/get-children-ids objects %)) ids)

             shapes            (map (d/getf objects) ids)
             ignore-tree       (->> (map #(get-ignore-tree object-modifiers objects %) shapes)
                                    (reduce merge {}))
             undo-id (uuid/next)

             update-fn
             (fn [shape]
               (let [modif (get-in object-modifiers [(:id shape) :modifiers])
                     text-shape? (cph/text-shape? shape)]
                 (-> shape
                     (gsh/transform-shape modif)
                     (cond-> text-shape?
                       (update-grow-type shape)))))

             ignore-touched-fn
             (fn [shape-id]
               ;; When a modifier comes from copying a main component to copies,
               ;; do not set the touched flag, because this change is synced.
               (let [modif (get-in object-modifiers [shape-id :modifiers])]
                 (:copied-modifier? (meta modif))))

             opts {:reg-objects? true
                   :ignore-tree ignore-tree
                   :ignore-touched-fn ignore-touched-fn
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
                           :flip-x
                           :flip-y
                           :grow-type
                           :layout-item-h-sizing
                           :layout-item-v-sizing]}]

         (rx/concat
          (if undo-transation?
            (rx/of (dwu/start-undo-transaction undo-id))
            (rx/empty))
          (rx/of (ptk/event ::dwg/move-frame-guides ids-with-children)
                 (ptk/event ::dwcm/move-frame-comment-threads ids-with-children)
                 (dch/update-shapes ids update-fn opts)
                 (clear-local-transform))
          (if undo-transation?
            (rx/of (dwu/commit-undo-transaction undo-id))
            (rx/empty))))))))

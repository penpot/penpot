;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.dynamic-modifiers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.rect :as grc]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.debug :as dbg]
   [app.util.dom :as dom]
   [app.util.timers :as ts]
   [rumext.v2 :as mf]))

(defn get-shape-node
  ([id]
   (get-shape-node js/document id))

  ([base-node id]
   (if (= (.-id base-node) (dm/str "shape-" id))
     base-node
     (dom/query base-node (dm/str "#shape-" id)))))

(defn get-nodes
  "Retrieve the DOM nodes to apply the matrix transformation"
  [base-node {:keys [id parent-id] :as shape}]
  (when (some? base-node)
    (let [shape-node     (get-shape-node base-node id)
          parent-node    (get-shape-node base-node parent-id)
          frame?         (cfh/frame-shape? shape)
          group?         (cfh/group-shape? shape)
          text?          (cfh/text-shape? shape)
          masking-child? (:masking-child? (meta shape))]
      (cond
        frame?
        [shape-node
         (dom/query shape-node ".frame-children")
         (dom/query (dm/str "#thumbnail-container-" id))
         (dom/query (dm/str "#thumbnail-" id))
         (dom/query (dm/str "#frame-title-" id))]

        ;; For groups we don't want to transform the whole group but only
        ;; its filters/masks
        masking-child?
        [shape-node
         (dom/query parent-node ".mask-clip-path")
         (dom/query parent-node ".mask-shape")
         (when (dbg/enabled? :shape-titles)
           (dom/query (dm/str "#frame-title-" id)))]

        group?
        (let [shape-defs (dom/query shape-node "defs")]
          (d/concat-vec
           [(when (dbg/enabled? :shape-titles)
              (dom/query (dm/str "#frame-title-" id)))]
           (dom/query-all shape-defs ".svg-def")
           (dom/query-all shape-defs ".svg-mask-wrapper")))

        text?
        [shape-node
         (when (dbg/enabled? :shape-titles)
           (dom/query (dm/str "#frame-title-" id)))]

        :else
        [shape-node
         (when (dbg/enabled? :shape-titles)
           (dom/query (dm/str "#frame-title-" id)))]))))

(defn transform-region!
  [node modifiers]

  (let [{:keys [x y width height]}
        (-> (grc/make-rect
             (-> (dom/get-attribute node "data-old-x") d/parse-double)
             (-> (dom/get-attribute node "data-old-y") d/parse-double)
             (-> (dom/get-attribute node "data-old-width") d/parse-double)
             (-> (dom/get-attribute node "data-old-height") d/parse-double))
            (gsh/transform-selrect modifiers))]

    (when (and (some? x) (some? y) (some? width) (some? height))
      (dom/set-attribute! node "x" x)
      (dom/set-attribute! node "y" y)
      (dom/set-attribute! node "width" width)
      (dom/set-attribute! node "height" height))))

(defn start-transform!
  [base-node shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (doseq [node nodes]
        (let [old-transform (dom/get-attribute node "transform")]
          (when (some? old-transform)
            (dom/set-attribute! node "data-old-transform" old-transform))

          (when (or (= (dom/get-tag-name node) "linearGradient")
                    (= (dom/get-tag-name node) "radialGradient"))
            (let [gradient-transform (dom/get-attribute node "gradientTransform")]
              (when (some? gradient-transform)
                (dom/set-attribute! node "data-old-gradientTransform" gradient-transform))))

          (when (= (dom/get-tag-name node) "pattern")
            (let [pattern-transform (dom/get-attribute node "patternTransform")]
              (when (some? pattern-transform)
                (dom/set-attribute! node "data-old-patternTransform" pattern-transform))))

          (when (or (= (dom/get-tag-name node) "mask")
                    (= (dom/get-tag-name node) "filter"))
            (let [old-x (dom/get-attribute node "x")
                  old-y (dom/get-attribute node "y")
                  old-width (dom/get-attribute node "width")
                  old-height (dom/get-attribute node "height")]
              (dom/set-attribute! node "data-old-x" old-x)
              (dom/set-attribute! node "data-old-y" old-y)
              (dom/set-attribute! node "data-old-width" old-width)
              (dom/set-attribute! node "data-old-height" old-height))))))))

(defn set-transform-att!
  [node att value]

  (let [old-att (dom/get-attribute node (dm/str "data-old-" att))
        new-value (if (some? old-att)
                    (dm/str value " " old-att)
                    (str value))]
    (dom/set-attribute! node att (str new-value))))

(defn override-transform-att!
  [node att value]
  (dom/set-attribute! node att (str value)))

(defn update-transform!
  [base-node shapes transforms modifiers]
  (doseq [{:keys [id _type] :as shape} shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])]

        (doseq [node nodes]
          (cond
            (dom/class? node "frame-children")
            (set-transform-att! node "transform" (gmt/inverse transform))

            (dom/class? node "frame-title")
            (let [shape (gsh/transform-shape shape modifiers)
                  zoom  (get-in @st/state [:workspace-local :zoom] 1)
                  edit-grid? (= (dom/get-data node "edit-grid") "true")
                  mtx   (vwu/title-transform shape zoom edit-grid?)]
              (override-transform-att! node "transform" mtx))

            (or (= (dom/get-tag-name node) "mask")
                (= (dom/get-tag-name node) "filter"))
            (transform-region! node modifiers)

            (or (= (dom/get-tag-name node) "linearGradient")
                (= (dom/get-tag-name node) "radialGradient"))
            (set-transform-att! node "gradientTransform" transform)

            (= (dom/get-tag-name node) "pattern")
            (set-transform-att! node "patternTransform" transform)

            (and (some? transform) (some? node))
            (set-transform-att! node "transform" transform)))))))

(defn remove-transform!
  [base-node shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (doseq [node nodes]
        (when (some? node)
          (cond
            (= (dom/get-tag-name node) "foreignObject")
            ;; The shape width/height will be automatically setup when the modifiers are applied
            nil

            (or (= (dom/get-tag-name node) "mask")
                (= (dom/get-tag-name node) "filter"))
            (let [old-x      (dom/get-attribute node "data-old-x")
                  old-y      (dom/get-attribute node "data-old-y")
                  old-width  (dom/get-attribute node "data-old-width")
                  old-height (dom/get-attribute node "data-old-height")]
              (dom/set-attribute! node "x" old-x)
              (dom/set-attribute! node "y" old-y)
              (dom/set-attribute! node "width" old-width)
              (dom/set-attribute! node "height" old-height)

              (dom/remove-attribute! node "data-old-x")
              (dom/remove-attribute! node "data-old-y")
              (dom/remove-attribute! node "data-old-width")
              (dom/remove-attribute! node "data-old-height"))

            (dom/class? node "frame-title")
            (dom/remove-attribute! node "data-old-transform")

            :else
            (let [old-transform (dom/get-attribute node "data-old-transform")]
              (if (some? old-transform)
                (dom/remove-attribute! node "data-old-transform")
                (dom/remove-attribute! node "transform")))))))))

(defn adapt-text-modifiers
  [modifiers shape]
  (let [shape' (gsh/transform-shape shape modifiers)
        scalev
        (gpt/point (/ (:width shape) (:width shape'))
                   (/ (:height shape) (:height shape')))]
    ;; Reverse the change in size so we can recalculate the layout
    (-> modifiers
        (ctm/resize scalev (-> shape' :points first) (:transform shape') (:transform-inverse shape')))))

(defn add-masking-child?
  "Adds to the object the information about if the current shape is a masking child. We use the metadata
  to not adding new parameters to the object."
  [objects]
  (fn [{:keys [id parent-id] :as shape}]
    (let [parent (get objects parent-id)
          masking-child? (and (cfh/mask-shape? parent) (= id (first (:shapes parent))))]

      (cond-> shape
        masking-child?
        (with-meta {:masking-child? true})))))

(defn use-dynamic-modifiers
  [objects node modifiers]
  (let [transforms
        (mf/with-memo [modifiers]
          (when (some? modifiers)
            (d/mapm (fn [id {current-modifiers :modifiers}]
                      (let [shape (get objects id)

                            adapt-text?
                            (and (= :text (:type shape))
                                 (ctm/has-geometry? current-modifiers)
                                 (not (ctm/only-move? current-modifiers)))

                            current-modifiers
                            (cond-> current-modifiers
                              adapt-text?
                              (adapt-text-modifiers shape))]
                        (ctm/modifiers->transform current-modifiers)))
                    modifiers)))

        add-children
        (mf/with-memo [modifiers]
          (ctm/added-children-frames modifiers))

        shapes
        (mf/with-memo [transforms]
          (->> (keys transforms)
               (filter #(some? (get transforms %)))
               (mapv (comp (add-masking-child? objects) (d/getf objects)))))

        add-children      (hooks/use-equal-memo add-children)
        add-children-prev (hooks/use-previous add-children)
        prev-shapes       (mf/use-var nil)
        prev-modifiers    (mf/use-var nil)
        prev-transforms   (mf/use-var nil)]

    (mf/with-effect [add-children]
      (ts/raf
       #(doseq [{:keys [shape]} add-children-prev]
          (let [shape-node  (get-shape-node shape)
                mirror-node (dom/query (dm/fmt ".mirror-shape[href='#shape-%'" shape))]
            (when mirror-node (.remove mirror-node))
            (dom/remove-attribute! (dom/get-parent shape-node) "display"))))

      (ts/raf
       #(doseq [{:keys [frame shape]} add-children]
          (let [frame-node (get-shape-node frame)
                shape-node (get-shape-node shape)

                clip-id
                (-> (dom/query frame-node ":scope > defs > .frame-clip-def")
                    (dom/get-attribute "id"))

                use-node
                (dom/create-element "http://www.w3.org/2000/svg" "use")

                contents-node
                (or (dom/query frame-node ".frame-children") frame-node)]

            (dom/set-attribute! use-node "href" (dm/fmt "#shape-%" shape))
            (dom/set-attribute! use-node "clip-path" (dm/fmt "url(#%)" clip-id))
            (dom/add-class! use-node "mirror-shape")
            (dom/append-child! contents-node use-node)
            (dom/set-attribute! (dom/get-parent shape-node) "display" "none")))))

    (mf/with-effect [transforms]
      (let [curr-shapes-set (into #{} (map :id) shapes)
            prev-shapes-set (into #{} (map :id) @prev-shapes)

            new-shapes      (->> shapes (remove #(contains? prev-shapes-set (:id %))))
            removed-shapes  (->> @prev-shapes (remove #(contains? curr-shapes-set (:id %))))]

        ;; NOTE: we schedule the dom modifications to be executed
        ;; asynchronously for avoid component flickering when react18
        ;; is used.

        (when (d/not-empty? new-shapes)
          (ts/raf #(start-transform! node new-shapes)))

        (when (d/not-empty? shapes)
          (ts/raf #(update-transform! node shapes transforms modifiers)))

        (when (d/not-empty? removed-shapes)
          (ts/raf #(remove-transform! node removed-shapes))))

      (reset! prev-modifiers modifiers)
      (reset! prev-transforms transforms)
      (reset! prev-shapes shapes))))

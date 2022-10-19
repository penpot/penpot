;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.frame.dynamic-modifiers
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.types.modifiers :as ctm]
   [app.main.store :as st]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
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
  [base-node {:keys [id type masked-group?] :as shape}]
  (when (some? base-node)
    (let [shape-node (get-shape-node base-node id)

          frame? (= :frame type)
          group? (= :group type)
          text? (= :text type)
          mask?  (and group? masked-group?)]
      (cond
        frame?
        [shape-node
         (dom/query shape-node ".frame-children")
         (dom/query (dm/str "#thumbnail-container-" id))
         (dom/query (dm/str "#thumbnail-" id))
         (dom/query (dm/str "#frame-title-" id))]

        ;; For groups we don't want to transform the whole group but only
        ;; its filters/masks
        mask?
        [(dom/query shape-node ".mask-clip-path")
         (dom/query shape-node ".mask-shape")]

        group?
        (let [shape-defs (dom/query shape-node "defs")]
          (d/concat-vec
           (dom/query-all shape-defs ".svg-def")
           (dom/query-all shape-defs ".svg-mask-wrapper")))

        text?
        [shape-node]

        :else
        [shape-node]))))

(defn transform-region!
  [node modifiers]

  (let [{:keys [x y width height]}
        (-> (gsh/make-selrect
             (-> (dom/get-attribute node "data-old-x") d/parse-double)
             (-> (dom/get-attribute node "data-old-y") d/parse-double)
             (-> (dom/get-attribute node "data-old-width") d/parse-double)
             (-> (dom/get-attribute node "data-old-height") d/parse-double))
            (gsh/transform-selrect modifiers))]
    (dom/set-attribute! node "x" x)
    (dom/set-attribute! node "y" y)
    (dom/set-attribute! node "width" width)
    (dom/set-attribute! node "height" height)))

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
                  mtx   (vwu/title-transform shape zoom)]
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
            (do
              (dom/remove-attribute! node "data-old-x")
              (dom/remove-attribute! node "data-old-y")
              (dom/remove-attribute! node "data-old-width")
              (dom/remove-attribute! node "data-old-height"))

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
        (ctm/set-resize scalev (-> shape' :points first) (:transform shape') (:transform-inverse shape')))))

(defn use-dynamic-modifiers
  [objects node modifiers]

  (let [transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           (when (some? modifiers)
             (d/mapm (fn [id {modifiers :modifiers}]
                       (let [shape (get objects id)
                             text? (= :text (:type shape))
                             modifiers (cond-> modifiers text? (adapt-text-modifiers shape))]
                         (ctm/modifiers->transform modifiers)))
                     modifiers))))

        add-children (mf/use-memo (mf/deps modifiers) #(ctm/get-frame-add-children modifiers))
        add-children (hooks/use-equal-memo add-children)
        add-children-prev (hooks/use-previous add-children)

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           (->> (keys transforms)
                (mapv (d/getf objects)))))

        prev-shapes (mf/use-var nil)
        prev-modifiers (mf/use-var nil)
        prev-transforms (mf/use-var nil)]

    (mf/use-effect
     (mf/deps add-children)
     (fn []
       (doseq [{:keys [frame shape]} add-children-prev]
         (let [frame-node (get-shape-node node frame)
               shape-node (get-shape-node shape)
               mirror-node (dom/query frame-node (dm/fmt ".mirror-shape[href='#shape-%'" shape))]
           (when mirror-node (.remove mirror-node))
           (dom/remove-attribute! (dom/get-parent shape-node) "display")))

       (doseq [{:keys [frame shape]} add-children]
         (let [frame-node (get-shape-node node frame)
               shape-node (get-shape-node shape)

               use-node
               (.createElementNS globals/document "http://www.w3.org/2000/svg" "use")

               contents-node
               (or (dom/query frame-node ".frame-children") frame-node)]

           (dom/set-attribute! use-node "href" (dm/fmt "#shape-%" shape))
           (dom/add-class! use-node "mirror-shape")
           (dom/append-child! contents-node use-node)
           (dom/set-attribute! (dom/get-parent shape-node) "display" "none")))))

    (mf/use-layout-effect
     (mf/deps transforms)
     (fn []
       (let [is-prev-val? (d/not-empty? @prev-modifiers)
             is-cur-val? (d/not-empty? modifiers)]

         (when (and (not is-prev-val?) is-cur-val?)
           (start-transform! node shapes))

         (when is-cur-val?
           (update-transform! node shapes transforms modifiers))

         (when (and is-prev-val? (not is-cur-val?))
           (remove-transform! node @prev-shapes))

         (reset! prev-modifiers modifiers)
         (reset! prev-transforms transforms)
         (reset! prev-shapes shapes))))))

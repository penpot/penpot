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
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.utils :as vwu]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(defn- transform-no-resize
  "If we apply a scale directly to the texts it will show deformed so we need to create this
  correction matrix to \"undo\" the resize but keep the other transformations."
  [{:keys [x y width height points transform transform-inverse] :as shape} current-transform modifiers]

  (let [corner-pt (first points)
        corner-pt (cond-> corner-pt (some? transform-inverse) (gpt/transform transform-inverse))

        resize-x? (some? (:resize-vector modifiers))
        resize-y? (some? (:resize-vector-2 modifiers))

        flip-x? (neg? (get-in modifiers [:resize-vector :x]))
        flip-y? (or (neg? (get-in modifiers [:resize-vector :y]))
                    (neg? (get-in modifiers [:resize-vector-2 :y])))

        result (cond-> (gmt/matrix)
                 (and (some? transform) (or resize-x? resize-y?))
                 (gmt/multiply transform)

                 resize-x?
                 (gmt/scale (gpt/inverse (:resize-vector modifiers)) corner-pt)

                 resize-y?
                 (gmt/scale (gpt/inverse (:resize-vector-2 modifiers)) corner-pt)

                 flip-x?
                 (gmt/scale (gpt/point -1 1) corner-pt)

                 flip-y?
                 (gmt/scale (gpt/point 1 -1) corner-pt)

                 (and (some? transform) (or resize-x? resize-y?))
                 (gmt/multiply transform-inverse))

        [width height]
        (if (or resize-x? resize-y?)
          (let [pc (cond-> (gpt/point x y)
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))

                pw (cond-> (gpt/point (+ x width) y)
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))

                ph (cond-> (gpt/point x (+ y height))
                     (some? transform)
                     (gpt/transform transform)

                     (some? current-transform)
                     (gpt/transform current-transform))]
            [(gpt/distance pc pw) (gpt/distance pc ph)])
          [width height])]

    [result width height]))

(defn get-nodes
  "Retrieve the DOM nodes to apply the matrix transformation"
  [base-node {:keys [id type masked-group?] :as shape}]
  (when (some? base-node)
    (let [shape-node (if (= (.-id base-node) (dm/str "shape-" id))
                       base-node
                       (dom/query base-node (dm/str "#shape-" id)))

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
        [shape-node
         (dom/query shape-node ".text-container")]

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
  (doseq [{:keys [id type] :as shape} shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])
            text? (= type :text)
            transform-text? (and text? (and (nil? (:resize-vector modifiers)) (nil? (:resize-vector-2 modifiers))))]

        (doseq [node nodes]
          (cond
            ;; Text shapes need special treatment because their resize only change
            ;; the text area, not the change size/position
            (dom/class? node "frame-thumbnail")
            (let [[transform] (transform-no-resize shape transform modifiers)]
              (set-transform-att! node "transform" transform))

            (dom/class? node "frame-children")
            (set-transform-att! node "transform" (gmt/inverse transform))

            (dom/class? node "text-container")
            (let [modifiers (dissoc modifiers :displacement :rotation)]
              (when (not (gsh/empty-modifiers? modifiers))
                (let [mtx (-> shape
                              (assoc :modifiers modifiers)
                              (gsh/transform-shape)
                              (gsh/transform-matrix {:no-flip true}))]
                  (override-transform-att! node "transform" mtx))))

            (dom/class? node "frame-title")
            (let [shape (-> shape (assoc :modifiers modifiers) gsh/transform-shape)
                  zoom (get-in @st/state [:workspace-local :zoom] 1)
                  mtx  (vwu/title-transform shape zoom)]
              (override-transform-att! node "transform" mtx))

            (or (= (dom/get-tag-name node) "mask")
                (= (dom/get-tag-name node) "filter"))
            (transform-region! node modifiers)

            (or (= (dom/get-tag-name node) "linearGradient")
                (= (dom/get-tag-name node) "radialGradient"))
            (set-transform-att! node "gradientTransform" transform)

            (= (dom/get-tag-name node) "pattern")
            (set-transform-att! node "patternTransform" transform)

            (and (some? transform) (some? node) (or (not text?) transform-text?))
            (set-transform-att! node "transform" transform)))))))

(defn remove-transform!
  [base-node shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (doseq [node nodes]
        (when (some? node)
          (cond
            (= (dom/get-tag-name node) "foreignObject")
            ;; The shape width/height will be automaticaly setup when the modifiers are applied
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

(defn use-dynamic-modifiers
  [objects node modifiers]

  (let [transforms
        (mf/use-memo
         (mf/deps modifiers)
         (fn []
           (when (some? modifiers)
             (d/mapm (fn [id {modifiers :modifiers}]
                       (let [shape (get objects id)
                             center (gsh/center-shape shape)
                             modifiers (cond-> modifiers
                                         ;; For texts we only use the displacement because
                                         ;; resize needs to recalculate the text layout
                                         (= :text (:type shape))
                                         (select-keys [:displacement :rotation]))]
                         (gsh/modifiers->transform center modifiers)))
                     modifiers))))

        shapes
        (mf/use-memo
         (mf/deps transforms)
         (fn []
           (->> (keys transforms)
                (mapv (d/getf objects)))))

        prev-shapes (mf/use-var nil)
        prev-modifiers (mf/use-var nil)
        prev-transforms (mf/use-var nil)]

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

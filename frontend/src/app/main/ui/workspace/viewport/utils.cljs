; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.cursors :as cur]
   [app.util.dom :as dom]))

(defn- text-corrected-transform
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
  [base-node {:keys [id type masked-group?]}]
  (let [shape-node (dom/query base-node (str "#shape-" id))

        frame? (= :frame type)
        group? (= :group type)
        text?  (= :text type)
        mask?  (and group? masked-group?)

        ;; When the shape is a frame we maybe need to move its thumbnail
        thumb-node (when frame? (dom/query (str "#thumbnail-" id)))]

    (cond
      frame?
      [thumb-node
       (dom/get-parent (dom/query shape-node ".frame-background"))
       (dom/query shape-node ".frame-clip")]

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
       (dom/query shape-node ".text-shape")]

      :else
      [shape-node])))

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

(defn update-transform!
  [base-node shapes transforms modifiers]
  (doseq [{:keys [id type] :as shape} shapes]
    (when-let [nodes (get-nodes base-node shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])

            [text-transform _text-width _text-height]
            (when (= :text type)
              (text-corrected-transform shape transform modifiers))]

        (doseq [node nodes]
          (cond
            ;; Text shapes need special treatment because their resize only change
            ;; the text area, not the change size/position
            (dom/class? node "text-shape")
            (when (some? text-transform)
              (set-transform-att! node "transform" text-transform))

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

(defn format-viewbox [vbox]
  (dm/str (:x vbox 0) " "
          (:y vbox 0) " "
          (:width vbox 0) " "
          (:height vbox 0)))

(defn translate-point-to-viewport [viewport zoom pt]
  (let [vbox     (.. ^js viewport -viewBox -baseVal)
        brect    (dom/get-bounding-rect viewport)
        brect    (gpt/point (d/parse-integer (:left brect))
                            (d/parse-integer (:top brect)))
        box      (gpt/point (.-x vbox) (.-y vbox))
        zoom     (gpt/point zoom)]
    (-> (gpt/subtract pt brect)
        (gpt/divide zoom)
        (gpt/add box))))

(defn get-cursor [cursor]
  (case cursor
    :hand cur/hand
    :comments cur/comments
    :create-artboard cur/create-artboard
    :create-rectangle cur/create-rectangle
    :create-ellipse cur/create-ellipse
    :pen cur/pen
    :pencil cur/pencil
    :create-shape cur/create-shape
    :duplicate cur/duplicate
    :zoom cur/zoom
    :zoom-in cur/zoom-in
    :zooom-out cur/zoom-out
    cur/pointer-inner))

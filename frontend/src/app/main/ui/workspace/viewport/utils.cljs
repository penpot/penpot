; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.main.ui.cursors :as cur]
   [app.util.dom :as dom]
   [cuerdas.core :as str]))

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
  [{:keys [id type masked-group?]}]
  (let [shape-node (dom/get-element (str "shape-" id))

        frame? (= :frame type)
        group? (= :group type)
        text?  (= :text type)
        mask?  (and group? masked-group?)

        ;; When the shape is a frame we maybe need to move its thumbnail
        thumb-node (when frame? (dom/get-element (str "thumbnail-" id)))]

    (cond
      frame?
      [thumb-node
       (dom/query shape-node ".frame-background")
       (dom/query shape-node ".frame-clip")]

      ;; For groups we don't want to transform the whole group but only
      ;; its filters/masks
      mask?
      [(dom/query shape-node ".mask-clip-path")
       (dom/query shape-node ".mask-shape")]

      group?
      []

      text?
      [shape-node
       (dom/query shape-node "foreignObject")
       (dom/query shape-node ".text-shape")]

      :else
      [shape-node])))

(defn update-transform [shapes transforms modifiers]
  (doseq [{:keys [id type] :as shape} shapes]
    (when-let [nodes (get-nodes shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])

            [text-transform text-width text-height]
            (when (= :text type)
              (text-corrected-transform shape transform modifiers))]

        (doseq [node nodes]
          (cond
            (dom/class? node "text-shape")
            (when (some? text-transform)
              (dom/set-attribute node "transform" (str text-transform)))

            (= (dom/get-tag-name node) "foreignObject")
            (when (and (some? text-width) (some? text-height))
              (dom/set-attribute node "width" text-width)
              (dom/set-attribute node "height" text-height))

            (and (some? transform) (some? node))
            (dom/set-attribute node "transform" (str transform))))))))

(defn remove-transform [shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes shape)]
      (doseq [node nodes]
        (when (some? node)
          (cond
            (= (dom/get-tag-name node) "foreignObject")
            ;; The shape width/height will be automaticaly setup when the modifiers are applied
            nil

            :else
            (dom/remove-attribute node "transform")))))))

(defn format-viewbox [vbox]
  (str/join " " [(:x vbox 0)
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(defn translate-point-to-viewport-raw [viewport zoom pt]
  (let [vbox     (.. ^js viewport -viewBox -baseVal)
        brect    (dom/get-bounding-rect viewport)
        brect    (gpt/point (d/parse-integer (:left brect))
                            (d/parse-integer (:top brect)))
        box      (gpt/point (.-x vbox) (.-y vbox))
        zoom     (gpt/point zoom)]
    (-> (gpt/subtract pt brect)
        (gpt/divide zoom)
        (gpt/add box))))

(defn translate-point-to-viewport [viewport zoom pt]
  (-> (translate-point-to-viewport-raw viewport zoom pt)
      (gpt/round 0)))

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

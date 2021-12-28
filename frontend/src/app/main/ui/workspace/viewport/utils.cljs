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
  [{:keys [points transform transform-inverse]} current-transform modifiers]

  (let [corner-pt (first points)
        transform (or transform (gmt/matrix))
        transform-inverse (or transform-inverse (gmt/matrix))

        current-transform
        (if (some? (:resize-vector modifiers))
          (gmt/multiply
           current-transform
           transform
           (gmt/scale-matrix (gpt/inverse (:resize-vector modifiers)) (gpt/transform corner-pt transform-inverse))
           transform-inverse)
          current-transform)

        current-transform
        (if (some? (:resize-vector-2 modifiers))
          (gmt/multiply
           current-transform
           transform
           (gmt/scale-matrix (gpt/inverse (:resize-vector-2 modifiers)) (gpt/transform corner-pt transform-inverse))
           transform-inverse)
          current-transform)]
    current-transform))

(defn get-nodes
  "Retrieve the DOM nodes to apply the matrix transformation"
  [{:keys [id type masked-group?]}]
  (let [shape-node (dom/get-element (str "shape-" id))

        frame? (= :frame type)
        group? (= :group type)
        mask?  (and group? masked-group?)

        ;; When the shape is a frame we maybe need to move its thumbnail
        thumb-node (when frame? (dom/get-element (str "thumbnail-" id)))]
    (cond
      (some? thumb-node)
      [(.-parentNode thumb-node)]

      (and (some? shape-node) frame?)
      [(dom/query shape-node ".frame-background")
       (dom/query shape-node ".frame-clip")]

      ;; For groups we don't want to transform the whole group but only
      ;; its filters/masks
      (and (some? shape-node) mask?)
      [(dom/query shape-node ".mask-clip-path")
       (dom/query shape-node ".mask-shape")]

      group?
      []

      :else
      [shape-node])))

(defn update-transform [shapes transforms modifiers]
  (doseq [{id :id :as shape} shapes]
    (when-let [nodes (get-nodes shape)]
      (let [transform (get transforms id)
            modifiers (get-in modifiers [id :modifiers])
            transform (case type
                        :text (text-corrected-transform shape transform modifiers)
                        transform)]
        (doseq [node nodes]
          (when (and (some? transform) (some? node))
            (dom/set-attribute node "transform" (str transform))))))))

(defn remove-transform [shapes]
  (doseq [shape shapes]
    (when-let [nodes (get-nodes shape)]
      (doseq [node nodes]
        (when (some? node)
          (dom/remove-attribute node "transform"))))))

(defn format-viewbox [vbox]
  (str/join " " [(+ (:x vbox 0) (:left-offset vbox 0))
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(defn translate-point-to-viewport [viewport zoom pt]
  (let [vbox     (.. ^js viewport -viewBox -baseVal)
        brect    (dom/get-bounding-rect viewport)
        brect    (gpt/point (d/parse-integer (:left brect))
                            (d/parse-integer (:top brect)))
        box      (gpt/point (.-x vbox) (.-y vbox))
        zoom     (gpt/point zoom)]
    (-> (gpt/subtract pt brect)
        (gpt/divide zoom)
        (gpt/add box)
        (gpt/round 0))))

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
    cur/pointer-inner))

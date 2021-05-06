; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.util.dom :as dom]
   [app.common.geom.point :as gpt]
   [cuerdas.core :as str]
   [app.common.data :as d]
   [app.main.ui.cursors :as cur]
   ))

(defn update-transform [node shapes modifiers]
  (doseq [{:keys [id type]} shapes]
    (when-let [node (dom/get-element (str "shape-" id))]
      (let [node (if (= :frame type) (.-parentNode node) node)]
        (dom/set-attribute node "transform" (str (:displacement modifiers)))))))

(defn remove-transform [node shapes]
  (doseq [{:keys [id type]} shapes]
    (when-let [node (dom/get-element (str "shape-" id))]
      (let [node (if (= :frame type) (.-parentNode node) node)]
        (dom/remove-attribute node "transform")))))

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

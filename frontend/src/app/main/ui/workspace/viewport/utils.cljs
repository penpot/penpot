; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.utils
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.ui.cursors :as cur]
   [app.main.ui.formats :refer [format-number]]
   [app.util.dom :as dom]))

(defn format-viewbox [vbox]
  (dm/str (format-number(:x vbox 0)) " "
          (format-number (:y vbox 0)) " "
          (format-number (:width vbox 0)) " "
          (format-number (:height vbox 0))))

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

;; Ensure that the label has always the same font
;; size, regardless of zoom
;; https://css-tricks.com/transforms-on-svg-elements/
(defn text-transform
  [{:keys [x y]} zoom]
  (let [inv-zoom (/ 1 zoom)]
    (dm/fmt "scale(%, %) translate(%, %)" inv-zoom inv-zoom (* zoom x) (* zoom y))))

(defn title-transform [frame zoom]
  (let [frame-transform (gsh/transform-str frame {:no-flip true})
        label-pos (gpt/point (:x frame) (- (:y frame) (/ 10 zoom)))]
    (dm/str frame-transform " " (text-transform label-pos zoom))))

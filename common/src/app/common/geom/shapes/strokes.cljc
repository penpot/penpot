;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.strokes)

(defn update-stroke-width
  [stroke scale]
  (update stroke :stroke-width * scale))

(defn update-strokes-width
  [shape scale]
  (update shape :strokes
          (fn [strokes]
            (mapv #(update-stroke-width % scale) strokes))))

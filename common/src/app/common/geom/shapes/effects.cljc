;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.effects)

(defn update-shadow-scale
  [shadow scale]
  (-> shadow
      (update :offset-x * scale)
      (update :offset-y * scale)
      (update :spread * scale)
      (update :blur * scale)))

(defn update-shadows-scale
  [shape scale]
  (update shape :shadow
          (fn [shadow]
            (mapv #(update-shadow-scale % scale) shadow))))

(defn update-blur-scale
  [shape scale]
  (update-in shape [:blur :value] * scale))


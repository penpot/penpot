;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.renderer.svg-gradient)

(defn- get-stops
  [data]
  (->> (get-in data ["gradient" "stops"])
       (mapv (fn [stop-data]
               {"type" "element"
                "name" "stop"
                "attributes" {"offset" (get stop-data "offset")
                              "stop-color" (get stop-data "color")
                              "stop-opacity" (get stop-data "opacity")}}))))

(defn data->gradient-def
  [id [color data]]
  (let [id            (str "gradient-" id "-" (subs color 1))
        gradient-type (get-in data ["gradient" "type"])]
    (if (= gradient-type "linear")
      {"type" "element"
       "name" "linearGradient"
       "attributes" {"id" id "x1" "0.5" "y1" "1" "x2" "0.5" "y2" "0"}
       "elements" (get-stops data)}

      {"type" "element"
       "name" "radialGradient"
       "attributes" {"id" id "cx" "0.5" "cy" "0.5" "r" "0.5"}
       "elements" (get-stops data)})))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns exporter-tests.renderer-svg-test
  (:require
   [app.renderer.svg-gradient :as svg-gradient]
   [cljs.test :refer [deftest is testing]]))

(def gradient-stops
  [{"color" "#000000" "offset" 0 "opacity" 1}
   {"color" "#ffffff" "offset" 1 "opacity" 1}])

(deftest creates-the-correct-gradient-element
  (doseq [[gradient-type element-name]
          [["linear" "linearGradient"]
           ["radial" "radialGradient"]]]
    (testing gradient-type
      (let [gradient-data {"type" "gradient"
                           "gradient" {"type" gradient-type
                                       "stops" gradient-stops}}
            result         (svg-gradient/data->gradient-def "text-id" ["#000001" gradient-data])]
        (is (= element-name (get result "name")))))))

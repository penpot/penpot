;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.svgo
  (:require
   ["./impl_svgo" :as svgo]))

(def default-config
  #js {:multipass false
       :plugins
       #js [#js {:name "safePreset"
                 :params #js {:overrides
                              #js {:convertColors
                                   #js {:names2hex true
                                        :shorthex false
                                        :shortname false}
                                   :convertTransform
                                   #js {:matrixToTransform false
                                        :convertToShorts false
                                        :transformPrecision 4
                                        :leadingZero false}}}}]})

(defn optimize
  [input]
  (let [result (svgo/optimize input default-config)]
    ;; (js/console.log "optimize" result)
    result))

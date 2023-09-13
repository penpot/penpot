;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.svgo
  (:require
   ["./impl_svgo" :as svgo]))

(js/console.log svgo)


(defn optimize
  [input]
  (let [result (svgo/optimize input)]
    (js/console.log "optimize" result)

    (unchecked-get result "data")))

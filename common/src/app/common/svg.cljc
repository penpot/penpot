;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.svg
  #?(:cljs
     (:require
      ["./svg_optimizer.js" :as svgo])))

#?(:cljs
   (defn optimize
     ([input] (optimize input nil))
     ([input options]
      (svgo/optimize input (clj->js options)))))

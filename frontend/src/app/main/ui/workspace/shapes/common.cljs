;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.common
  (:require
   [app.main.ui.shapes.shape :refer [shape-container]]
   [rumext.alpha :as mf]))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
     ::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")]
      [:> shape-container {:shape shape}
       [:& component {:shape shape}]])))

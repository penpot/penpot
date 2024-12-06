;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.common
  (:require
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [rumext.v2 :as mf]))

(defn check-shape-props
  [np op]
  (= (unchecked-get np "shape")
     (unchecked-get op "shape")))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap [#(mf/memo' % check-shape-props)]
     ::mf/props :obj}
    [props]
    (let [shape (unchecked-get props "shape")]
      [:> shape-container {:shape shape}
       [:& component {:shape shape}]
       (when *assert*
         [:& wsd/shape-debug {:shape shape}])])))

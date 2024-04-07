;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.common
  (:require
   [app.common.record :as cr]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [rumext.v2 :as mf]))

(def ^:private excluded-attrs
  #{:blocked
    :hide-fill-on-export
    :collapsed
    :remote-synced
    :exports})

(defn check-shape
  [new-shape old-shape]
  (cr/-equiv-with-exceptions old-shape new-shape excluded-attrs))

(defn check-shape-props
  [np op]
  (check-shape (unchecked-get np "shape")
               (unchecked-get op "shape")))

(defn generic-wrapper-factory
  [component]
  (mf/fnc generic-wrapper
    {::mf/wrap [#(mf/memo' % check-shape-props)]
     ::mf/wrap-props false}
    [props]
    (let [shape (unchecked-get props "shape")]
      [:> shape-container {:shape shape}
       [:& component {:shape shape}]
       (when *assert*
         [:& wsd/shape-debug {:shape shape}])])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.svg-raw
  (:require
   [app.main.refs :as refs]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [rumext.alpha :as mf]))

(defn svg-raw-wrapper-factory
  [shape-wrapper]
  (let [svg-raw-shape (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            childs-ref (mf/use-memo (mf/deps shape) #(refs/objects-by-id (:shapes shape)))
            childs     (mf/deref childs-ref)]


        (if (or (= (get-in shape [:content :tag]) :svg)
                (and (contains? shape :svg-attrs) (map? (:content shape))))
          [:> shape-container {:shape shape}
           [:& svg-raw-shape {:shape shape
                              :childs childs}]]

          [:& svg-raw-shape {:shape shape
                             :childs childs}])))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.svg-raw
  (:require
   [app.main.refs :as refs]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.svg-raw :as svg-raw]
   [app.util.svg :as usvg]
   [rumext.v2 :as mf]))

(defn svg-raw-wrapper-factory
  [shape-wrapper]
  (let [svg-raw-shape (svg-raw/svg-raw-shape shape-wrapper)]
    (mf/fnc svg-raw-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            childs-ref (mf/use-memo (mf/deps (:id shape)) #(refs/children-objects (:id shape)))
            childs     (mf/deref childs-ref)
            svg-tag    (get-in shape [:content :tag])]
        (if (contains? usvg/svg-group-safe-tags svg-tag)
          [:> shape-container {:shape shape}
           [:& svg-raw-shape {:shape shape
                              :childs childs}]]

          [:& svg-raw-shape {:shape shape
                             :childs childs}])))))


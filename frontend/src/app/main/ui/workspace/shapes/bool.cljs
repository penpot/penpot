;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.bool
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.shapes.bool :as bool]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :refer [check-shape-props]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [rumext.v2 :as mf]))

(defn bool-wrapper-factory
  [shape-wrapper]
  (let [bool-shape (bool/bool-shape shape-wrapper)]
    (mf/fnc bool-wrapper
      {::mf/wrap [#(mf/memo' % check-shape-props)]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            shape-id   (dm/get-prop shape :id)

            child-sel* (mf/with-memo [shape-id]
                         (refs/is-child-selected? shape-id))

            childs*    (mf/with-memo [shape-id]
                         (refs/select-bool-children shape-id))

            child-sel? (mf/deref child-sel*)
            childs     (mf/deref childs*)

            shape      (cond-> shape
                         ^boolean child-sel?
                         (dissoc :content))]

        [:> shape-container {:shape shape}
         [:& bool-shape {:shape shape
                         :childs childs}]
         (when *assert*
           [:& wsd/shape-debug {:shape shape}])]))))


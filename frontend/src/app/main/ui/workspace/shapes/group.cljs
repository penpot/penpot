;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.group
  (:require
   [app.common.data.macros :as dm]
   [app.main.refs :as refs]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.common :refer [check-shape-props]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [rumext.v2 :as mf]))

(defn group-wrapper-factory
  [shape-wrapper]
  (let [group-shape (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      {::mf/wrap [#(mf/memo' % check-shape-props)]
       ::mf/wrap-props false}
      [props]
      (let [shape    (unchecked-get props "shape")
            shape-id (dm/get-prop shape :id)

            childs*  (mf/with-memo [shape-id]
                       (refs/children-objects shape-id))
            childs   (mf/deref childs*)]

        [:> shape-container {:shape shape}
         [:& group-shape
          {:shape shape
           :childs childs}]
         (when *assert*
           [:& wsd/shape-debug {:shape shape}])]))))


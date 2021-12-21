;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.group
  (:require
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.util.dom :as dom]
   [rumext.alpha :as mf]))

(defn use-double-click [{:keys [id]}]
  (mf/use-callback
   (mf/deps id)
   (fn [event]
     (dom/stop-propagation event)
     (dom/prevent-default event)
     (st/emit! (dw/select-inside-group id @ms/mouse-position)))))

(defn group-wrapper-factory
  [shape-wrapper]
  (let [group-shape (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape      (unchecked-get props "shape")
            childs-ref (mf/use-memo (mf/deps shape) #(refs/objects-by-id (:shapes shape)))
            childs     (mf/deref childs-ref)]

        [:> shape-container {:shape shape}
         [:& group-shape
          {:shape shape
           :childs childs}]]))))


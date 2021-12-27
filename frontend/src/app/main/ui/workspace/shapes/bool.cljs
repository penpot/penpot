;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.bool
  (:require
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.shapes.bool :as bool]
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

(defn bool-wrapper-factory
  [shape-wrapper]
  (let [shape-component (bool/bool-shape shape-wrapper)]
    (mf/fnc bool-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape"]))]
       ::mf/wrap-props false}
      [props]
      (let [shape         (unchecked-get props "shape")
            child-sel-ref (mf/use-memo
                           (mf/deps (:id shape))
                           #(refs/is-child-selected? (:id shape)))

            childs-ref    (mf/use-memo
                           (mf/deps (:id shape))
                           #(refs/select-bool-children (:id shape)))

            child-sel?    (mf/deref child-sel-ref)
            childs        (mf/deref childs-ref)

            shape         (cond-> shape
                            child-sel?
                            (dissoc :bool-content))]

        [:> shape-container {:shape shape}
         [:& shape-component {:shape shape
                              :childs childs}]]))))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.shapes.group
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.shapes.common :as common]
   [uxbox.main.ui.shapes.group :as group]
   [uxbox.util.dom :as dom]
   [uxbox.main.streams :as ms]
   [uxbox.util.timers :as ts]))

(defn- group-wrapper-factory-equals?
  [np op]
  (let [n-shape (unchecked-get np "shape")
        o-shape (unchecked-get op "shape")
        n-frame (unchecked-get np "frame")
        o-frame (unchecked-get op "frame")]
    (and (= n-frame o-frame)
         (= n-shape o-shape))))

(defn group-wrapper-factory
  [shape-wrapper]
  (let [group-shape (group/group-shape shape-wrapper)]
    (mf/fnc group-wrapper
      {::mf/wrap [#(mf/memo' % group-wrapper-factory-equals?)]
       ::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")
            on-mouse-down   (mf/use-callback (mf/deps shape)
                                             #(common/on-mouse-down % shape))
            on-context-menu (mf/use-callback (mf/deps shape)
                                             #(common/on-context-menu % shape))

            children-ref   (mf/use-memo (mf/deps shape)
                                        #(refs/objects-by-id (:shapes shape)))
            children       (mf/deref children-ref)


            is-child-selected-ref (mf/use-memo (mf/deps (:id shape))
                                               #(refs/is-child-selected? (:id shape)))
            is-child-selected?    (mf/deref is-child-selected-ref)

            on-double-click
            (mf/use-callback
             (mf/deps (:id shape))
             (fn [event]
               (dom/stop-propagation event)
               (dom/prevent-default event)
               (st/emit! (dw/select-inside-group (:id shape) @ms/mouse-position))))]

        [:g.shape
         {:on-mouse-down on-mouse-down
          :on-context-menu on-context-menu
          :on-double-click on-double-click}

         [:& group-shape
          {:frame frame
           :shape shape
           :children children
           :is-child-selected? is-child-selected?}]]))))


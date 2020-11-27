;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.group
  (:require
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.shapes.group :as group]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.effects :as we]
   [app.util.dom :as dom]
   [rumext.alpha :as mf]))

(defn- group-wrapper-factory-equals?
  [np op]
  (let [n-shape (unchecked-get np "shape")
        o-shape (unchecked-get op "shape")
        n-frame (unchecked-get np "frame")
        o-frame (unchecked-get op "frame")]
    (and (= n-frame o-frame)
         (= n-shape o-shape))))

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
      {::mf/wrap [#(mf/memo' % group-wrapper-factory-equals?)]
       ::mf/wrap-props false}
      [props]
      (let [shape (unchecked-get props "shape")
            frame (unchecked-get props "frame")

            childs-ref (mf/use-memo (mf/deps shape) #(refs/objects-by-id (:shapes shape)))
            childs     (mf/deref childs-ref)

            is-child-selected-ref
            (mf/use-memo (mf/deps (:id shape)) #(refs/is-child-selected? (:id shape)))

            is-child-selected?
            (mf/deref is-child-selected-ref)

            mask-id (when (:masked-group? shape) (first (:shapes shape)))

            is-mask-selected-ref
            (mf/use-memo (mf/deps mask-id) #(refs/make-selected-ref mask-id))

            is-mask-selected?
            (mf/deref is-mask-selected-ref)

            handle-mouse-down (we/use-mouse-down shape)
            handle-context-menu (we/use-context-menu shape)
            handle-pointer-enter (we/use-pointer-enter shape)
            handle-pointer-leave (we/use-pointer-leave shape)
            handle-double-click (use-double-click shape)]

        [:> shape-container {:shape shape
                             :on-mouse-down handle-mouse-down
                             :on-context-menu handle-context-menu
                             :on-pointer-enter handle-pointer-enter
                             :on-pointer-leave handle-pointer-leave
                             :on-double-click handle-double-click}
         [:& group-shape
          {:frame frame
           :shape shape
           :childs childs
           :is-child-selected? is-child-selected?
           :expand-mask is-mask-selected?}]]))))


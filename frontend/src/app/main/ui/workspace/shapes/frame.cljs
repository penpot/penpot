;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.shapes.frame
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as muc]
   [app.main.ui.shapes.frame :as frame]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.timers :as ts]
   [beicon.core :as rx]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(defn make-is-moving-ref
  [id]
  (let [check-moving (fn [local]
                       (and (= :move (:transform local))
                            (contains? (:selected local) id)))]
    (l/derived check-moving refs/workspace-local)))

;; This custom deffered don't deffer rendering when ghost rendering is
;; used.
(defn custom-deferred
  [component]
  (mf/fnc deferred
    {::mf/wrap-props false}
    [props]
    (let [tmp (mf/useState false)
          ^boolean render? (aget tmp 0)
          ^js set-render (aget tmp 1)]
      (mf/use-layout-effect
       (fn []
         (let [sem (ts/schedule-on-idle #(set-render true))]
           #(rx/dispose! sem))))
      (when render? (mf/create-element component props)))))

(defn frame-wrapper-factory
  [shape-wrapper]
  (let [frame-shape (frame/frame-shape shape-wrapper)]
    (mf/fnc frame-wrapper
      {::mf/wrap [#(mf/memo' % (mf/check-props ["shape" "objects"])) custom-deferred]
       ::mf/wrap-props false}
      [props]
      (let [shape       (unchecked-get props "shape")
            objects     (unchecked-get props "objects")
            edition     (mf/deref refs/selected-edition)

            shape       (gsh/transform-shape shape)
            children    (mapv #(get objects %) (:shapes shape))
            ds-modifier (get-in shape [:modifiers :displacement])]

        (when (and shape (not (:hidden shape)))
          [:g.frame-wrapper {:display (when (:hidden shape) "none")}
           [:> shape-container {:shape shape}
            [:& frame-shape
             {:shape shape
              :childs children}]]])))))


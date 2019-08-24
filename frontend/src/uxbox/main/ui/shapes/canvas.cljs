;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.shapes.canvas
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.main.constants :as c]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.geom :as geom]
   [uxbox.main.refs :as refs]
   [uxbox.main.ui.shapes.common :as common]
   [uxbox.main.store :as st]
   [uxbox.main.ui.shapes.rect :refer [rect-shape]]
   ;; [uxbox.main.ui.workspace.streams :as uws]
   [uxbox.util.data :refer [parse-int]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]))

(def canvas-default-props
  {:fill-color "#ffffff"})

(mf/defc canvas-component
  [{:keys [shape] :as props}]
  (let [selected (mf/deref refs/selected-shapes)
        selected? (contains? selected (:id shape))
        on-mouse-down #(common/on-mouse-down % shape selected)
        shape (merge canvas-default-props shape)]
    (letfn [(on-double-click [event]
              (dom/prevent-default event)
              (st/emit! (dw/deselect-all)
                        (dw/select-shape (:id shape))))]
      [:g.shape {:class (when selected? "selected")
                 :on-double-click on-double-click
                 :on-mouse-down on-mouse-down}
       [:& rect-shape {:shape shape}]])))



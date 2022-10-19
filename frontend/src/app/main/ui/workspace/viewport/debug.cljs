;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.debug
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.layout :as gsl]
   [app.common.pages.helpers :as cph]
   [rumext.v2 :as mf]))

(mf/defc debug-layout
  "Debug component to show the auto-layout drop areas"
  {::mf/wrap-props false}
  [props]

  (let [objects            (unchecked-get props "objects")
        selected-shapes    (unchecked-get props "selected-shapes")
        hover-top-frame-id (unchecked-get props "hover-top-frame-id")

        selected-frame
        (when (and (= (count selected-shapes) 1) (= :frame (-> selected-shapes first :type)))
          (first selected-shapes))

        shape (or selected-frame (get objects hover-top-frame-id))]
    
    (when (and shape (:layout shape))
      (let [children (cph/get-immediate-children objects (:id shape))
            layout-data (gsl/calc-layout-data shape children)
            drop-areas (gsl/layout-drop-areas shape layout-data children)]
        [:g.debug-layout {:pointer-events "none"
                          :transform (gsh/transform-str shape)}
         (for [[idx drop-area] (d/enumerate drop-areas)]
           [:g.drop-area {:key (dm/str "drop-area-" idx)}
            [:rect {:x (:x drop-area)
                    :y (:y drop-area)
                    :width (:width drop-area)
                    :height (:height drop-area)
                    :style {:fill "blue"
                            :fill-opacity 0.3
                            :stroke "red"
                            :stroke-width 1
                            :stroke-dasharray "3 6"}}]
            [:text {:x (:x drop-area)
                    :y (:y drop-area)
                    :width (:width drop-area)
                    :height (:height drop-area)
                    :alignment-baseline "hanging"
                    :fill "black"}
             (:index drop-area)]])]))))

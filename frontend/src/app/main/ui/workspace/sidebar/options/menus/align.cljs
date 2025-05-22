;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.align
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc align-options
  []
  (let [selected            (mf/deref refs/selected-shapes)
        ;; don't need to watch objects, only read the value
        objects             (deref refs/workspace-page-objects)

        disabled-align      (not (dw/can-align? selected objects))
        disabled-distribute (not (dw/can-distribute? selected))

        align-objects
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (dw/align-objects value)))))

        distribute-objects
        (mf/use-fn
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (dw/distribute-objects value)))))]

    (when (not  (and disabled-align disabled-distribute))
      [:div {:class (stl/css :align-options)}
       [:div {:class (stl/css :align-group-horizontal)}
        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hleft" (sc/get-tooltip :align-left))
                  :data-value "hleft"
                  :on-click align-objects}
         i/align-left]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hcenter" (sc/get-tooltip :align-hcenter))
                  :data-value "hcenter"
                  :on-click align-objects}
         i/align-horizontal-center]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hright" (sc/get-tooltip :align-right))
                  :data-value "hright"
                  :on-click align-objects}
         i/align-right]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-distribute)
                  :disabled disabled-distribute
                  :title (tr "workspace.align.hdistribute" (sc/get-tooltip :h-distribute))
                  :data-value "horizontal"
                  :on-click distribute-objects}
         i/distribute-horizontally]]

       [:div {:class (stl/css :align-group-vertical)}
        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vtop" (sc/get-tooltip :align-top))
                  :data-value "vtop"
                  :on-click  align-objects}
         i/align-top]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vcenter" (sc/get-tooltip :align-vcenter))
                  :data-value "vcenter"
                  :on-click  align-objects}
         i/align-vertical-center]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vbottom" (sc/get-tooltip :align-bottom))
                  :data-value "vbottom"
                  :on-click  align-objects}
         i/align-bottom]

        [:button {:title (tr "workspace.align.vdistribute" (sc/get-tooltip :v-distribute))
                  :class (stl/css-case :align-button true
                                       :disabled disabled-distribute)
                  :disabled disabled-distribute
                  :data-value "vertical"
                  :on-click distribute-objects}
         i/distribute-vertical-spacing]]])))


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
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc align-options*
  [{:keys [shapes objects]}]
  (let [disabled-align
        (not (dw/can-align? shapes objects))

        disabled-distribute
        (not (dw/can-distribute? shapes))

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

    (when-not (and disabled-align disabled-distribute)
      [:div {:class (stl/css :align-options)}
       [:div {:class (stl/css :align-group-horizontal)}
        [:> icon-button* {:variant "ghost"
                          :icon i/align-left
                          :aria-label (tr "workspace.align.hleft" (sc/get-tooltip :align-left))
                          :on-click align-objects
                          :data-value "hleft"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/align-horizontal-center
                          :aria-label (tr "workspace.align.hcenter" (sc/get-tooltip :align-hcenter))
                          :on-click align-objects
                          :data-value "hcenter"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/align-right
                          :aria-label (tr "workspace.align.hright" (sc/get-tooltip :align-right))
                          :on-click align-objects
                          :data-value "hright"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/distribute-horizontally
                          :aria-label (tr "workspace.align.hdistribute" (sc/get-tooltip :h-distribute))
                          :on-click distribute-objects
                          :data-value "horizontal"
                          :disabled disabled-distribute}]]

       [:div {:class (stl/css :align-group-vertical)}
        [:> icon-button* {:variant "ghost"
                          :icon i/align-top
                          :aria-label (tr "workspace.align.vtop" (sc/get-tooltip :align-top))
                          :on-click align-objects
                          :data-value "vtop"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/align-vertical-center
                          :aria-label (tr "workspace.align.vcenter" (sc/get-tooltip :align-vcenter))
                          :on-click align-objects
                          :data-value "vcenter"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/align-bottom
                          :aria-label (tr "workspace.align.vbottom" (sc/get-tooltip :align-bottom))
                          :on-click align-objects
                          :data-value "vbottom"
                          :disabled disabled-align}]

        [:> icon-button* {:variant "ghost"
                          :icon i/distribute-vertical-spacing
                          :aria-label (tr "workspace.align.vdistribute" (sc/get-tooltip :v-distribute))
                          :on-click distribute-objects
                          :data-value "vertical"
                          :disabled disabled-distribute}]]])))

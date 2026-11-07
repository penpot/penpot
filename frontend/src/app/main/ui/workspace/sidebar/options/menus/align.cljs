;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.sidebar.options.menus.align
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path :as dwdp]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc align-options*
  ;; Align path nodes or whole shapes for the current edit mode.
  [{:keys [shapes objects path-edit? node-count]}]
  (let [node-count (or node-count 0)

        disabled-align
        (if path-edit?
          (< node-count 2)
          (not (dw/can-align? shapes objects)))

        disabled-distribute
        (if path-edit?
          (< node-count 3)
          (not (dw/can-distribute? shapes)))

        align-objects
        (mf/use-fn
         (mf/deps path-edit?)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (if path-edit?
                         (dwdp/align-nodes value)
                         (dw/align-objects value))))))

        distribute-objects
        (mf/use-fn
         (mf/deps path-edit?)
         (fn [event]
           (let [value (-> (dom/get-current-target event)
                           (dom/get-data "value")
                           (keyword))]
             (st/emit! (if path-edit?
                         (dwdp/distribute-nodes value)
                         (dw/distribute-objects value))))))]

    ;; Keep path controls visible while their actions are disabled.
    (when (or path-edit? (not (and disabled-align disabled-distribute)))
      [:div {:class (stl/css :align-options)}
       [:div {:class (stl/css :align-group-horizontal)}
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.hleft" (sc/get-tooltip :align-left))
                          :icon i/align-left
                          :disabled disabled-align
                          :data-value "hleft"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.hcenter" (sc/get-tooltip :align-hcenter))
                          :icon i/align-horizontal-center
                          :disabled disabled-align
                          :data-value "hcenter"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.hright" (sc/get-tooltip :align-right))
                          :icon i/align-right
                          :disabled disabled-align
                          :data-value "hright"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.hdistribute" (sc/get-tooltip :h-distribute))
                          :icon i/distribute-horizontally
                          :disabled disabled-distribute
                          :data-value "horizontal"
                          :on-click distribute-objects}]]

       [:div {:class (stl/css :align-group-vertical)}
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.vtop" (sc/get-tooltip :align-top))
                          :icon i/align-top
                          :disabled disabled-align
                          :data-value "vtop"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.vcenter" (sc/get-tooltip :align-vcenter))
                          :icon i/align-vertical-center
                          :disabled disabled-align
                          :data-value "vcenter"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.vbottom" (sc/get-tooltip :align-bottom))
                          :icon i/align-bottom
                          :disabled disabled-align
                          :data-value "vbottom"
                          :on-click align-objects}]

        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.align.vdistribute" (sc/get-tooltip :v-distribute))
                          :icon i/distribute-vertical-spacing
                          :disabled disabled-distribute
                          :data-value "vertical"
                          :on-click distribute-objects}]]])))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.options.menus.align
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path :as dwdp]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.icons :as deprecated-icon]
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
        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hleft" (sc/get-tooltip :align-left))
                  :data-value "hleft"
                  :on-click align-objects}
         deprecated-icon/align-left]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hcenter" (sc/get-tooltip :align-hcenter))
                  :data-value "hcenter"
                  :on-click align-objects}
         deprecated-icon/align-horizontal-center]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.hright" (sc/get-tooltip :align-right))
                  :data-value "hright"
                  :on-click align-objects}
         deprecated-icon/align-right]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-distribute)
                  :disabled disabled-distribute
                  :title (tr "workspace.align.hdistribute" (sc/get-tooltip :h-distribute))
                  :data-value "horizontal"
                  :on-click distribute-objects}
         deprecated-icon/distribute-horizontally]]

       [:div {:class (stl/css :align-group-vertical)}
        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vtop" (sc/get-tooltip :align-top))
                  :data-value "vtop"
                  :on-click  align-objects}
         deprecated-icon/align-top]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vcenter" (sc/get-tooltip :align-vcenter))
                  :data-value "vcenter"
                  :on-click  align-objects}
         deprecated-icon/align-vertical-center]

        [:button {:class (stl/css-case :align-button true
                                       :disabled disabled-align)
                  :disabled disabled-align
                  :title (tr "workspace.align.vbottom" (sc/get-tooltip :align-bottom))
                  :data-value "vbottom"
                  :on-click  align-objects}
         deprecated-icon/align-bottom]

        [:button {:title (tr "workspace.align.vdistribute" (sc/get-tooltip :v-distribute))
                  :class (stl/css-case :align-button true
                                       :disabled disabled-distribute)
                  :disabled disabled-distribute
                  :data-value "vertical"
                  :on-click distribute-objects}
         deprecated-icon/distribute-vertical-spacing]]])))

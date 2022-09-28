;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.align
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc align-options
  []
  (let [selected (mf/deref refs/selected-shapes)

        ;; don't need to watch objects, only read the value
        objects  (deref refs/workspace-page-objects)

        disabled (not (dw/can-align? selected objects))

        disabled-distribute (not(dw/can-distribute? selected))]

    [:div.align-options
     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hleft" (sc/get-tooltip :align-left))
        :class (when disabled "disabled")
        :on-click #(st/emit! (dw/align-objects :hleft))}
       i/shape-halign-left]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hcenter" (sc/get-tooltip :align-hcenter))
        :class (when disabled "disabled")
        :on-click  #(st/emit! (dw/align-objects :hcenter))}
       i/shape-halign-center]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hright" (sc/get-tooltip :align-right))
        :class (when disabled "disabled")
        :on-click  #(st/emit! (dw/align-objects :hright))}
       i/shape-halign-right]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hdistribute" (sc/get-tooltip :h-distribute))
        :class (when disabled-distribute "disabled")
        :on-click #(st/emit! (dw/distribute-objects :horizontal))}
       i/shape-hdistribute]]

     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vtop" (sc/get-tooltip :align-top))
        :class (when disabled "disabled")
        :on-click  #(st/emit! (dw/align-objects :vtop))}
       i/shape-valign-top]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vcenter" (sc/get-tooltip :align-vcenter))
        :class (when disabled "disabled")
        :on-click  #(st/emit! (dw/align-objects :vcenter))}
       i/shape-valign-center]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vbottom" (sc/get-tooltip :align-bottom))
        :class (when disabled "disabled")
        :on-click  #(st/emit! (dw/align-objects :vbottom))}
       i/shape-valign-bottom]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vdistribute" (sc/get-tooltip :v-distribute))
        :class (when disabled-distribute "disabled")
        :on-click #(st/emit! (dw/distribute-objects :vertical))}
       i/shape-vdistribute]]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.align
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc align-options
  []
  (let [selected (mf/deref refs/selected-shapes)

        ;; don't need to watch objects, only read the value
        objects  (deref refs/workspace-page-objects)

        disabled (cond
                   (empty? selected) true
                   (> (count selected) 1) false
                   :else
                   (= uuid/zero (:frame-id (get objects (first selected)))))

        disabled-distribute (cond
                              (empty? selected) true
                              (< (count selected) 2) true
                              :else false)

        on-align-button-clicked
        (fn [axis] (when-not disabled (st/emit! (dw/align-objects axis))))

        on-distribute-button-clicked
        (fn [axis] (when-not disabled-distribute (st/emit! (dw/distribute-objects axis))))]

    [:div.align-options
     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hleft")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :hleft)}
       i/shape-halign-left]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hcenter")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :hcenter)}
       i/shape-halign-center]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hright")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :hright)}
       i/shape-halign-right]

      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.hdistribute")
        :class (when disabled-distribute "disabled")
        :on-click #(on-distribute-button-clicked :horizontal)}
       i/shape-hdistribute]]

     [:div.align-group
      [:div.align-button.tooltip.tooltip-bottom
       {:alt (tr "workspace.align.vtop")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :vtop)}
       i/shape-valign-top]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vcenter")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :vcenter)}
       i/shape-valign-center]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vbottom")
        :class (when disabled "disabled")
        :on-click #(on-align-button-clicked :vbottom)}
       i/shape-valign-bottom]

      [:div.align-button.tooltip.tooltip-bottom-left
       {:alt (tr "workspace.align.vdistribute")
        :class (when disabled-distribute "disabled")
        :on-click #(on-distribute-button-clicked :vertical)}
       i/shape-vdistribute]]]))


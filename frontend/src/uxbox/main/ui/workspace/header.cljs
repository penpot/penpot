;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.header
  (:require
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.config :as cfg]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.undo :as udu]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.images :refer [import-image-modal]]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt]))

;; --- Zoom Widget

(mf/defc zoom-widget
  {:wrap [mf/wrap-memo]}
  [props]
  (let [zoom (mf/deref refs/selected-zoom)
        increase #(st/emit! dw/increase-zoom)
        decrease #(st/emit! dw/decrease-zoom)]
    [:ul.options-view
     [:li.zoom-input
      [:span.add-zoom {:on-click decrease} "-"]
      [:span {} (str (mth/round (* 100 zoom)) "%")]
      [:span.remove-zoom {:on-click increase} "+"]]]))

;; --- Header Users

(mf/defc user-widget
  [{:keys [user self?] :as props}]
  [:li.tooltip.tooltip-bottom
   {:alt (:fullname user)
    :on-click (when self?
                #(st/emit! (rt/navigate :settings/profile)))}
   [:img {:style {:border-color (:color user)}
          :src (if self? "/images/avatar.jpg" "/images/avatar-red.jpg")}]])

(mf/defc active-users
  [props]
  (let [profile (mf/deref refs/profile)
        users (mf/deref refs/workspace-users)]
    [:ul.user-multi
     [:& user-widget {:user profile :self? true}]
     (for [id (->> (:active users)
                   (remove #(= % (:id profile))))]
       [:& user-widget {:user (get-in users [:by-id id])
                        :key id}])]))

;; --- Header Component

(mf/defc header
  [{:keys [page] :as props}]
  (let [toggle #(st/emit! (dw/toggle-flag %))
        toggle-layout #(st/emit! (dw/toggle-layout-flag %))
        on-undo #(st/emit! (udu/undo))
        on-redo #(st/emit! (udu/redo))
        on-image #(modal/show! import-image-modal {})
        ;;on-download #(udl/open! :download)
        layout (mf/deref refs/workspace-layout)
        flags (mf/deref refs/selected-flags)
        file (mf/deref refs/workspace-file)
        selected-drawtool (mf/deref refs/selected-drawing-tool)
        select-drawtool #(st/emit! :interrupt
                                   (dw/deactivate-ruler)
                                   (dw/select-for-drawing %))]

    [:header#workspace-bar.workspace-bar
     [:div.main-icon
      [:a {:on-click #(st/emit! (rt/nav :dashboard-projects))} i/logo-icon]]

     [:div.project-tree-btn
      {:alt (tr "header.sitemap")
       :class (when (contains? layout :sitemap) "selected")
       :on-click #(st/emit! (dw/toggle-layout-flag :sitemap))}
      [:span (:project-name file) " / " (:name file)]]

     [:& active-users]

     [:div.workspace-options
      [:ul.options-btn
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.canvas")
         :class (when (= selected-drawtool :canvas) "selected")
         :on-click (partial select-drawtool :canvas)}
        i/artboard]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.rect")
         :class (when (= selected-drawtool :rect) "selected")
         :on-click (partial select-drawtool :rect)}
        i/box]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.circle")
         :class (when (= selected-drawtool :circle) "selected")
         :on-click (partial select-drawtool :circle)}
        i/circle]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.text")
         :class (when (= selected-drawtool :text) "selected")
         :on-click (partial select-drawtool :text)}
        i/text]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.path")
         :class (when (= selected-drawtool :path) "selected")
         :on-click (partial select-drawtool :path)}
        i/curve]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.curve")
         :class (when (= selected-drawtool :curve) "selected")
         :on-click (partial select-drawtool :curve)}
        i/pencil]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.color-palette")
         :class (when (contains? layout :colorpalette) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flag :colorpalette))}
        i/palette]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.icons")
         :class (when (contains? layout :icons) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flag :icons))}
        i/icon-set]
       ;; [:li.tooltip.tooltip-bottom
       ;;  {:alt (tr "header.layers")
       ;;   :class (when (contains? layout :layers) "selected")
       ;;   :on-click #(st/emit! (dw/toggle-layout-flag :layers))}
       ;;  i/layers]
       ;; [:li.tooltip.tooltip-bottom
       ;;  {:alt (tr "header.element-options")
       ;;   :class (when (contains? layout :element-options) "selected")
       ;;   :on-click #(st/emit! (dw/toggle-layout-flag :element-options))}
       ;;  i/options]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.document-history")
         :class (when (contains? layout :document-history) "selected")
         :on-click #(st/emit! (dw/toggle-layout-flag :document-history))}
        i/undo-history]
       ;; [:li.tooltip.tooltip-bottom
       ;;  {:alt (tr "header.undo")
       ;;   :on-click on-undo}
       ;;  i/undo]
       ;; [:li.tooltip.tooltip-bottom
       ;;  {:alt (tr "header.redo")
       ;;   :on-click on-redo}
       ;;  i/redo]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.download")
         ;; :on-click on-download
         }
        i/download]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.image")
         :on-click on-image}
        i/image]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.rules")
         :class (when (contains? layout :rules) "selected")
         :on-click (partial toggle-layout :rules)}
        i/ruler]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.grid")
         :class (when (contains? flags :grid) "selected")
         :on-click (partial toggle :grid)}
        i/grid]
       [:li.tooltip.tooltip-bottom
        {:alt (tr "workspace.header.grid-snap")
         :class (when (contains? flags :grid-snap) "selected")
         :on-click (partial toggle :grid-snap)}
        i/grid-snap]]]
     ;; [:li.tooltip.tooltip-bottom
     ;;  {:alt (tr "header.align")}
     ;;  i/alignment]]
     ;; [:& user]
     [:div.secondary-options
      [:& zoom-widget]
      [:a.tooltip.tooltip-bottom.view-mode
       {:alt (tr "workspace.header.view-mode")
        ;; :on-click #(st/emit! (dw/->OpenView (:id page)))
        }
       i/play]]
     ]))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.viewer.header
  (:require
   [beicon.core :as rx]
   [goog.events :as events]
   [goog.object :as gobj]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.data.viewer :as dv]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt])
  (:import goog.events.EventType
           goog.events.KeyCodes))

(mf/defc zoom-widget
  {:wrap [mf/memo]}
  [{:keys [zoom] :as props}]
  (let [show-dropdown? (mf/use-state false)
        increase #(st/emit! dv/increase-zoom)
        decrease #(st/emit! dv/decrease-zoom)
        zoom-to-50 #(st/emit! dv/zoom-to-50)
        zoom-to-100 #(st/emit! dv/reset-zoom)
        zoom-to-200 #(st/emit! dv/zoom-to-200)]
    [:div.zoom-widget
     [:span.add-zoom {:on-click decrease} "-"]
     [:div.input-container {:on-click #(reset! show-dropdown? true)}
      [:span {} (str (mth/round (* 100 zoom)) "%")]
      [:span.dropdown-button i/arrow-down]
      [:& dropdown {:show @show-dropdown?
                    :on-close #(reset! show-dropdown? false)}
       [:ul.zoom-dropdown
        [:li {:on-click increase}
         "Zoom in" [:span "+"]]
        [:li {:on-click decrease}
         "Zoom out" [:span "-"]]
        [:li {:on-click zoom-to-50}
         "Zoom to 50%"]
        [:li {:on-click zoom-to-100}
         "Zoom to 100%" [:span "Shift + 0"]]
        [:li {:on-click zoom-to-200}
         "Zoom to 200%"]]]]
     [:span.remove-zoom {:on-click increase} "+"]]))

(mf/defc header
  [{:keys [data index local] :as props}]
  (let [{:keys [project file page frames]} data
        total (count frames)
        on-click #(st/emit! dv/toggle-thumbnails-panel)
        on-edit #(st/emit! (rt/nav :workspace
                                   {:project-id (get-in data [:project :id])
                                    :file-id (get-in data [:file :id])}
                                   {:page-id (get-in data [:page :id])}))]
    [:header.viewer-header
     [:div.main-icon
      [:a i/logo-icon]]

     [:div.sitemap-zone {:alt (tr "header.sitemap")
                         :on-click on-click}
      [:span.project-name (:name project)]
      [:span "/"]
      [:span.file-name (:name file)]
      [:span "/"]
      [:span.page-name (:name page)]
      [:span.dropdown-button i/arrow-down]
      [:span.counters (str (inc index) " / " total)]]

     [:div.options-zone
      [:span.btn-primary {:on-click on-edit} "Edit page"]
      [:& zoom-widget {:zoom (:zoom local)}]
      [:span.btn-fullscreen.tooltip.tooltip-bottom {:alt "Full screen"} i/full-screen]]]))


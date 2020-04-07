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
   [uxbox.main.refs :as refs]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.uuid :as uuid]
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

(mf/defc share-link
  [{:keys [page] :as props}]
  (let [show-dropdown? (mf/use-state false)
        dropdown-ref (mf/use-ref)
        token (:share-token page)

        create #(st/emit! dv/create-share-link)
        delete #(st/emit! dv/delete-share-link)
        href (.-href js/location)]
    [:*
     [:span.btn-share.tooltip.tooltip-bottom
      {:alt "Share link"
       :on-click #(swap! show-dropdown? not)}
      i/exit]

     [:& dropdown {:show @show-dropdown?
                   :on-close #(swap! show-dropdown? not)
                   :container dropdown-ref}
      [:div.share-link-dropdown {:ref dropdown-ref}
       [:span.share-link-title "Share link"]
       [:div.share-link-input
        (if (string? token)
          [:span.link (str href "&token=" token)]
          [:span "Share link will apear here"])
        i/chain]
       [:span.share-link-subtitle "Anyone with the link will have access"]
       [:div.share-link-buttons
        (if (string? token)
          [:button.btn-delete {:on-click delete} "Remove link"]
          [:button.btn-primary {:on-click create} "Create link"])]]]]))

(mf/defc header
  [{:keys [data index local fullscreen? toggle-fullscreen] :as props}]
  (let [{:keys [project file page frames]} data
        total (count frames)
        on-click #(st/emit! dv/toggle-thumbnails-panel)

        profile (mf/deref refs/profile)
        anonymous? (= uuid/zero (:id profile))

        project-id (get-in data [:project :id])
        file-id (get-in data [:file :id])
        page-id (get-in data [:page :id])

        on-edit #(st/emit! (rt/nav :workspace
                                   {:project-id project-id
                                    :file-id file-id}
                                   {:page-id page-id}))]
    [:header.viewer-header
     [:div.main-icon
      [:a {:on-click on-edit} i/logo-icon]]

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
      (when-not anonymous?
        [:& share-link {:page (:page data)}])
      (when-not anonymous?
        [:span.btn-primary {:on-click on-edit} "Edit page"])
      [:& zoom-widget {:zoom (:zoom local)}]
      [:span.btn-fullscreen.tooltip.tooltip-bottom
       {:alt "Full screen"
        :on-click toggle-fullscreen}
       i/full-screen]
      ]]))



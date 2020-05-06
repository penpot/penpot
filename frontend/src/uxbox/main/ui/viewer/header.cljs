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
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i]
   [uxbox.main.data.messages :as dm]
   [uxbox.main.data.viewer :as dv]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.ui.workspace.header :refer [zoom-widget]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.router :as rt]
   [uxbox.common.uuid :as uuid]
   [uxbox.util.webapi :as wapi]))

(mf/defc interactions-menu
  [{:keys [interactions-mode] :as props}]
  (let [show-dropdown? (mf/use-state false)
        locale (i18n/use-locale)
        on-select-mode #(st/emit! (dv/set-interactions-mode %))]
    [:div.header-icon
     [:a {:on-click #(swap! show-dropdown? not)} i/eye
      [:& dropdown {:show @show-dropdown?
                    :on-close #(swap! show-dropdown? not)}
       [:ul.custom-select-dropdown
        [:li {:key :hide
              :class (classnames :selected (= interactions-mode :hide))
              :on-click #(on-select-mode :hide)}
         (t locale "viewer.header.dont-show-interactions")]
        [:li {:key :show
              :class (classnames :selected (= interactions-mode :show))
              :on-click #(on-select-mode :show)}
         (t locale "viewer.header.show-interactions")]
        [:li {:key :show-on-click
              :class (classnames :selected (= interactions-mode :show-on-click))
              :on-click #(on-select-mode :show-on-click)}
         (t locale "viewer.header.show-interactions-on-click")]]]]]))

(mf/defc share-link
  [{:keys [page] :as props}]
  (let [show-dropdown? (mf/use-state false)
        dropdown-ref (mf/use-ref)
        token (:share-token page)

        locale (i18n/use-locale)

        create #(st/emit! dv/create-share-link)
        delete #(st/emit! dv/delete-share-link)

        href (.-href js/location)
        link (str href "&token=" token)

        copy-link
        (fn [event]
          (wapi/write-to-clipboard link)
          (st/emit! (dm/show {:type :info
                              :content "Link copied successfuly!"
                              :timeout 2000})))]
    [:*
     [:span.btn-primary.btn-small
      {:alt (t locale "viewer.header.share.title")
       :on-click #(swap! show-dropdown? not)}
      (t locale "viewer.header.share.title")]

     [:& dropdown {:show @show-dropdown?
                   :on-close #(swap! show-dropdown? not)
                   :container dropdown-ref}
      [:div.share-link-dropdown {:ref dropdown-ref}
       [:span.share-link-title (t locale "viewer.header.share.title")]
       [:div.share-link-input
        (if (string? token)
          [:span.link link]
          [:span.link-placeholder (t locale "viewer.header.share.placeholder")])
        [:span.link-button {:on-click copy-link}
         (t locale "viewer.header.share.copy-link")]]

       [:span.share-link-subtitle (t locale "viewer.header.share.subtitle")]
       [:div.share-link-buttons
        (if (string? token)
          [:button.btn-delete {:on-click delete}
           (t locale "viewer.header.share.remove-link")]
          [:button.btn-primary {:on-click create}
           (t locale "viewer.header.share.create-link")])]]]]))

(mf/defc header
  [{:keys [data index local fullscreen? toggle-fullscreen] :as props}]
  (let [{:keys [project file page frames]} data
        total (count frames)
        on-click #(st/emit! dv/toggle-thumbnails-panel)

        interactions-mode (:interactions-mode local)

        locale (i18n/use-locale)

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

     [:div.sitemap-zone {:alt (t locale "viewer.header.sitemap")
                         :on-click on-click}
      [:span.project-name (:name project)]
      [:span "/"]
      [:span.file-name (:name file)]
      [:span "/"]
      [:span.page-name (:name page)]
      [:span.dropdown-button i/arrow-down]
      [:span.counters (str (inc index) " / " total)]]

     [:div.options-zone
      [:& interactions-menu {:interactions-mode interactions-mode}]
      (when-not anonymous?
        [:& share-link {:page (:page data)}])
      (when-not anonymous?
        [:a {:on-click on-edit}
         (t locale "viewer.header.edit-page")])

      [:& zoom-widget
       {:zoom (:zoom local)
        :on-increase #(st/emit! dv/increase-zoom)
        :on-decrease #(st/emit! dv/decrease-zoom)
        :on-zoom-to-50 #(st/emit! dv/zoom-to-50)
        :on-zoom-to-100 #(st/emit! dv/reset-zoom)
        :on-zoom-to-200 #(st/emit! dv/zoom-to-200)}]

      [:span.btn-fullscreen.tooltip.tooltip-bottom
       {:alt (t locale "viewer.header.fullscreen")
        :on-click toggle-fullscreen}
       (if fullscreen?
         i/full-screen-off
         i/full-screen)]
      ]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.viewer.header
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.main.ui.icons :as i]
   [app.main.data.messages :as dm]
   [app.main.data.viewer :as dv]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.router :as rt]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.util.webapi :as wapi]))

(mf/defc zoom-widget
  {:wrap [mf/memo]}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-to-50
           on-zoom-to-100
           on-zoom-to-200]
    :as props}]
  (let [show-dropdown? (mf/use-state false)]
    [:div.zoom-widget {:on-click #(reset! show-dropdown? true)}
     [:span {} (str (mth/round (* 100 zoom)) "%")]
     [:span.dropdown-button i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close #(reset! show-dropdown? false)}
      [:ul.zoom-dropdown
       [:li {:on-click on-increase}
        "Zoom in" [:span "+"]]
       [:li {:on-click on-decrease}
        "Zoom out" [:span "-"]]
       [:li {:on-click on-zoom-to-50}
        "Zoom to 50%" [:span "Shift + 0"]]
       [:li {:on-click on-zoom-to-100}
        "Zoom to 100%" [:span "Shift + 1"]]
       [:li {:on-click on-zoom-to-200}
        "Zoom to 200%" [:span "Shift + 2"]]]]]))

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
  [{:keys [page token] :as props}]
  (let [show-dropdown? (mf/use-state false)
        dropdown-ref   (mf/use-ref)
        locale         (mf/deref i18n/locale)

        create #(st/emit! dv/create-share-link)
        delete #(st/emit! dv/delete-share-link)

        href (.-href js/location)
        href (subs href 0 (str/index-of href "?"))
        link (str href "?token=" token "&index=0")

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
          [:*
            [:span.link link]
            [:span.link-button {:on-click copy-link}
             (t locale "viewer.header.share.copy-link")]]
          [:span.link-placeholder (t locale "viewer.header.share.placeholder")])]

       [:span.share-link-subtitle (t locale "viewer.header.share.subtitle")]
       [:div.share-link-buttons
        (if (string? token)
          [:button.btn-warning {:on-click delete}
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
        [:& share-link {:token (:share-token data)
                        :page  (:page data)}])

      (when-not anonymous?
        [:a.btn-text-basic.btn-small {:on-click on-edit}
         (t locale "viewer.header.edit-page")])

      [:& zoom-widget
       {:zoom (:zoom local)
        :on-increase #(st/emit! dv/increase-zoom)
        :on-decrease #(st/emit! dv/decrease-zoom)
        :on-zoom-to-50 #(st/emit! dv/zoom-to-50)
        :on-zoom-to-100 #(st/emit! dv/reset-zoom)
        :on-zoom-to-200 #(st/emit! dv/zoom-to-200)}]

      [:span.btn-icon-dark.btn-small.tooltip.tooltip-bottom
       {:alt (t locale "viewer.header.fullscreen")
        :on-click toggle-fullscreen}
       (if fullscreen?
         i/full-screen-off
         i/full-screen)]
      ]]))


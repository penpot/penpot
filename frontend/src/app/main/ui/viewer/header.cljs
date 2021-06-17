;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.header
  (:require
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.config :as cfg]
   [app.main.data.comments :as dcm]
   [app.main.data.messages :as dm]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.fullscreen :as fs]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.webapi :as wapi]
   [rumext.alpha :as mf]))

(mf/defc zoom-widget
  {:wrap [mf/memo]}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-to-50
           on-zoom-to-100
           on-zoom-to-200
           on-fullscreen]
    :as props}]
  (let [show-dropdown? (mf/use-state false)]
    [:div.zoom-widget {:on-click #(reset! show-dropdown? true)}
     [:span {} (str (mth/round (* 100 zoom)) "%")]
     [:span.dropdown-button i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close #(reset! show-dropdown? false)}
      [:ul.dropdown.zoom-dropdown
       [:li {:on-click on-increase}
        "Zoom in" [:span (sc/get-tooltip :increase-zoom)]]
       [:li {:on-click on-decrease}
        "Zoom out" [:span (sc/get-tooltip :decrease-zoom)]]
       [:li {:on-click on-zoom-to-50}
        "Zoom to 50%" [:span (sc/get-tooltip :zoom-50)]]
       [:li {:on-click on-zoom-to-100}
        "Zoom to 100%" [:span (sc/get-tooltip :reset-zoom)]]
       [:li {:on-click on-zoom-to-200}
        "Zoom to 200%" [:span (sc/get-tooltip :zoom-200)]]
       [:li {:on-click on-fullscreen}
        "Full screen"]]]]))
        ;; "Full screen" [:span (sc/get-tooltip :full-screen)]]]]]))

(mf/defc share-link
  [{:keys [token] :as props}]
  (let [show-dropdown? (mf/use-state false)
        dropdown-ref   (mf/use-ref)
        create (st/emitf (dv/create-share-link))
        delete (st/emitf (dv/delete-share-link))

        router (mf/deref refs/router)
        route  (mf/deref refs/route)
        link   (rt/resolve router
                           :viewer
                           (:path-params route)
                           {:token token :index "0"})
        link   (assoc cfg/public-uri :fragment link)

        copy-link
        (fn [_]
          (wapi/write-to-clipboard (str link))
          (st/emit! (dm/show {:type :info
                              :content "Link copied successfuly!"
                              :timeout 3000})))]
    [:*
     [:span.btn-primary.btn-small
      {:alt (tr "viewer.header.share.title")
       :on-click #(swap! show-dropdown? not)}
      (tr "viewer.header.share.title")]

     [:& dropdown {:show @show-dropdown?
                   :on-close #(swap! show-dropdown? not)
                   :container dropdown-ref}
      [:div.dropdown.share-link-dropdown {:ref dropdown-ref}
       [:span.share-link-title (tr "viewer.header.share.title")]
       [:div.share-link-input
        (if (string? token)
          [:*
            [:span.link (str link)]
            [:span.link-button {:on-click copy-link}
             (tr "viewer.header.share.copy-link")]]
          [:span.link-placeholder (tr "viewer.header.share.placeholder")])]

       [:span.share-link-subtitle (tr "viewer.header.share.subtitle")]
       [:div.share-link-buttons
        (if (string? token)
          [:button.btn-warning {:on-click delete}
           (tr "viewer.header.share.remove-link")]
          [:button.btn-primary {:on-click create}
           (tr "viewer.header.share.create-link")])]]]]))

(mf/defc interactions-menu
  [{:keys [state] :as props}]
  (let [imode          (:interactions-mode state)

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        select-mode
        (mf/use-callback
         (fn [mode]
           (st/emit! (dv/set-interactions-mode mode))))]

    [:div.view-options
     [:div.view-options-dropdown {:on-click toggle-dropdown}
      [:span (tr "viewer.header.interactions")]
      i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul.dropdown.with-check
       [:li {:class (dom/classnames :selected (= imode :hide))
             :on-click #(select-mode :hide)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.dont-show-interactions")]]

       [:li {:class (dom/classnames :selected (= imode :show))
             :on-click #(select-mode :show)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.show-interactions")]]

       [:li {:class (dom/classnames :selected (= imode :show-on-click))
             :on-click #(select-mode :show-on-click)}
        [:span.icon i/tick]
        [:span.label (tr "viewer.header.show-interactions-on-click")]]]]]))

(mf/defc comments-menu
  []
  (let [{cmode :mode cshow :show} (mf/deref refs/comments-local)

        show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        update-mode
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:mode mode}))))

        update-show
        (mf/use-callback
         (fn [mode]
           (st/emit! (dcm/update-filters {:show mode}))))]

    [:div.view-options
     [:div.icon {:on-click toggle-dropdown} i/eye]
     [:& dropdown {:show @show-dropdown?
                   :on-close hide-dropdown}
      [:ul.dropdown.with-check
       [:li {:class (dom/classnames :selected (= :all cmode))
             :on-click #(update-mode :all)}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-all-comments")]]

       [:li {:class (dom/classnames :selected (= :yours cmode))
             :on-click #(update-mode :yours)}
        [:span.icon i/tick]
        [:span.label (tr "labels.show-your-comments")]]

       [:hr]

       [:li {:class (dom/classnames :selected (= :pending cshow))
             :on-click #(update-show (if (= :pending cshow) :all :pending))}
        [:span.icon i/tick]
        [:span.label (tr "labels.hide-resolved-comments")]]]]]))

(mf/defc file-menu
  [{:keys [project-id file-id page-id] :as props}]
  (let [show-dropdown?  (mf/use-state false)
        toggle-dropdown (mf/use-fn #(swap! show-dropdown? not))
        hide-dropdown   (mf/use-fn #(reset! show-dropdown? false))

        on-edit
        (mf/use-callback
         (mf/deps project-id file-id page-id)
         (st/emitf (rt/nav :workspace
                           {:project-id project-id
                            :file-id file-id}
                           {:page-id page-id})))]
    [:div.file-menu
     [:span.btn-icon-dark.btn-small {:on-click toggle-dropdown}
      i/actions
      [:& dropdown {:show @show-dropdown?
                    :on-close hide-dropdown}
       [:ul.dropdown
        [:li {:on-click on-edit}
         [:span.label (tr "viewer.header.edit-file")]]]]]]))

(mf/defc header
  [{:keys [data index section state] :as props}]
  (let [{:keys [project file page frames]} data

        fullscreen (mf/use-ctx fs/fullscreen-context)

        total      (count frames)
        profile    (mf/deref refs/profile)
        teams      (mf/deref refs/teams)

        team-id    (get-in data [:project :team-id])

        has-permission? (and (not= uuid/zero (:id profile))
                             (contains? teams team-id))

        project-id (get-in data [:project :id])
        file-id    (get-in data [:file :id])
        page-id    (get-in data [:page :id])

        on-click
        (mf/use-callback
         (st/emitf dv/toggle-thumbnails-panel))

        on-goback
        (mf/use-callback
         (mf/deps project)
         (st/emitf (dv/go-to-dashboard project)))

        navigate
        (mf/use-callback
         (mf/deps file-id page-id)
         (fn [section]
           (st/emit! (dv/go-to-section section))))

        toggle-fullscreen
        (mf/use-callback
          (mf/deps fullscreen)
          (fn []
            (if @fullscreen (fullscreen false) (fullscreen true))))]

    [:header.viewer-header
     [:div.main-icon
      [:a {:on-click on-goback
           ;; If the user doesn't have permission we disable the link
           :style {:pointer-events (when-not has-permission? "none")}} i/logo-icon]]

     [:div.sitemap-zone {:alt (tr "viewer.header.sitemap")
                         :on-click on-click}
      [:span.project-name (:name project)]
      [:span "/"]
      [:span.file-name (:name file)]
      [:span "/"]
      [:span.page-name (:name page)]
      [:span.show-thumbnails-button i/arrow-down]
      [:span.counters (str (inc index) " / " total)]]

     [:div.mode-zone
      [:button.mode-zone-button.tooltip.tooltip-bottom
       {:on-click #(navigate :interactions)
        :class (dom/classnames :active (= section :interactions))
        :alt "View mode"}
       i/play]

      (when has-permission?
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click #(navigate :comments)
          :class (dom/classnames :active (= section :comments))
          :alt "Comments"}
         i/chat])

      [:button.mode-zone-button.tooltip.tooltip-bottom
       {:on-click #(navigate :handoff)
        :class (dom/classnames :active (= section :handoff))
        :alt "Code mode"}
       i/code]]

     [:div.options-zone
      (case section
        :interactions [:& interactions-menu {:state state}]
        :comments [:& comments-menu]
        nil)

      (when has-permission?
        [:& share-link {:token (:token data)
                        :page  (:page data)}])

      [:& zoom-widget
       {:zoom (:zoom state)
        :on-increase (st/emitf dv/increase-zoom)
        :on-decrease (st/emitf dv/decrease-zoom)
        :on-zoom-to-50 (st/emitf dv/zoom-to-50)
        :on-zoom-to-100 (st/emitf dv/reset-zoom)
        :on-zoom-to-200 (st/emitf dv/zoom-to-200)
        :on-fullscreen toggle-fullscreen}]

      [:span.btn-icon-basic.btn-small.tooltip.tooltip-bottom-left
       {:alt (tr "viewer.header.fullscreen")
        :on-click toggle-fullscreen}
       (if @fullscreen
         i/full-screen-off
         i/full-screen)]

      (when has-permission?
        [:& file-menu {:project-id project-id
                       :file-id file-id
                       :page-id page-id}])]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.header
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.export :refer [export-progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.comments :refer [comments-menu]]
   [app.main.ui.viewer.interactions :refer [flows-menu interactions-menu]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def fullscreen-ref
  (l/derived (fn [state]
               (dm/get-in state [:viewer-local :fullscreen?]))
             st/state))

(defn open-login-dialog
  []
  (modal/show! :login-register {}))

(mf/defc zoom-widget
  {::mf/wrap [mf/memo]}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-reset
           on-fullscreen
           on-zoom-fit
           on-zoom-fill]
    :as props}]
  (let [show-dropdown? (mf/use-state false)]
    [:div.zoom-widget {:on-click #(reset! show-dropdown? true)}
     [:span.label (fmt/format-percent zoom)]
     [:span.icon i/arrow-down]
     [:& dropdown {:show @show-dropdown?
                   :on-close #(reset! show-dropdown? false)}
      [:ul.dropdown
       [:li.basic-zoom-bar
        [:span.zoom-btns
         [:button {:on-click (fn [event]
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (on-decrease))} "-"]
         [:p.zoom-size (fmt/format-percent zoom)]
         [:button {:on-click (fn [event]
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (on-increase))} "+"]]
        [:button.reset-btn {:on-click on-zoom-reset} (tr "workspace.header.reset-zoom")]]
       [:li.separator]
       [:li {:on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit") [:span (sc/get-tooltip :toggle-zoom-style)]]
       [:li {:on-click on-zoom-fill}
        (tr "workspace.header.zoom-fill") [:span (sc/get-tooltip :toggle-zoom-style)]]
       [:li {:on-click on-fullscreen}
        (tr "workspace.header.zoom-full-screen") [:span (sc/get-tooltip :toogle-fullscreen)]]]]]))


(mf/defc header-options
  [{:keys [section zoom page file index permissions]}]
  (let [fullscreen? (mf/deref fullscreen-ref)

        toggle-fullscreen
        (mf/use-callback
         (fn [] (st/emit! dv/toggle-fullscreen)))

        go-to-workspace
        (mf/use-callback
         (mf/deps page)
         (fn []
           (st/emit! (dv/go-to-workspace (:id page)))))

        open-share-dialog
        (mf/use-callback
         (mf/deps page)
         (fn []
           (modal/show! :share-link {:page page :file file})
           (modal/allow-click-outside!)))]

    [:div.options-zone
     (case section
       :interactions [:*
                      (when index
                        [:& flows-menu {:page page :index index}])
                      [:& interactions-menu]]
       :comments [:& comments-menu]

       [:div.view-options])

     [:& export-progress-widget]
     [:& zoom-widget
      {:zoom zoom
       :on-increase #(st/emit! dv/increase-zoom)
       :on-decrease #(st/emit! dv/decrease-zoom)
       :on-zoom-reset #(st/emit! dv/reset-zoom)
       :on-zoom-fill #(st/emit! dv/zoom-to-fill)
       :on-zoom-fit #(st/emit! dv/zoom-to-fit)
       :on-fullscreen toggle-fullscreen}]

     [:span.btn-icon-dark.btn-small.tooltip.tooltip-bottom-left
      {:alt (tr "viewer.header.fullscreen")
       :on-click toggle-fullscreen}
      (if fullscreen?
        i/full-screen-off
        i/full-screen)]

     (when (:is-admin permissions)
       [:span.btn-primary {:on-click open-share-dialog} i/export [:span (tr "labels.share-prototype")]])

     (when (:can-edit permissions)
       [:span.btn-text-dark {:on-click go-to-workspace} (tr "labels.edit-file")])

     (when-not (:is-logged permissions)
       [:span.btn-text-dark {:on-click open-login-dialog} (tr "labels.log-or-sign")])]))

(mf/defc header-sitemap
  [{:keys [project file page frame] :as props}]
  (let [project-name   (:name project)
        file-name      (:name file)
        page-name      (:name page)
        frame-name     (:name frame)
        show-dropdown? (mf/use-state false)

        toggle-thumbnails
        (mf/use-callback
         (fn []
           (st/emit! dv/toggle-thumbnails-panel)))

        open-dropdown
        (mf/use-callback
         (fn []
           (reset! show-dropdown? true)))

        close-dropdown
        (mf/use-callback
         (fn []
           (reset! show-dropdown? false)))

        navigate-to
        (mf/use-callback
         (fn [page-id]
           (st/emit! (dv/go-to-page page-id))
           (reset! show-dropdown? false)))]

    [:div.sitemap-zone {:alt (tr "viewer.header.sitemap")}
     [:div.breadcrumb
      {:on-click open-dropdown}
      [:span.project-name project-name]
      [:span "/"]
      [:span.file-name file-name]
      [:span "/"]

      [:span.page-name page-name]


      [:& dropdown {:show @show-dropdown?
                    :on-close close-dropdown}
       [:ul.dropdown
        (for [id (get-in file [:data :pages])]
          [:li {:id (str id)
                :key (str id)
                :on-click (partial navigate-to id)}
           (get-in file [:data :pages-index id :name])])]]]

     [:span.icon {:on-click open-dropdown} i/arrow-down]
     [:div.current-frame
      {:on-click toggle-thumbnails}
      [:span.label "/"]
      [:span.label frame-name]]
     [:span.icon {:on-click toggle-thumbnails} i/arrow-down]]))


(mf/defc header
  [{:keys [project file page frame zoom section permissions index]}]
  (let [go-to-dashboard
        #(st/emit! (dv/go-to-dashboard))

        go-to-handoff
        (fn[]
          (if (:is-logged permissions)
           (st/emit! dv/close-thumbnails-panel (dv/go-to-section :handoff))
           (open-login-dialog)))

        navigate
        (fn [section]
          (if (or (= section :interactions) (:is-logged permissions))
            (st/emit! (dv/go-to-section section))
            (open-login-dialog)))]

    [:header.viewer-header
     [:div.nav-zone
      [:div.main-icon
       [:a {:on-click go-to-dashboard
            ;; If the user doesn't have permission we disable the link
            :style {:pointer-events (when-not (:can-edit permissions) "none")}} i/logo-icon]]

      [:& header-sitemap {:project project :file file :page page :frame frame :index index}]]

     [:div.mode-zone
      [:button.mode-zone-button.tooltip.tooltip-bottom
       {:on-click #(navigate :interactions)
        :class (dom/classnames :active (= section :interactions))
        :alt (tr "viewer.header.interactions-section" (sc/get-tooltip :open-interactions))}
       i/play]

      (when (or (:can-edit permissions)
                (= (:who-comment permissions) "all"))
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click #(navigate :comments)
          :class (dom/classnames :active (= section :comments))
          :alt (tr "viewer.header.comments-section" (sc/get-tooltip :open-comments))}
         i/chat])

      (when (or (= (:type permissions) :membership)
                (and (= (:type permissions) :share-link)
                     (= (:who-inspect permissions) "all")))
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click go-to-handoff
          :class (dom/classnames :active (= section :handoff))
          :alt (tr "viewer.header.handoff-section" (sc/get-tooltip :open-handoff))}
         i/code])]

     [:& header-options {:section section
                         :permissions permissions
                         :page page
                         :file file
                         :index index
                         :zoom zoom}]]))

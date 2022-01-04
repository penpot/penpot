;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.viewer.header
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.fullscreen :as fs]
   [app.main.ui.icons :as i]
   [app.main.ui.viewer.comments :refer [comments-menu]]
   [app.main.ui.viewer.interactions :refer [flows-menu interactions-menu]]
   [app.main.ui.workspace.header :refer [zoom-widget]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.alpha :as mf]))

(mf/defc header-options
  [{:keys [section zoom page file index permissions]}]
  (let [fullscreen (mf/use-ctx fs/fullscreen-context)

        toggle-fullscreen
        (mf/use-callback
         (mf/deps fullscreen)
         (fn []
           (if @fullscreen (fullscreen false) (fullscreen true))))

        go-to-workspace
        (mf/use-callback
         (mf/deps page)
         (fn []
           (st/emit! (dv/go-to-workspace (:id page)))))

        open-share-dialog
        (mf/use-callback
         (mf/deps page)
         (fn []
           (modal/show! :share-link {:page page :file file})))]

    [:div.options-zone
     (case section
       :interactions [:*
                      (when index
                        [:& flows-menu {:page page :index index}])
                      [:& interactions-menu]]
       :comments [:& comments-menu]

       [:div.view-options])

     [:& zoom-widget
      {:zoom zoom
       :on-increase (st/emitf dv/increase-zoom)
       :on-decrease (st/emitf dv/decrease-zoom)
       :on-zoom-to-50 (st/emitf dv/zoom-to-50)
       :on-zoom-to-100 (st/emitf dv/reset-zoom)
       :on-zoom-to-200 (st/emitf dv/zoom-to-200)
       :on-fullscreen toggle-fullscreen}]

     [:span.btn-icon-dark.btn-small.tooltip.tooltip-bottom-left
      {:alt (tr "viewer.header.fullscreen")
       :on-click toggle-fullscreen}
      (if @fullscreen
        i/full-screen-off
        i/full-screen)]

     (when (:is-admin permissions)
       [:span.btn-primary {:on-click open-share-dialog} (tr "labels.share-prototype")])

     (when (:can-edit permissions)
       [:span.btn-text-dark {:on-click go-to-workspace} (tr "labels.edit-file")])]))

(mf/defc header-sitemap
  [{:keys [project file page frame index] :as props}]
  (let [project-name   (:name project)
        file-name      (:name file)
        page-name      (:name page)
        frame-name     (:name frame)
        total          (count (:frames page))
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
       [:span.icon i/arrow-down]

       [:& dropdown {:show @show-dropdown?
                     :on-close close-dropdown}
        [:ul.dropdown
         (for [id (get-in file [:data :pages])]
           [:li {:id (str id)
                 :on-click (partial navigate-to id)}
            (get-in file [:data :pages-index id :name])])]]]

      [:div.current-frame
       {:on-click toggle-thumbnails}
       [:span.label "/"]
       [:span.label frame-name]
       [:span.icon i/arrow-down]
       [:span.counters (str (inc index) " / " total)]]]))


(mf/defc header
  [{:keys [project file page frame zoom section permissions index]}]
  (let [go-to-dashboard
        (st/emitf (dv/go-to-dashboard))

        navigate
        (fn [section]
          (st/emit! (dv/go-to-section section)))]

    [:header.viewer-header
     [:div.main-icon
      [:a {:on-click go-to-dashboard
           ;; If the user doesn't have permission we disable the link
           :style {:pointer-events (when-not permissions "none")}} i/logo-icon]]

     [:& header-sitemap {:project project :file file :page page :frame frame :index index}]

     [:div.mode-zone
      [:button.mode-zone-button.tooltip.tooltip-bottom
       {:on-click #(navigate :interactions)
        :class (dom/classnames :active (= section :interactions))
        :alt (tr "viewer.header.interactions-section" (sc/get-tooltip :open-interactions))}
       i/play]

      (when (:can-edit permissions)
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click #(navigate :comments)
          :class (dom/classnames :active (= section :comments))
          :alt (tr "viewer.header.comments-section" (sc/get-tooltip :open-comments))}
         i/chat])

      (when (or (= (:type permissions) :membership)
                (and (= (:type permissions) :share-link)
                     (contains? (:flags permissions) :section-handoff)))
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click #(navigate :handoff)
          :class (dom/classnames :active (= section :handoff))
          :alt (tr "viewer.header.handoff-section" (sc/get-tooltip :open-handoff))}
         i/code])]

     [:& header-options {:section section
                         :permissions permissions
                         :page page
                         :file file
                         :index index
                         :zoom zoom}]]))


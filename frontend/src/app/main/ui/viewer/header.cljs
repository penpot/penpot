;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.viewer.header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.viewer :as dv]
   [app.main.data.viewer.shortcuts :as sc]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
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

  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        open*           (mf/use-state false)
        open?           (deref open*)
        open-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* true)))

        close-dropdown
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! open* false)))

        on-increase
        (mf/use-fn
         (mf/deps on-increase)
         (fn [event]
           (dom/stop-propagation event)
           (on-increase)))

        on-decrease
        (mf/use-fn
         (mf/deps on-decrease)
         (fn [event]
           (dom/stop-propagation event)
           (on-decrease)))

        show-dropdown? (mf/use-state false)]

    (if new-css-system
      [:div {:class (stl/css-case :zoom-widget true
                                  :selected open?)
             :on-click open-dropdown
             :title (tr "workspace.header.zoom")}
       [:span {:class (stl/css :label)} (fmt/format-percent zoom)]
       [:& dropdown {:show open?
                     :on-close close-dropdown}
        [:ul {:class (stl/css :dropdown)}
         [:li  {:class (stl/css :basic-zoom-bar)}
          [:span {:class (stl/css :zoom-btns)}
           [:button {:class (stl/css :zoom-btn)
                     :on-click on-decrease}
            [:span {:class (stl/css :zoom-icon)}
             i/remove-refactor]]
           [:p  {:class (stl/css :zoom-text)}
            (fmt/format-percent zoom)]
           [:button {:class (stl/css :zoom-btn)
                     :on-click on-increase}
            [:span {:class (stl/css :zoom-icon)}
             i/add-refactor]]]
          [:button {:class (stl/css :reset-btn)
                    :on-click on-zoom-reset}
           (tr "workspace.header.reset-zoom")]]

         [:li {:class (stl/css :zoom-option)
               :on-click on-zoom-fit}
          (tr "workspace.header.zoom-fit")
          [:span  {:class (stl/css :shortcuts)}
           (for [sc (scd/split-sc (sc/get-tooltip :toggle-zoom-style))]
             [:span {:class (stl/css :shortcut-key)
                     :key (str "zoom-fit-" sc)} sc])]]
         [:li {:class (stl/css :zoom-option)
               :on-click on-zoom-fill}
          (tr "workspace.header.zoom-fill")
          [:span  {:class (stl/css :shortcuts)}
           (for [sc (scd/split-sc (sc/get-tooltip :toggle-zoom-style))]
             [:span {:class (stl/css :shortcut-key)
                     :key (str "zoom-fill-" sc)} sc])]]
         [:li {:class (stl/css :zoom-option)
               :on-click on-fullscreen}
          (tr "workspace.header.zoom-full-screen")
          [:span  {:class (stl/css :shortcuts)}
           (for [sc (scd/split-sc (sc/get-tooltip :toggle-fullscreen))]
             [:span {:class (stl/css :shortcut-key)
                     :key (str "zoom-fullscreen-" sc)} sc])]]]]]


      ;; OLD
      [:div.zoom-widget {:on-click
                         (fn [event]
                           (dom/stop-propagation event)
                           (reset! show-dropdown? true))}
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
          (tr "workspace.header.zoom-full-screen") [:span (sc/get-tooltip :toggle-fullscreen)]]]]])))

(mf/defc header-options
  [{:keys [section zoom page file index permissions interactions-mode]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        fullscreen?    (mf/deref fullscreen-ref)

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
           (modal/allow-click-outside!)))

        handle-increase
        (mf/use-fn
         #(st/emit! dv/increase-zoom))

        handle-decrease
        (mf/use-fn
         #(st/emit! dv/decrease-zoom))

        handle-zoom-reset
        (mf/use-fn
         #(st/emit! dv/reset-zoom))

        handle-zoom-fill
        (mf/use-fn
         #(st/emit! dv/zoom-to-fill))

        handle-zoom-fit
        (mf/use-fn
         #(st/emit! dv/zoom-to-fit))]

    (if new-css-system
      [:div {:class (stl/css :options-zone)}
       (case section
         :interactions [:*
                        (when index
                          [:& flows-menu {:page page :index index}])
                        [:& interactions-menu {:interactions-mode interactions-mode}]]
         :comments [:& comments-menu]
         [:div {:class (stl/css :view-options)}])

       [:& export-progress-widget]

       [:& zoom-widget
        {:zoom zoom
         :on-increase handle-increase
         :on-decrease handle-decrease
         :on-zoom-reset handle-zoom-reset
         :on-zoom-fill handle-zoom-fill
         :on-zoom-fit  handle-zoom-fit
         :on-fullscreen toggle-fullscreen}]

       (when (:can-edit permissions)
         [:span {:on-click go-to-workspace
                 :class (stl/css :edit-btn)}
          i/curve-refactor])

       [:span {:title (tr "viewer.header.fullscreen")
               :class (stl/css-case :fullscreen-btn true
                                    :selected fullscreen?)
               :on-click toggle-fullscreen}
        i/expand-refactor]

       (when (:is-admin permissions)
         [:button {:on-click open-share-dialog
                   :class (stl/css :share-btn)}
          (tr "labels.share")])

       (when-not (:is-logged permissions)
         [:span {:on-click open-login-dialog
                 :class (stl/css :go-log-btn)} (tr "labels.log-or-sign")])]


      ;; OLD
      [:div.options-zone
       (case section
         :interactions [:*
                        (when index
                          [:& flows-menu {:page page :index index}])
                        [:& interactions-menu {:interactions-mode interactions-mode}]]
         :comments [:& comments-menu]

         [:div.view-options])

       [:& export-progress-widget]
       [:& zoom-widget
        {:zoom zoom
         :on-increase handle-increase
         :on-decrease handle-decrease
         :on-zoom-reset handle-zoom-reset
         :on-zoom-fill handle-zoom-fill
         :on-zoom-fit  handle-zoom-fit
         :on-fullscreen toggle-fullscreen}]

       [:span.btn-icon-dark.btn-small.tooltip.tooltip-bottom-left
        {:alt (tr "viewer.header.fullscreen")
         :on-click toggle-fullscreen}
        (if fullscreen?
          i/full-screen-off
          i/full-screen)]

       (when (:is-admin permissions)
         [:span.btn-primary.tooltip.tooltip-bottom-left {:on-click open-share-dialog :alt (tr "labels.share-prototype")} i/export [:span (tr "labels.share-prototype")]])

       (when (:can-edit permissions)
         [:span.btn-text-dark {:on-click go-to-workspace} (tr "labels.edit-file")])

       (when-not (:is-logged permissions)
         [:span.btn-text-dark {:on-click open-login-dialog} (tr "labels.log-or-sign")])])))

(mf/defc header-sitemap
  [{:keys [project file page frame] :as props}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        project-name   (:name project)
        file-name      (:name file)
        page-name      (:name page)
        page-id        (:id page)
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

    (if new-css-system
      [:div {:class (stl/css :sitemap-zone)
             :title (tr "viewer.header.sitemap")}
       [:span {:class (stl/css :project-name)} project-name]
       [:div {:class (stl/css :sitemap-text)}
        [:div {:class (stl/css :breadcrumb)
               :on-click open-dropdown}
         [:span  {:class (stl/css :breadcrumb-text)}
          (dm/str file-name " / " page-name)]
         [:span {:class (stl/css :icon)} i/arrow-refactor]
         [:span "/"]
         [:& dropdown {:show @show-dropdown?
                       :on-close close-dropdown}
          [:ul {:class (stl/css :dropdown-sitemap)}
           (for [id (get-in file [:data :pages])]
             [:li {:class (stl/css-case :dropdown-element true
                                        :selected (= page-id id))
                   :id (str id)
                   :key (str id)
                   :on-click (partial navigate-to id)}
              [:span {:class (stl/css :label)}
               (get-in file [:data :pages-index id :name])]
              (when (= page-id id)
                [:span {:class (stl/css :icon-check)} i/tick-refactor])])]]]
        [:div {:class (stl/css :current-frame)
               :on-click toggle-thumbnails}
         [:span {:class (stl/css :frame-name)} frame-name]
         [:span {:class (stl/css :icon)} i/arrow-refactor]]]]


      ;; OLD
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
       [:span.icon {:on-click toggle-thumbnails} i/arrow-down]])))

(mf/defc header
  [{:keys [project file page frame zoom section permissions index interactions-mode]}]
  (let [new-css-system (mf/use-ctx ctx/new-css-system)
        go-to-dashboard
        (mf/use-fn
         #(st/emit! (dv/go-to-dashboard)))

        go-to-inspect
        (mf/use-fn
         (mf/deps permissions)
         (fn []
           (if (:is-logged permissions)
             (st/emit! dv/close-thumbnails-panel (dv/go-to-section :inspect))
             (open-login-dialog))))

        navigate
        (mf/use-fn
         (mf/deps permissions)
         (fn [event]
          (let [section (-> (dom/get-current-target event)
                            (dom/get-data "value")
                            (keyword))]

            (if (or (= section :interactions) (:is-logged permissions))
              (st/emit! (dv/go-to-section section))
              (open-login-dialog)))))]

    (if new-css-system
      [:header {:class (stl/css :viewer-header)}
       [:div {:class (stl/css :nav-zone)}
        ;; If the user doesn't have permission we disable the link
        [:a {:class (stl/css :home-link)
             :on-click go-to-dashboard
             :style {:cursor (when-not (:can-edit permissions) "auto")
                     :pointer-events (when-not (:can-edit permissions) "none")}}
         [:span {:class (stl/css :logo-icon)}
          i/logo-icon]]

        [:& header-sitemap {:project project
                            :file file
                            :page page
                            :frame frame
                            :index index}]]

       [:div {:class (stl/css :mode-zone)}
        [:button {:on-click navigate
                  :data-value :interactions
                  :class (stl/css-case :mode-zone-btn true
                                       :selected (= section :interactions))
                  :title (tr "viewer.header.interactions-section" (sc/get-tooltip :open-interactions))}
         i/play-refactor]

        (when (or (:can-edit permissions)
                  (= (:who-comment permissions) "all"))
          [:button {:on-click navigate
                    :data-value :comments
                    :class (stl/css-case :mode-zone-btn true
                                         :selected (= section :comments))
                    :title (tr "viewer.header.comments-section" (sc/get-tooltip :open-comments))}
           i/comments-refactor])

        (when (or (= (:type permissions) :membership)
                  (and (= (:type permissions) :share-link)
                       (= (:who-inspect permissions) "all")))
          [:button {:on-click go-to-inspect
                    :class (stl/css-case :mode-zone-btn true
                                         :selected (= section :inspect))
                    :title (tr "viewer.header.inspect-section" (sc/get-tooltip :open-inspect))}
           i/code-refactor])]

       [:& header-options {:section section
                           :permissions permissions
                           :page page
                           :file file
                           :index index
                           :zoom zoom
                           :interactions-mode interactions-mode}]]


      ;; OLD
      [:header.viewer-header
       [:div.nav-zone
            ;; If the user doesn't have permission we disable the link
        [:div.main-icon {:style {:cursor (when-not (:can-edit permissions) "auto")}}
         [:a {:on-click go-to-dashboard
              :style {:pointer-events (when-not (:can-edit permissions) "none")}} i/logo-icon]]

        [:& header-sitemap {:project project :file file :page page :frame frame :index index}]]

       [:div.mode-zone
        [:button.mode-zone-button.tooltip.tooltip-bottom
         {:on-click navigate
          :data-value :interactions
          :class (dom/classnames :active (= section :interactions))
          :alt (tr "viewer.header.interactions-section" (sc/get-tooltip :open-interactions))}
         i/play]

        (when (or (:can-edit permissions)
                  (= (:who-comment permissions) "all"))
          [:button.mode-zone-button.tooltip.tooltip-bottom
           {:on-click navigate
            :data-value :comments
            :class (dom/classnames :active (= section :comments))
            :alt (tr "viewer.header.comments-section" (sc/get-tooltip :open-comments))}
           i/chat])

        (when (or (= (:type permissions) :membership)
                  (and (= (:type permissions) :share-link)
                       (= (:who-inspect permissions) "all")))
          [:button.mode-zone-button.tooltip.tooltip-bottom
           {:on-click go-to-inspect
            :class (dom/classnames :active (= section :inspect))
            :alt (tr "viewer.header.inspect-section" (sc/get-tooltip :open-inspect))}
           i/code])]

       [:& header-options {:section section
                           :permissions permissions
                           :page page
                           :file file
                           :index index
                           :zoom zoom
                           :interactions-mode interactions-mode}]])))

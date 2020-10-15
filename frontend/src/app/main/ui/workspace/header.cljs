;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.header
  (:require
   [okulary.core :as l]
   [rumext.alpha :as mf]
   [app.main.ui.icons :as i :include-macros true]
   [app.config :as cfg]
   [app.main.data.history :as udh]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.data.modal :as modal]
   [app.main.ui.workspace.presence :as presence]
   [app.main.ui.keyboard :as kbd]
   [app.util.i18n :as i18n :refer [t]]
   [app.util.data :refer [classnames]]
   [app.util.dom :as dom]
   [app.common.math :as mth]
   [app.util.router :as rt]))

;; --- Zoom Widget

(def workspace-persistence-ref
  (l/derived :workspace-persistence st/state))

(mf/defc persistence-state-widget
  {::mf/wrap [mf/memo]}
  [{:keys [locale]}]
  (let [data (mf/deref workspace-persistence-ref)]
    [:div.persistence-status-widget
     (cond
       (= :pending (:status data))
       [:div.pending
        [:span.label (t locale "workspace.header.unsaved")]]

       (= :saving (:status data))
       [:div.saving
        [:span.icon i/toggle]
        [:span.label (t locale "workspace.header.saving")]]

       (= :saved (:status data))
       [:div.saved
        [:span.icon i/tick]
        [:span.label (t locale "workspace.header.saved")]]

       (= :error (:status data))
       [:div.error {:title "There was an error saving the data. Please refresh if this persists."}
        [:span.icon i/msg-warning]
        [:span.label (t locale "workspace.header.save-error")]])]))


(mf/defc zoom-widget
  {::mf/wrap [mf/memo]}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-reset
           on-zoom-fit
           on-zoom-selected]
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
       [:li {:on-click on-zoom-reset}
        "Zoom to 100%" [:span "Shift + 0"]]
       [:li {:on-click on-zoom-fit}
        "Zoom to fit all" [:span "Shift + 1"]]
       [:li {:on-click on-zoom-selected}
        "Zoom to selected" [:span "Shift + 2"]]]]]))

;; --- Header Users

(mf/defc menu
  [{:keys [layout project file team-id] :as props}]
  (let [show-menu? (mf/use-state false)
        editing? (mf/use-state false)

        locale     (mf/deref i18n/locale)

        edit-input-ref (mf/use-ref nil)

        add-shared-fn
        (st/emitf (dw/set-file-shared (:id file) true))

        del-shared-fn
        (st/emitf (dw/set-file-shared (:id file) false))

        on-add-shared
        (mf/use-fn
         (mf/deps file)
         (st/emitf (modal/show
                    {:type :confirm
                     :message ""
                     :title (t locale "modals.add-shared-confirm.message" (:name file))
                     :hint (t locale "modals.add-shared-confirm.hint")
                     :cancel-label :omit
                     :accept-label (t locale "modals.add-shared-confirm.accept")
                     :accept-style :primary
                     :on-accept add-shared-fn})))

        on-remove-shared
        (mf/use-fn
         (mf/deps file)
         (st/emitf (modal/show
                    {:type :confirm
                     :message ""
                     :title (t locale "modals.remove-shared-confirm.message" (:name file))
                     :hint (t locale "modals.remove-shared-confirm.hint")
                     :cancel-label :omit
                     :accept-label (t locale "modals.remove-shared-confirm.accept")
                     :on-accept del-shared-fn})))


        handle-blur (fn [event]
                      (let [value (-> edit-input-ref mf/ref-val dom/get-value)]
                        (st/emit! (dw/rename-file (:id file) value)))
                      (reset! editing? false))

        handle-name-keydown (fn [event]
                              (when (kbd/enter? event)
                                (handle-blur event)))
        start-editing-name (fn [event]
                             (dom/prevent-default event)
                             (reset! editing? true))]
    (mf/use-effect
     (mf/deps @editing?)
     #(when @editing?
        (dom/select-text! (mf/ref-val edit-input-ref))))

    [:div.menu-section
     [:div.btn-icon-dark.btn-small {:on-click #(reset! show-menu? true)} i/actions]
     [:div.project-tree {:alt (t locale "workspace.sitemap")}
      [:span.project-name
       {:on-click #(st/emit! (rt/navigate :dashboard-project {:team-id team-id
                                                              :project-id (:project-id file)}))}
       (:name project) " /"]
      (if @editing?
        [:input.file-name
         {:type "text"
          :ref edit-input-ref
          :on-blur handle-blur
          :on-key-down handle-name-keydown
          :auto-focus true
          :default-value (:name file "")}]
        [:span
         {:on-double-click start-editing-name}
         (:name file)])]
     (when (:is-shared file)
       [:div.shared-badge i/library])

     [:& dropdown {:show @show-menu?
                   :on-close #(reset! show-menu? false)}
      [:ul.menu
       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :rules))}
        [:span
         (if (contains? layout :rules)
           (t locale "workspace.header.menu.hide-rules")
           (t locale "workspace.header.menu.show-rules"))]
        [:span.shortcut "Ctrl+shift+R"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :display-grid))}
        [:span
         (if (contains? layout :display-grid)
           (t locale "workspace.header.menu.hide-grid")
           (t locale "workspace.header.menu.show-grid"))]
        [:span.shortcut "Ctrl+'"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :snap-grid))}
        [:span
         (if (contains? layout :snap-grid)
           (t locale "workspace.header.menu.disable-snap-grid")
           (t locale "workspace.header.menu.enable-snap-grid"))]
        [:span.shortcut "Ctrl+Shift+'"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :sitemap :layers))}
        [:span
         (if (or (contains? layout :sitemap) (contains? layout :layers))
           (t locale "workspace.header.menu.hide-layers")
           (t locale "workspace.header.menu.show-layers"))]
        [:span.shortcut "Ctrl+l"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :colorpalette))}
        [:span
         (if (contains? layout :colorpalette)
           (t locale "workspace.header.menu.hide-palette")
           (t locale "workspace.header.menu.show-palette"))]
        [:span.shortcut "Ctrl+p"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :assets))}
        [:span
         (if (contains? layout :assets)
           (t locale "workspace.header.menu.hide-assets")
           (t locale "workspace.header.menu.show-assets"))]
        [:span.shortcut "Ctrl+i"]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :dynamic-alignment))}
        [:span
         (if (contains? layout :dynamic-alignment)
           (t locale "workspace.header.menu.disable-dynamic-alignment")
           (t locale "workspace.header.menu.enable-dynamic-alignment"))]
        [:span.shortcut "Ctrl+a"]]

       (if (:is-shared file)
         [:li {:on-click on-remove-shared}
          [:span (t locale "dashboard.remove-shared")]]
         [:li {:on-click on-add-shared}
          [:span (t locale "dashboard.add-shared")]])
       ]]]))

;; --- Header Component

(mf/defc header
  [{:keys [file layout project page-id] :as props}]
  (let [team-id (:team-id project)
        go-back #(st/emit! (rt/nav :dashboard-projects {:team-id team-id}))
        zoom (mf/deref refs/selected-zoom)
        locale (mf/deref i18n/locale)
        router (mf/deref refs/router)
        view-url (rt/resolve router :viewer {:page-id page-id :file-id (:id file)} {:index 0})]
    [:header.workspace-header
     [:div.main-icon
      [:a {:on-click go-back} i/logo-icon]]

     [:& menu {:layout layout
               :project project
               :file file
               :team-id team-id}]

     [:div.users-section
      [:& presence/active-sessions]]

     [:div.options-section
      [:& persistence-state-widget
       {:locale locale}]

      [:& zoom-widget
       {:zoom zoom
        :on-increase #(st/emit! (dw/increase-zoom nil))
        :on-decrease #(st/emit! (dw/decrease-zoom nil))
        :on-zoom-reset #(st/emit! dw/reset-zoom)
        :on-zoom-fit #(st/emit! dw/zoom-to-fit-all)
        :on-zoom-selected #(st/emit! dw/zoom-to-selected-shape)}]

      [:a.btn-icon-dark.btn-small
       {;; :target "__blank"
        :alt (t locale "workspace.header.viewer")
        :href (str "#" view-url)} i/play]]]))


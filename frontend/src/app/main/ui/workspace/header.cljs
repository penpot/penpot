;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.header
  (:require
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.exports :as de]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.export :refer [export-progress-widget]]
   [app.main.ui.formats :as fmt]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.presence :refer [active-sessions]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(def workspace-persistence-ref
  (l/derived :workspace-persistence st/state))

;; --- Persistence state Widget

(mf/defc persistence-state-widget
  {::mf/wrap [mf/memo]}
  []
  (let [data (mf/deref workspace-persistence-ref)]
    [:div.persistence-status-widget
     (cond
       (= :pending (:status data))
       [:div.pending
        [:span.label (tr "workspace.header.unsaved")]]

       (= :saving (:status data))
       [:div.saving
        [:span.icon i/toggle]
        [:span.label (tr "workspace.header.saving")]]

       (= :saved (:status data))
       [:div.saved
        [:span.icon i/tick]
        [:span.label (tr "workspace.header.saved")]]

       (= :error (:status data))
       [:div.error {:title "There was an error saving the data. Please refresh if this persists."}
        [:span.icon i/msg-warning]
        [:span.label (tr "workspace.header.save-error")]])]))

;; --- Zoom Widget

(mf/defc zoom-widget-workspace
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
     [:span.label (fmt/format-percent zoom {:precision 0})]
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
         [:p.zoom-size {} (fmt/format-percent zoom {:precision 0})]
         [:button {:on-click (fn [event]
                               (dom/stop-propagation event)
                               (dom/prevent-default event)
                               (on-increase))} "+"]]
        [:button.reset-btn {:on-click on-zoom-reset} (tr "workspace.header.reset-zoom")]]
       [:li.separator]
       [:li {:on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit-all") [:span (sc/get-tooltip :fit-all)]]
       [:li {:on-click on-zoom-selected}
        (tr "workspace.header.zoom-selected") [:span (sc/get-tooltip :zoom-selected)]]]]]))



;; --- Header Users

(mf/defc menu
  [{:keys [layout project file team-id] :as props}]
  (let [show-menu?     (mf/use-state false)
        show-sub-menu? (mf/use-state false)
        editing?       (mf/use-state false)
        edit-input-ref (mf/use-ref nil)
        frames         (mf/deref refs/workspace-frames)

        add-shared-fn
        #(st/emit! (dwl/set-file-shared (:id file) true))

        del-shared-fn
        #(st/emit! (dwl/set-file-shared (:id file) false))

        on-add-shared
        (mf/use-fn
         (mf/deps file)
         #(st/emit! (modal/show
                     {:type :confirm
                      :message ""
                      :title (tr "modals.add-shared-confirm.message" (:name file))
                      :hint (tr "modals.add-shared-confirm.hint")
                      :cancel-label :omit
                      :accept-label (tr "modals.add-shared-confirm.accept")
                      :accept-style :primary
                      :on-accept add-shared-fn})))

        on-remove-shared
        (mf/use-fn
         (mf/deps file)
         #(st/emit! (modal/show
                     {:type :confirm
                      :message ""
                      :title (tr "modals.remove-shared-confirm.message" (:name file))
                      :hint (tr "modals.remove-shared-confirm.hint")
                      :cancel-label :omit
                      :accept-label (tr "modals.remove-shared-confirm.accept")
                      :on-accept del-shared-fn})))

        handle-blur (fn [_]
                      (let [value (-> edit-input-ref mf/ref-val dom/get-value)]
                        (st/emit! (dw/rename-file (:id file) value)))
                      (reset! editing? false))

        handle-name-keydown (fn [event]
                              (when (kbd/enter? event)
                                (handle-blur event)))
        start-editing-name (fn [event]
                             (dom/prevent-default event)
                             (reset! editing? true))

        on-export-shapes
        (mf/use-callback
         (fn [_]
           (st/emit! (de/show-workspace-export-dialog))))

        on-export-file
        (fn [event-name binary?]
          (st/emit! (ptk/event ::ev/event {::ev/name event-name
                                           ::ev/origin "workspace"
                                           :num-files 1}))

          (->> (rx/of file)
               (rx/flat-map
                (fn [file]
                  (->> (rp/command :has-file-libraries {:file-id (:id file)})
                       (rx/map #(assoc file :has-libraries? %)))))
               (rx/reduce conj [])
               (rx/subs
                (fn [files]
                  (st/emit!
                   (modal/show
                    {:type :export
                     :team-id team-id
                     :has-libraries? (->> files (some :has-libraries?))
                     :files files
                     :binary? binary?}))))))

        on-export-binary-file
        (mf/use-callback
         (mf/deps file team-id)
         (fn [_]
           (on-export-file "export-binary-files" true)))

        on-export-standard-file
        (mf/use-callback
         (mf/deps file team-id)
         (fn [_]
           (on-export-file "export-standard-files" false)))

        on-export-frames
        (mf/use-callback
         (mf/deps file frames)
         (fn [_]
           (st/emit! (de/show-workspace-export-frames-dialog (reverse frames)))))

        on-item-hover
        (mf/use-callback
         (fn [item]
           (fn [event]
             (dom/stop-propagation event)
             (reset! show-sub-menu? item))))

        on-item-click
        (mf/use-callback
         (fn [item]
           (fn [event]
             (dom/stop-propagation event)
             (reset! show-sub-menu? item))))

        toggle-flag
        (mf/use-callback
         (fn [flag]
           (-> (dw/toggle-layout-flag flag)
               (vary-meta assoc ::ev/origin "workspace-menu"))))

        show-release-notes
        (mf/use-callback
         (fn [event]
           (let [version (:main @cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))]

    (mf/use-effect
     (mf/deps @editing?)
     #(when @editing?
        (dom/select-text! (mf/ref-val edit-input-ref))))

    [:div.menu-section
     [:div.btn-icon-dark.btn-small {:on-click #(reset! show-menu? true)} i/actions]
     [:div.project-tree {:alt (tr "workspace.sitemap")}
      [:span.project-name
       {:on-click #(st/emit! (rt/navigate :dashboard-files {:team-id team-id
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
       [:li {:on-click (on-item-click :file)
             :on-pointer-enter (on-item-hover :file)}
        [:span (tr "workspace.header.menu.option.file")]
        [:span i/arrow-slide]]
       [:li {:on-click (on-item-click :edit)
             :on-pointer-enter (on-item-hover :edit)}
        [:span (tr "workspace.header.menu.option.edit")] [:span i/arrow-slide]]
       [:li {:on-click (on-item-click :view)
             :on-pointer-enter (on-item-hover :view)}
        [:span (tr "workspace.header.menu.option.view")] [:span i/arrow-slide]]
       [:li {:on-click (on-item-click :preferences)
             :on-pointer-enter (on-item-hover :preferences)}
        [:span (tr "workspace.header.menu.option.preferences")] [:span i/arrow-slide]]
       [:li.info {:on-click (on-item-click :help-info)
                  :on-pointer-enter (on-item-hover :help-info)}
        [:span (tr "workspace.header.menu.option.help-info")] [:span i/arrow-slide]]]]

     [:& dropdown {:show (= @show-sub-menu? :file)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.file
       (if (:is-shared file)
         [:li {:on-click on-remove-shared}
          [:span (tr "dashboard.remove-shared")]]
         [:li {:on-click on-add-shared}
          [:span (tr "dashboard.add-shared")]])
       [:li.export-file {:on-click on-export-shapes}
        [:span (tr "dashboard.export-shapes")]
        [:span.shortcut (sc/get-tooltip :export-shapes)]]
       [:li.separator.export-file {:on-click on-export-binary-file}
        [:span (tr "dashboard.download-binary-file")]]
       [:li.export-file {:on-click on-export-standard-file}
        [:span (tr "dashboard.download-standard-file")]]
       (when (seq frames)
         [:li.separator.export-file {:on-click on-export-frames}
          [:span (tr "dashboard.export-frames")]])]]

     [:& dropdown {:show (= @show-sub-menu? :edit)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.edit
       [:li {:on-click #(st/emit! (dw/select-all))}
        [:span (tr "workspace.header.menu.select-all")]
        [:span.shortcut (sc/get-tooltip :select-all)]]
       [:li {:on-click #(st/emit! (toggle-flag :scale-text))}
        [:span
         (if (contains? layout :scale-text)
           (tr "workspace.header.menu.disable-scale-text")
           (tr "workspace.header.menu.enable-scale-text"))]
        [:span.shortcut (sc/get-tooltip :toggle-scale-text)]]]]

     [:& dropdown {:show (= @show-sub-menu? :view)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.view
       [:li {:on-click #(st/emit! (toggle-flag :rules))}
        [:span
         (if (contains? layout :rules)
           (tr "workspace.header.menu.hide-rules")
           (tr "workspace.header.menu.show-rules"))]
        [:span.shortcut (sc/get-tooltip :toggle-rules)]]

       [:li {:on-click #(st/emit! (toggle-flag :display-grid))}
        [:span
         (if (contains? layout :display-grid)
           (tr "workspace.header.menu.hide-grid")
           (tr "workspace.header.menu.show-grid"))]
        [:span.shortcut (sc/get-tooltip :toggle-grid)]]

       [:li {:on-click (fn []
                         (r/set-resize-type! :bottom)
                         (st/emit! (dw/remove-layout-flag :textpalette)
                                   (toggle-flag :colorpalette)))}
        [:span
         (if (contains? layout :colorpalette)
           (tr "workspace.header.menu.hide-palette")
           (tr "workspace.header.menu.show-palette"))]
        [:span.shortcut (sc/get-tooltip :toggle-colorpalette)]]

       [:li {:on-click (fn []
                         (r/set-resize-type! :bottom)
                         (st/emit! (dw/remove-layout-flag :colorpalette)
                                   (toggle-flag :textpalette)))}
        [:span
         (if (contains? layout :textpalette)
           (tr "workspace.header.menu.hide-textpalette")
           (tr "workspace.header.menu.show-textpalette"))]
        [:span.shortcut (sc/get-tooltip :toggle-textpalette)]]

       [:li {:on-click #(st/emit! (toggle-flag :display-artboard-names))}
        [:span
         (if (contains? layout :display-artboard-names)
           (tr "workspace.header.menu.hide-artboard-names")
           (tr "workspace.header.menu.show-artboard-names"))]]

       [:li {:on-click #(st/emit! (toggle-flag :show-pixel-grid))}
        [:span
         (if (contains? layout :show-pixel-grid)
           (tr "workspace.header.menu.hide-pixel-grid")
           (tr "workspace.header.menu.show-pixel-grid"))]
        [:span.shortcut (sc/get-tooltip :show-pixel-grid)]]

       [:li {:on-click #(st/emit! (-> (toggle-flag :hide-ui)
                                      (vary-meta assoc ::ev/origin "workspace-menu")))}
        [:span
         (tr "workspace.shape.menu.hide-ui")]
        [:span.shortcut (sc/get-tooltip :hide-ui)]]]]

     [:& dropdown {:show (= @show-sub-menu? :preferences)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.preferences
       [:li {:on-click #(st/emit! (toggle-flag :snap-guides))}
        [:span
         (if (contains? layout :snap-guides)
           (tr "workspace.header.menu.disable-snap-guides")
           (tr "workspace.header.menu.enable-snap-guides"))]
        [:span.shortcut (sc/get-tooltip :toggle-snap-guide)]]

       [:li {:on-click #(st/emit! (toggle-flag :snap-grid))}
        [:span
         (if (contains? layout :snap-grid)
           (tr "workspace.header.menu.disable-snap-grid")
           (tr "workspace.header.menu.enable-snap-grid"))]
        [:span.shortcut (sc/get-tooltip :toggle-snap-grid)]]

       [:li {:on-click #(st/emit! (toggle-flag :dynamic-alignment))}
        [:span
         (if (contains? layout :dynamic-alignment)
           (tr "workspace.header.menu.disable-dynamic-alignment")
           (tr "workspace.header.menu.enable-dynamic-alignment"))]
        [:span.shortcut (sc/get-tooltip :toggle-alignment)]]

       [:li {:on-click #(st/emit! (toggle-flag :snap-pixel-grid))}
        [:span
         (if (contains? layout :snap-pixel-grid)
           (tr "workspace.header.menu.disable-snap-pixel-grid")
           (tr "workspace.header.menu.enable-snap-pixel-grid"))]
        [:span.shortcut (sc/get-tooltip :snap-pixel-grid)]]

       [:li {:on-click #(st/emit! (modal/show {:type :nudge-option}))}
        [:span (tr "modals.nudge-title")]]]]

     [:& dropdown {:show (= @show-sub-menu? :help-info)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.help-info
       [:li {:on-click #(dom/open-new-window "https://help.penpot.app")}
        [:span (tr "labels.help-center")]]
       [:li {:on-click #(dom/open-new-window "https://community.penpot.app")}
        [:span (tr "labels.community")]]
       [:li {:on-click #(dom/open-new-window "https://www.youtube.com/c/Penpot")}
        [:span (tr "labels.tutorials")]]
       [:li {:on-click show-release-notes}
        [:span (tr "labels.release-notes")]]
       [:li.separator {:on-click #(dom/open-new-window "https://penpot.app/libraries-templates.html")}
        [:span (tr "labels.libraries-and-templates")]]
       [:li {:on-click #(dom/open-new-window "https://github.com/penpot/penpot")}
        [:span (tr "labels.github-repo")]]
       [:li  {:on-click #(dom/open-new-window "https://penpot.app/terms.html")}
        [:span (tr "auth.terms-of-service")]]
       [:li.separator {:on-click #(st/emit! (when (contains? layout :collapse-left-sidebar) (dw/toggle-layout-flag :collapse-left-sidebar))
                                            (-> (dw/toggle-layout-flag :shortcuts)
                                                (vary-meta assoc ::ev/origin "workspace-header")))}
        [:span (tr "label.shortcuts")]
        [:span.shortcut (sc/get-tooltip :show-shortcuts)]]

       (when (contains? @cf/flags :user-feedback)
         [:*
          [:li.feedback {:on-click #(st/emit! (rt/nav-new-window* {:rname :settings-feedback}))}
           [:span (tr "labels.give-feedback")]]])]]]))

;; --- Header Component

(mf/defc header
  [{:keys [file layout project page-id] :as props}]
  (let [team-id             (:team-id project)
        zoom                (mf/deref refs/selected-zoom)
        params              {:page-id page-id :file-id (:id file) :section "interactions"}

        go-back
        (mf/use-callback
         (mf/deps project)
         #(st/emit! (dw/go-to-dashboard project)))

        go-viewer
        (mf/use-callback
         (mf/deps file page-id)
         #(st/emit! (dw/go-to-viewer params)))]

    [:header.workspace-header
     [:div.left-area
      [:div.main-icon
       [:a {:on-click go-back} i/logo-icon]]

      [:& menu {:layout layout
                :project project
                :file file
                :team-id team-id
                :page-id page-id}]]

     [:div.center-area
      [:div.users-section
       [:& active-sessions]]]

     [:div.right-area
      [:div.options-section
       [:& persistence-state-widget]
       [:& export-progress-widget]
       [:button.document-history
        {:alt (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
         :class (when (contains? layout :document-history) "selected")
         :on-click #(st/emit! (-> (dw/toggle-layout-flag :document-history)
                                  (vary-meta assoc ::ev/origin "workspace-header")))}
        i/recent]]

      [:div.options-section
       [:& zoom-widget-workspace
        {:zoom zoom
         :on-increase #(st/emit! (dw/increase-zoom nil))
         :on-decrease #(st/emit! (dw/decrease-zoom nil))
         :on-zoom-reset #(st/emit! dw/reset-zoom)
         :on-zoom-fit #(st/emit! dw/zoom-to-fit-all)
         :on-zoom-selected #(st/emit! dw/zoom-to-selected-shape)}]

       [:a.btn-icon-dark.btn-small.tooltip.tooltip-bottom-left
        {:alt (tr "workspace.header.viewer" (sc/get-tooltip :open-viewer))
         :on-click go-viewer}
        i/play]]]]))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.header
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.config :as cf]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
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
   [rumext.alpha :as mf]))

;; --- Zoom Widget

(def workspace-persistence-ref
  (l/derived :workspace-persistence st/state))

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
     [:span.label {} (str (mth/round (* 100 zoom)) "%")]
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
         [:p.zoom-size {} (str (mth/round (* 100 zoom)) "%")]
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
  [{:keys [layout project file team-id page-id] :as props}]
  (let [show-menu?     (mf/use-state false)
        show-sub-menu? (mf/use-state false)
        editing?       (mf/use-state false)
        edit-input-ref (mf/use-ref nil)
        frames         (mf/deref refs/workspace-frames)

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
                     :title (tr "modals.add-shared-confirm.message" (:name file))
                     :hint (tr "modals.add-shared-confirm.hint")
                     :cancel-label :omit
                     :accept-label (tr "modals.add-shared-confirm.accept")
                     :accept-style :primary
                     :on-accept add-shared-fn})))

        on-remove-shared
        (mf/use-fn
         (mf/deps file)
         (st/emitf (modal/show
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

        on-export-file
        (mf/use-callback
         (mf/deps file team-id)
         (fn [_]
           (st/emit! (ptk/event ::ev/event {::ev/name "export-files"
                                            ::ev/origin "workspace"
                                            :num-files 1}))

           (->> (rx/of file)
                (rx/flat-map
                 (fn [file]
                   (->> (rp/query :file-libraries {:file-id (:id file)})
                        (rx/map #(assoc file :has-libraries? (d/not-empty? %))))))
                (rx/reduce conj [])
                (rx/subs
                 (fn [files]
                   (st/emit!
                    (modal/show
                     {:type :export
                      :team-id team-id
                      :has-libraries? (->> files (some :has-libraries?))
                      :files files})))))))

        on-export-frames
        (mf/use-callback
         (mf/deps file frames)
         (fn [_]
           (when (seq frames)
             (let [filename  (str (:name file) ".pdf")
                   frame-ids (mapv :id frames)]
               (st/emit! (dm/info (tr "workspace.options.exporting-object")
                                  {:timeout nil}))
               (->> (rp/query! :export-frames
                               {:name     (:name file)
                                :file-id  (:id file)
                                :page-id   page-id
                                :frame-ids frame-ids})
                    (rx/subs
                     (fn [body]
                       (dom/trigger-download filename body))
                     (fn [_error]
                       (st/emit! (dm/error (tr "errors.unexpected-error"))))
                     (st/emitf dm/hide)))))))

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
             (reset! show-sub-menu? item))))]

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
       (when (contains? @cf/flags :user-feedback)
         [:*
          [:li.feedback {:on-click (st/emitf (rt/nav :settings-feedback))
                         :on-pointer-enter (st/emitf (rt/nav :settings-feedback))}
           [:span (tr "labels.give-feedback")]]])]]

     [:& dropdown {:show (= @show-sub-menu? :file)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.file
       (if (:is-shared file)
         [:li {:on-click on-remove-shared}
          [:span (tr "dashboard.remove-shared")]]
         [:li {:on-click on-add-shared}
          [:span (tr "dashboard.add-shared")]])
       [:li.export-file {:on-click on-export-file}
        [:span (tr "dashboard.export-single")]]
       (when (seq frames)
         [:li.export-file {:on-click on-export-frames}
          [:span (tr "dashboard.export-frames")]])]]

     [:& dropdown {:show (= @show-sub-menu? :edit)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.edit
       [:li {:on-click #(st/emit! (dw/select-all))}
        [:span (tr "workspace.header.menu.select-all")]
        [:span.shortcut (sc/get-tooltip :select-all)]]
       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :scale-text))}
        [:span
         (if (contains? layout :scale-text)
           (tr "workspace.header.menu.disable-scale-text")
           (tr "workspace.header.menu.enable-scale-text"))]
        [:span.shortcut (sc/get-tooltip :toggle-scale-text)]]]]

     [:& dropdown {:show (= @show-sub-menu? :view)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.view
       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :rules))}
        [:span
         (if (contains? layout :rules)
           (tr "workspace.header.menu.hide-rules")
           (tr "workspace.header.menu.show-rules"))]
        [:span.shortcut (sc/get-tooltip :toggle-rules)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :display-grid))}
        [:span
         (if (contains? layout :display-grid)
           (tr "workspace.header.menu.hide-grid")
           (tr "workspace.header.menu.show-grid"))]
        [:span.shortcut (sc/get-tooltip :toggle-grid)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :sitemap :layers))}
        [:span
         (if (or (contains? layout :sitemap) (contains? layout :layers))
           (tr "workspace.header.menu.hide-layers")
           (tr "workspace.header.menu.show-layers"))]
        [:span.shortcut (sc/get-tooltip :toggle-layers)]]

       [:li {:on-click (fn []
                         (r/set-resize-type! :bottom)
                         (st/emit! (dw/remove-layout-flags :textpalette)
                                   (dw/toggle-layout-flags :colorpalette)))}
        [:span
         (if (contains? layout :colorpalette)
           (tr "workspace.header.menu.hide-palette")
           (tr "workspace.header.menu.show-palette"))]
        [:span.shortcut (sc/get-tooltip :toggle-colorpalette)]]

       [:li {:on-click (fn []
                         (r/set-resize-type! :bottom)
                         (st/emit! (dw/remove-layout-flags :colorpalette)
                                   (dw/toggle-layout-flags :textpalette)))}
        [:span
         (if (contains? layout :textpalette)
           (tr "workspace.header.menu.hide-textpalette")
           (tr "workspace.header.menu.show-textpalette"))]
        [:span.shortcut (sc/get-tooltip :toggle-textpalette)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :assets))}
        [:span
         (if (contains? layout :assets)
           (tr "workspace.header.menu.hide-assets")
           (tr "workspace.header.menu.show-assets"))]
        [:span.shortcut (sc/get-tooltip :toggle-assets)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :display-artboard-names))}
        [:span
         (if (contains? layout :display-artboard-names)
           (tr "workspace.header.menu.hide-artboard-names")
           (tr "workspace.header.menu.show-artboard-names"))]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :hide-ui))}
        [:span
         (tr "workspace.shape.menu.hide-ui")]
        [:span.shortcut (sc/get-tooltip :hide-ui)]]]]

     [:& dropdown {:show (= @show-sub-menu? :preferences)
                   :on-close #(reset! show-sub-menu? false)}
      [:ul.sub-menu.preferences
       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :snap-guides))}
        [:span
         (if (contains? layout :snap-guides)
           (tr "workspace.header.menu.disable-snap-guides")
           (tr "workspace.header.menu.enable-snap-guides"))]
        [:span.shortcut (sc/get-tooltip :toggle-snap-guide)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :snap-grid))}
        [:span
         (if (contains? layout :snap-grid)
           (tr "workspace.header.menu.disable-snap-grid")
           (tr "workspace.header.menu.enable-snap-grid"))]
        [:span.shortcut (sc/get-tooltip :toggle-snap-grid)]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flags :dynamic-alignment))}
        [:span
         (if (contains? layout :dynamic-alignment)
           (tr "workspace.header.menu.disable-dynamic-alignment")
           (tr "workspace.header.menu.enable-dynamic-alignment"))]
        [:span.shortcut (sc/get-tooltip :toggle-alignment)]]

       [:li {:on-click #(st/emit! (modal/show {:type :nudge-option}))}
        [:span (tr "modals.nudge-title")]]]]]))

;; --- Header Component

(mf/defc header
  [{:keys [file layout project page-id] :as props}]
  (let [team-id  (:team-id project)
        zoom     (mf/deref refs/selected-zoom)
        params   {:page-id page-id :file-id (:id file) :section "interactions"}

        go-back
        (mf/use-callback
         (mf/deps project)
         (st/emitf (dw/go-to-dashboard project)))

        go-viewer
        (mf/use-callback
         (mf/deps file page-id)
         (st/emitf (dw/go-to-viewer params)))]

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
       [:button.document-history
        {:alt (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
         :class (when (contains? layout :document-history) "selected")
         :on-click (st/emitf (dw/toggle-layout-flags :document-history))}
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


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.header
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :refer [show-shared-dialog]]
   [app.main.data.events :as ev]
   [app.main.data.exports :as de]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
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
   [cuerdas.core :as str]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(def ref:workspace-persistence
  (l/derived :workspace-persistence st/state))

;; --- Persistence state Widget

(mf/defc persistence-state-widget
  {::mf/wrap [mf/memo]}
  []
  (let [{:keys [status]} (mf/deref ref:workspace-persistence)]
    [:div.persistence-status-widget
     (case status
       :pending
       [:div.pending
        [:span.label (tr "workspace.header.unsaved")]]

       :saving
       [:div.saving
        [:span.icon i/toggle]
        [:span.label (tr "workspace.header.saving")]]

       :saved
       [:div.saved
        [:span.icon i/tick]
        [:span.label (tr "workspace.header.saved")]]

       :error
       [:div.error {:title "There was an error saving the data. Please refresh if this persists."}
        [:span.icon i/msg-warning]
        [:span.label (tr "workspace.header.save-error")]]

       nil)]))

;; --- Zoom Widget

(mf/defc zoom-widget-workspace
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [{:keys [zoom on-increase on-decrease on-zoom-reset on-zoom-fit on-zoom-selected]}]
  (let [open* (mf/use-state false)
        open? (deref open*)

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

        zoom (fmt/format-percent zoom {:precision 0})]

    [:div.zoom-widget {:on-click open-dropdown}
     [:span.label zoom]
     [:span.icon i/arrow-down]
     [:& dropdown {:show open? :on-close close-dropdown}
      [:ul.dropdown
       [:li.basic-zoom-bar
        [:span.zoom-btns
         [:button {:on-click on-decrease} "-"]
         [:p.zoom-size zoom]
         [:button {:on-click on-increase} "+"]]
        [:button.reset-btn {:on-click on-zoom-reset} (tr "workspace.header.reset-zoom")]]
       [:li.separator]
       [:li {:on-click on-zoom-fit}
        (tr "workspace.header.zoom-fit-all") [:span (sc/get-tooltip :fit-all)]]
       [:li {:on-click on-zoom-selected}
        (tr "workspace.header.zoom-selected") [:span (sc/get-tooltip :zoom-selected)]]]]]))

;; --- Header Users

(mf/defc help-info-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [layout on-close]}]
  (let [nav-to-helpc-center
        (mf/use-fn #(dom/open-new-window "https://help.penpot.app"))

        nav-to-community
        (mf/use-fn #(dom/open-new-window "https://community.penpot.app"))

        nav-to-youtube
        (mf/use-fn #(dom/open-new-window "https://www.youtube.com/c/Penpot"))

        nav-to-templates
        (mf/use-fn #(dom/open-new-window "https://penpot.app/libraries-templates"))

        nav-to-github
        (mf/use-fn #(dom/open-new-window "https://github.com/penpot/penpot"))

        nav-to-terms
        (mf/use-fn #(dom/open-new-window "https://penpot.app/terms"))

        nav-to-feedback
        (mf/use-fn #(st/emit! (rt/nav-new-window* {:rname :settings-feedback})))

        show-shortcuts
        (mf/use-fn
         (mf/deps layout)
         (fn []
           (when (contains? layout :collapse-left-sidebar)
             (st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))

           (st/emit!
            (-> (dw/toggle-layout-flag :shortcuts)
                (vary-meta assoc ::ev/origin "workspace-header")))))

        show-release-notes
        (mf/use-fn
         (fn [event]
           (let [version (:main cf/version)]
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes" :version version}))
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes :version version}))))))]

    [:& dropdown {:show true :on-close on-close}
     [:ul.sub-menu.help-info
      [:li {:on-click nav-to-helpc-center}
       [:span (tr "labels.help-center")]]
      [:li {:on-click nav-to-community}
       [:span (tr "labels.community")]]
      [:li {:on-click nav-to-youtube}
       [:span (tr "labels.tutorials")]]
      [:li {:on-click show-release-notes}
       [:span (tr "labels.release-notes")]]
      [:li.separator {:on-click nav-to-templates}
       [:span (tr "labels.libraries-and-templates")]]
      [:li {:on-click nav-to-github}
       [:span (tr "labels.github-repo")]]
      [:li  {:on-click nav-to-terms}
       [:span (tr "auth.terms-of-service")]]
      [:li.separator {:on-click show-shortcuts}
       [:span (tr "label.shortcuts")]
       [:span.shortcut (sc/get-tooltip :show-shortcuts)]]

      (when (contains? cf/flags :user-feedback)
        [:*
         [:li.feedback {:on-click nav-to-feedback}
          [:span (tr "labels.give-feedback")]]])]]))

(mf/defc preferences-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [layout toggle-flag on-close]}]
  (let [show-nudge-options (mf/use-fn #(modal/show! {:type :nudge-option}))]

    [:& dropdown {:show true :on-close on-close}
     [:ul.sub-menu.preferences
      [:li {:on-click toggle-flag
            :data-flag "scale-text"}
       [:span
        (if (contains? layout :scale-text)
          (tr "workspace.header.menu.disable-scale-content")
          (tr "workspace.header.menu.enable-scale-content"))]
       [:span.shortcut (sc/get-tooltip :toggle-scale-text)]]

      [:li {:on-click toggle-flag
            :data-flag "snap-guides"}
       [:span
        (if (contains? layout :snap-guides)
          (tr "workspace.header.menu.disable-snap-guides")
          (tr "workspace.header.menu.enable-snap-guides"))]
       [:span.shortcut (sc/get-tooltip :toggle-snap-guide)]]

      [:li {:on-click toggle-flag
            :data-flag "snap-grid"}
       [:span
        (if (contains? layout :snap-grid)
          (tr "workspace.header.menu.disable-snap-grid")
          (tr "workspace.header.menu.enable-snap-grid"))]
       [:span.shortcut (sc/get-tooltip :toggle-snap-grid)]]

      [:li {:on-click toggle-flag
            :data-flag "dynamic-alignment"}
       [:span
        (if (contains? layout :dynamic-alignment)
          (tr "workspace.header.menu.disable-dynamic-alignment")
          (tr "workspace.header.menu.enable-dynamic-alignment"))]
       [:span.shortcut (sc/get-tooltip :toggle-alignment)]]

      [:li {:on-click toggle-flag
            :data-flag "snap-pixel-grid"}
       [:span
        (if (contains? layout :snap-pixel-grid)
          (tr "workspace.header.menu.disable-snap-pixel-grid")
          (tr "workspace.header.menu.enable-snap-pixel-grid"))]
       [:span.shortcut (sc/get-tooltip :snap-pixel-grid)]]

      [:li {:on-click show-nudge-options}
       [:span (tr "modals.nudge-title")]]]]))

(mf/defc view-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [layout toggle-flag on-close]}]
  (let [read-only?   (mf/use-ctx ctx/workspace-read-only?)

        toggle-color-palette
        (mf/use-fn
         (fn []
           (r/set-resize-type! :bottom)
           (st/emit! (dw/remove-layout-flag :textpalette)
                     (-> (dw/toggle-layout-flag :colorpalette)
                         (vary-meta assoc ::ev/origin "workspace-menu")))))

        toggle-text-palette
        (mf/use-fn
         (fn []
           (r/set-resize-type! :bottom)
           (st/emit! (dw/remove-layout-flag :colorpalette)
                     (-> (dw/toggle-layout-flag :textpalette)
                         (vary-meta assoc ::ev/origin "workspace-menu")))))]

    [:& dropdown {:show true :on-close on-close}
     [:ul.sub-menu.view
      [:li {:on-click toggle-flag
            :data-flag "rules"}
       [:span
        (if (contains? layout :rules)
          (tr "workspace.header.menu.hide-rules")
          (tr "workspace.header.menu.show-rules"))]
       [:span.shortcut (sc/get-tooltip :toggle-rules)]]

      [:li {:on-click toggle-flag
            :data-flag "display-grid"}
       [:span
        (if (contains? layout :display-grid)
          (tr "workspace.header.menu.hide-grid")
          (tr "workspace.header.menu.show-grid"))]
       [:span.shortcut (sc/get-tooltip :toggle-grid)]]

      (when-not ^boolean read-only?
        [:*
         [:li {:on-click toggle-color-palette}
          [:span
           (if (contains? layout :colorpalette)
             (tr "workspace.header.menu.hide-palette")
             (tr "workspace.header.menu.show-palette"))]
          [:span.shortcut (sc/get-tooltip :toggle-colorpalette)]]

         [:li {:on-click toggle-text-palette}
          [:span
           (if (contains? layout :textpalette)
             (tr "workspace.header.menu.hide-textpalette")
             (tr "workspace.header.menu.show-textpalette"))]
          [:span.shortcut (sc/get-tooltip :toggle-textpalette)]]])

      [:li {:on-click toggle-flag
            :data-flag "display-artboard-names"}
       [:span
        (if (contains? layout :display-artboard-names)
          (tr "workspace.header.menu.hide-artboard-names")
          (tr "workspace.header.menu.show-artboard-names"))]]

      [:li {:on-click toggle-flag
            :data-flag "show-pixel-grid"}
       [:span
        (if (contains? layout :show-pixel-grid)
          (tr "workspace.header.menu.hide-pixel-grid")
          (tr "workspace.header.menu.show-pixel-grid"))]
       [:span.shortcut (sc/get-tooltip :show-pixel-grid)]]

      [:li {:on-click toggle-flag
            :data-flag "hide-ui"}
       [:span
        (tr "workspace.shape.menu.hide-ui")]
       [:span.shortcut (sc/get-tooltip :hide-ui)]]]]))

(mf/defc edit-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [on-close]}]
  (let [select-all (mf/use-fn #(st/emit! (dw/select-all)))
        undo       (mf/use-fn #(st/emit! dwc/undo))
        redo       (mf/use-fn #(st/emit! dwc/redo))]
    [:& dropdown {:show true :on-close on-close}
     [:ul.sub-menu.edit
      [:li {:on-click select-all}
       [:span (tr "workspace.header.menu.select-all")]
       [:span.shortcut (sc/get-tooltip :select-all)]]

      [:li {:on-click undo}
       [:span (tr "workspace.header.menu.undo")]
       [:span.shortcut (sc/get-tooltip :undo)]]

      [:li {:on-click redo}
       [:span (tr "workspace.header.menu.redo")]
       [:span.shortcut (sc/get-tooltip :redo)]]]]))

(mf/defc file-menu
  {::mf/wrap-props false}
  [{:keys [on-close file team-id]}]
  (let [file-id   (:id file)
        shared?   (:is-shared file)

        objects   (mf/deref refs/workspace-page-objects)
        frames    (->> (cfh/get-immediate-children objects uuid/zero)
                       (filterv cfh/frame-shape?))

        add-shared-fn
        (mf/use-fn
         (mf/deps file-id)
         #(st/emit! (dwl/set-file-shared file-id true)))

        on-add-shared
        (mf/use-fn
         (mf/deps file-id add-shared-fn)
         #(st/emit! (show-shared-dialog file-id add-shared-fn)))

        on-remove-shared
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (modal/show!
            {:type :delete-shared-libraries
             :origin :unpublish
             :ids #{file-id}
             :on-accept #(st/emit! (dwl/set-file-shared file-id false))
             :count-libraries 1})))

        on-export-shapes
        (mf/use-fn #(st/emit! (de/show-workspace-export-dialog)))

        ;; WARNING: this is broken, but as it is unused code because
        ;; it belongs to the pre styles/v2 feature which is enabled by
        ;; default right now. THIS CODE IS PENDING TO BE DELETED

        on-export-file
        (mf/use-fn
         (mf/deps file)
         (fn [event-name binary?]
           (st/emit! (ptk/event ::ev/event {::ev/name event-name
                                            ::ev/origin "workspace"
                                            :num-files 1}))

           (->> (rx/of file)
                (rx/flat-map
                 (fn [file]
                   (->> (rp/cmd! :has-file-libraries {:file-id (:id file)})
                        (rx/map #(assoc file :has-libraries? %)))))
                (rx/reduce conj [])
                (rx/subs
                 (fn [files]
                   (modal/show!
                    {:type :export
                     :team-id team-id
                     :has-libraries? (->> files (some :has-libraries?))
                     :files files
                     :binary? binary?}))))))

        on-export-binary-file
        (mf/use-fn
         (mf/deps on-export-file)
         (partial on-export-file "export-binary-files" true))

        on-export-standard-file
        (mf/use-fn
         (mf/deps on-export-file)
         (partial on-export-file "export-standard-files" false))

        on-export-frames
        (mf/use-fn
         (mf/deps frames)
         (fn [_]
           (st/emit! (de/show-workspace-export-frames-dialog (reverse frames)))))]

    [:& dropdown {:show true :on-close on-close}
     [:ul.sub-menu.file
      (if ^boolean shared?
        [:li {:on-click on-remove-shared}
         [:span (tr "dashboard.unpublish-shared")]]
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
         [:span (tr "dashboard.export-frames")]])]]))

(mf/defc menu
  {::mf/wrap-props false}
  [{:keys [layout file team-id]}]
  (let [show-menu*     (mf/use-state false)
        show-menu?     (deref show-menu*)
        sub-menu*      (mf/use-state false)
        sub-menu       (deref sub-menu*)

        open-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* true)))

        close-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)))

        close-sub-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! sub-menu* nil)))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [menu (-> (dom/get-target event)
                          (dom/get-data "menu")
                          (keyword))]
             (reset! sub-menu* menu))))

        toggle-flag
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [flag (-> (dom/get-current-target event)
                          (dom/get-data "flag")
                          (keyword))]
             (st/emit!
              (-> (dw/toggle-layout-flag flag)
                  (vary-meta assoc ::ev/origin "workspace-menu"))))))]


    [:*
     [:div.btn-icon-dark.btn-small {:on-click open-menu} i/actions]

     [:& dropdown {:show show-menu? :on-close close-menu}
      [:ul.menu
       [:li {:on-click on-menu-click
             :on-pointer-enter on-menu-click
             :data-menu "file"}
        [:span (tr "workspace.header.menu.option.file")]
        [:span i/arrow-slide]]
       [:li {:on-click on-menu-click
             :on-pointer-enter on-menu-click
             :data-menu "edit"}
        [:span (tr "workspace.header.menu.option.edit")]
        [:span i/arrow-slide]]
       [:li {:on-click on-menu-click
             :on-pointer-enter on-menu-click
             :data-menu :view}
        [:span (tr "workspace.header.menu.option.view")]
        [:span i/arrow-slide]]
       [:li {:on-click on-menu-click
             :on-pointer-enter on-menu-click
             :data-menu "preferences"}
        [:span (tr "workspace.header.menu.option.preferences")]
        [:span i/arrow-slide]]
       [:li.info {:on-click on-menu-click
                  :on-pointer-enter on-menu-click
                  :data-menu "help-info"}
        [:span (tr "workspace.header.menu.option.help-info")]
        [:span i/arrow-slide]]]]

     (case sub-menu
       :file
       [:& file-menu
        {:file file
         :team-id team-id
         :on-close close-sub-menu}]

       :edit
       [:& edit-menu
        {:on-close close-sub-menu}]

       :view
       [:& view-menu
        {:layout layout
         :toggle-flag toggle-flag
         :on-close close-sub-menu}]

       :preferences
       [:& preferences-menu
        {:layout layout
         :toggle-flag toggle-flag
         :on-close close-sub-menu}]

       :help-info
       [:& help-info-menu
        {:layout layout
         :on-close close-sub-menu}]

       nil)]))

;; --- Header Component

(mf/defc header
  {::mf/wrap-props false}
  [{:keys [file layout project page-id]}]
  (let [file-id          (:id file)
        file-name        (:name file)
        project-id       (:id project)
        team-id          (:team-id project)
        shared?          (:is-shared file)

        zoom             (mf/deref refs/selected-zoom)
        read-only?       (mf/use-ctx ctx/workspace-read-only?)

        on-increase      (mf/use-fn #(st/emit! (dw/increase-zoom nil)))
        on-decrease      (mf/use-fn #(st/emit! (dw/decrease-zoom nil)))
        on-zoom-reset    (mf/use-fn #(st/emit! dw/reset-zoom))
        on-zoom-fit      (mf/use-fn #(st/emit! dw/zoom-to-fit-all))
        on-zoom-selected (mf/use-fn #(st/emit! dw/zoom-to-selected-shape))


        editing*       (mf/use-state false)
        editing?       (deref editing*)

        input-ref      (mf/use-ref nil)

        handle-blur
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (let [value (str/trim (-> input-ref mf/ref-val dom/get-value))]
             (when (not= value "")
               (st/emit! (dw/rename-file file-id value)))
             (reset! editing* false))))

        handle-name-keydown
        (mf/use-fn
         (mf/deps handle-blur)
         (fn [event]
           (when (kbd/enter? event)
             (handle-blur event))))

        start-editing-name
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! editing* true)))

        go-back
        (mf/use-fn
         (mf/deps project)
         (fn []
           (st/emit! (dw/go-to-dashboard project))))

        nav-to-viewer
        (mf/use-fn
         (mf/deps file-id page-id)
         (fn []
           (let [params {:page-id page-id
                         :file-id file-id
                         :section "interactions"}]
             (st/emit! (dw/go-to-viewer params)))))

        nav-to-project
        (mf/use-fn
         (mf/deps team-id project-id)
         #(st/emit! (rt/nav-new-window* {:rname :dashboard-files
                                         :path-params {:team-id team-id
                                                       :project-id project-id}})))

        toggle-history
        (mf/use-fn
         #(st/emit! (-> (dw/toggle-layout-flag :document-history)
                        (vary-meta assoc ::ev/origin "workspace-header"))))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))

    [:header.workspace-header
     [:div.left-area
      [:div.main-icon
       [:a {:on-click go-back} i/logo-icon]]

      [:div.menu-section
       [:& menu {:layout layout
                 :file file
                 :read-only? read-only?
                 :team-id team-id
                 :page-id page-id}]

       [:div.project-tree {:alt (tr "workspace.sitemap")}
        [:span.project-name
         {:on-click nav-to-project}
         (:name project) " /"]

        (if ^boolean editing?
          [:input.file-name
           {:type "text"
            :ref input-ref
            :on-blur handle-blur
            :on-key-down handle-name-keydown
            :auto-focus true
            :default-value (:name file "")}]
          [:span
           {:on-double-click start-editing-name}
           file-name])]

       (when ^boolean shared?
         [:div.shared-badge i/library])]]

     [:div.center-area
      [:div.users-section
       [:& active-sessions]]]

     [:div.right-area
      [:div.options-section
       [:& persistence-state-widget]
       [:& export-progress-widget]
       (when-not ^boolean read-only?
         [:button.document-history
          {:alt (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
           :aria-label (tr "workspace.sidebar.history" (sc/get-tooltip :toggle-history))
           :class (when (contains? layout :document-history) "selected")
           :on-click toggle-history}
          i/recent])]

      [:div.options-section
       [:& zoom-widget-workspace
        {:zoom zoom
         :on-increase on-increase
         :on-decrease on-decrease
         :on-zoom-reset on-zoom-reset
         :on-zoom-fit on-zoom-fit
         :on-zoom-selected on-zoom-selected}]

       [:a.btn-icon-dark.btn-small.tooltip.tooltip-bottom-left
        {:alt (tr "workspace.header.viewer" (sc/get-tooltip :open-viewer))
         :on-click nav-to-viewer}
        i/play]]]]))


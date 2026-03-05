;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.main-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.event :as ev]
   [app.main.data.exports.assets :as de]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.data.plugins :as dp]
   [app.main.data.profile :as du]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.mcp :as mcp]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.versions :as dwv]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu*
                                                 dropdown-menu-item*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.subscription :refer [get-subscription-type
                                               main-menu-power-up*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.hooks.resize :as r]
   [app.plugins.register :as preg]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc shortcuts*
  {::mf/private true}
  [{:keys [id]}]
  [:span {:class (stl/css :shortcut)}
   (for [sc (scd/split-sc (sc/get-tooltip id))]
     [:span {:class (stl/css :shortcut-key)
             :key sc}
      sc])])

(mf/defc help-info-menu*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [layout on-close]}]
  (let [nav-to-helpc-center
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-help-center-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://help.penpot.app")))

        nav-to-community
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-community-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://community.penpot.app")))

        nav-to-youtube
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-tutorials-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://www.youtube.com/c/Penpot")))

        nav-to-templates
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-libraries-click"
                                            ::ev/origin "workspace"}))
           (dom/open-new-window "https://penpot.app/libraries-templates")))

        nav-to-github
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-github-repository-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://github.com/penpot/penpot")))

        nav-to-terms
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-terms-service-click"
                                            ::ev/origin "workspace-menu:in-app"}))
           (dom/open-new-window "https://penpot.app/terms")))

        nav-to-feedback
        (mf/use-fn #(st/emit! (dcm/go-to-feedback)))

        plugins?
        (features/active-feature? @st/state "plugins/runtime")

        mcp?
        (contains? cf/flags :mcp)

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
             (st/emit! (ptk/event ::ev/event {::ev/name "show-release-notes"
                                              :version version}))
             (println version)
             (if (and (kbd/alt? event) (kbd/mod? event))
               (st/emit! (modal/show {:type :onboarding}))
               (st/emit! (modal/show {:type :release-notes
                                      :version version}))))))]

    [:> dropdown-menu* {:show true
                        :on-close on-close
                        :class (stl/css-case :base-menu true
                                             :sub-menu true
                                             :pos-final-5 (not (or plugins? mcp?))
                                             :pos-final-6 (not= plugins? mcp?)
                                             :pos-final-7 (and plugins? mcp?))}
     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-helpc-center
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-helpc-center event)))
                              :id          "file-menu-help-center"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.help-center")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-community
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-community event)))
                              :id          "file-menu-community"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.community")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-youtube
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-youtube event)))
                              :id          "file-menu-youtube"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.tutorials")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    show-release-notes
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-release-notes event)))
                              :id          "file-menu-release-notes"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.release-notes")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-templates
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-templates event)))
                              :id          "file-menu-templates"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.libraries-and-templates")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-github
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-github event)))
                              :id          "file-menu-github"}
      [:span {:class (stl/css :item-name)}
       (tr "labels.github-repo")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    nav-to-terms
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-terms event)))
                              :id          "file-menu-terms"}
      [:span {:class (stl/css :item-name)}
       (tr "auth.terms-of-service")]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    show-shortcuts
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-shortcuts event)))
                              :id          "file-menu-shortcuts"}
      [:span {:class (stl/css :item-name)}
       (tr "label.shortcuts")]
      [:> shortcuts* {:id :show-shortcuts}]]

     (when (contains? cf/flags :user-feedback)
       [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                :on-click    nav-to-feedback
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (nav-to-feedback event)))
                                :id          "file-menu-feedback"}
        [:span {:class (stl/css :feedback :item-name)}
         (tr "labels.give-feedback")]])]))

(mf/defc preferences-menu*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [layout profile toggle-flag on-close toggle-theme]}]
  (let [show-nudge-options
        (mf/use-fn
         #(modal/show! {:type :nudge-option}))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :base-menu :sub-menu :pos-4)
                        :on-close on-close}
     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "scale-text"
                              :id          "file-menu-scale-text"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :scale-text)
         (tr "workspace.header.menu.disable-scale-content")
         (tr "workspace.header.menu.enable-scale-content"))]
      [:> shortcuts* {:id :scale}]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "snap-ruler-guides"
                              :id          "file-menu-snap-ruler-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-ruler-guides)
         (tr "workspace.header.menu.disable-snap-ruler-guides")
         (tr "workspace.header.menu.enable-snap-ruler-guides"))]
      [:> shortcuts* {:id :toggle-snap-ruler-guide}]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "snap-guides"
                              :id          "file-menu-snap-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-guides)
         (tr "workspace.header.menu.disable-snap-guides")
         (tr "workspace.header.menu.enable-snap-guides"))]
      [:> shortcuts* {:id :toggle-snap-guides}]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "dynamic-alignment"
                              :id          "file-menu-dynamic-alignment"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :dynamic-alignment)
         (tr "workspace.header.menu.disable-dynamic-alignment")
         (tr "workspace.header.menu.enable-dynamic-alignment"))]
      [:> shortcuts* {:id :toggle-alignment}]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "snap-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-pixel-grid)
         (tr "workspace.header.menu.disable-snap-pixel-grid")
         (tr "workspace.header.menu.enable-snap-pixel-grid"))]
      [:> shortcuts* {:id :snap-pixel-grid}]]

     [:> dropdown-menu-item* {:on-click    show-nudge-options
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-nudge-options event)))
                              :data-testid "snap-pixel-grid"
                              :id          "file-menu-nudge"}
      [:span {:class (stl/css :item-name)} (tr "modals.nudge-title")]]

     [:> dropdown-menu-item* {:on-click    toggle-theme
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-theme event)))
                              :data-testid "toggle-theme"
                              :id          "file-menu-toggle-theme"}
      [:span {:class (stl/css :item-name)}
       (case (:theme profile)  ;; dark -> light -> system -> dark and so on
         "dark" (tr "workspace.header.menu.toggle-light-theme")
         "light" (tr "workspace.header.menu.toggle-system-theme")
         "system" (tr "workspace.header.menu.toggle-dark-theme")
         (tr "workspace.header.menu.toggle-light-theme"))]
      [:> shortcuts* {:id :toggle-theme}]]]))

(mf/defc view-menu*
  {::mf/private true
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

    [:> dropdown-menu* {:show true
                        :class (stl/css :base-menu :sub-menu :pos-3)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "rulers"
                              :id          "file-menu-rulers"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :rulers)
         (tr "workspace.header.menu.hide-rules")
         (tr "workspace.header.menu.show-rules"))]
      [:> shortcuts* {:id :toggle-rulers}]]


     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "display-guides"
                              :id          "file-menu-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-guides)
         (tr "workspace.header.menu.hide-guides")
         (tr "workspace.header.menu.show-guides"))]
      [:> shortcuts* {:id :toggle-guides}]]


     (when-not ^boolean read-only?
       [:*
        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                 :on-click    toggle-color-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-color-palette event)))
                                 :id          "file-menu-color-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :colorpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:> shortcuts* {:id :toggle-colorpalette}]]

        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                 :on-click    toggle-text-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-text-palette event)))
                                 :id          "file-menu-text-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :textpalette)
            (tr "workspace.header.menu.hide-textpalette")
            (tr "workspace.header.menu.show-textpalette"))]
         [:> shortcuts* {:id :toggle-textpalette}]]])

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "display-artboard-names"
                              :id          "file-menu-artboards"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-artboard-names)
         (tr "workspace.header.menu.hide-artboard-names")
         (tr "workspace.header.menu.show-artboard-names"))]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "show-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :show-pixel-grid)
         (tr "workspace.header.menu.hide-pixel-grid")
         (tr "workspace.header.menu.show-pixel-grid"))]
      [:> shortcuts* {:id :show-pixel-grid}]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-testid "hide-ui"
                              :id          "file-menu-hide-ui"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.shape.menu.hide-ui")]
      [:> shortcuts* {:id :hide-ui}]]]))

(mf/defc edit-menu*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [on-close]}]
  (let [perms    (mf/use-ctx ctx/permissions)
        can-edit (:can-edit perms)

        select-all
        (mf/use-fn
         #(st/emit! (dw/select-all)))

        undo
        (mf/use-fn
         #(st/emit! dwu/undo))

        redo
        (mf/use-fn
         #(st/emit! dwu/redo))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :base-menu :sub-menu :pos-2)
                        :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    select-all
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (select-all event)))
                              :id          "file-menu-select-all"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.header.menu.select-all")]
      [:> shortcuts* {:id :select-all}]]

     (when can-edit
       [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                :on-click    undo
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (undo event)))
                                :id          "file-menu-undo"}
        [:span {:class (stl/css :item-name)}
         (tr "workspace.header.menu.undo")]
        [:> shortcuts* {:id :undo}]])

     (when can-edit
       [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                :on-click    redo
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (redo event)))
                                :id          "file-menu-redo"}
        [:span {:class (stl/css :item-name)}
         (tr "workspace.header.menu.redo")]
        [:> shortcuts* {:id :redo}]])]))

(mf/defc file-menu*
  {::mf/private true}
  [{:keys [on-close file]}]
  (let [file-id      (:id file)
        shared?      (:is-shared file)

        objects      (mf/deref refs/workspace-page-objects)
        selected     (mf/deref refs/selected-shapes)
        all-frames   (->> (cfh/get-immediate-children objects uuid/zero)
                          (filterv cfh/frame-shape?))

        ;; If there are selected frames, use only those. Otherwise, use all frames
        selected-frames (filterv #(contains? selected (:id %)) all-frames)
        frames       (if (seq selected-frames) selected-frames all-frames)

        perms        (mf/use-ctx ctx/permissions)
        can-edit     (:can-edit perms)

        on-remove-shared
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (modal/show! {:type :delete-shared-libraries
                         :origin :unpublish
                         :ids #{file-id}
                         :on-accept #(st/emit! (dwl/set-file-shared file-id false))
                         :count-libraries 1})))

        on-remove-shared-key-down
        (mf/use-fn
         (mf/deps on-remove-shared)
         (fn [event]
           (when (kbd/enter? event)
             (on-remove-shared event))))

        on-add-shared
        (mf/use-fn
         (mf/deps file-id)
         (fn [_event]
           (let [on-accept #(st/emit! (dwl/set-file-shared file-id true))]
             (st/emit! (dcm/show-shared-dialog file-id on-accept)))))

        on-add-shared-key-down
        (mf/use-fn
         (mf/deps on-add-shared)
         (fn [event]
           (when (kbd/enter? event)
             (on-add-shared event))))

        on-show-version-history
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (st/emit! (dw/toggle-layout-flag :document-history))))

        on-show-version-history-key-down
        (mf/use-fn
         (mf/deps on-show-version-history)
         (fn [event]
           (when (kbd/enter? event)
             (on-show-version-history event))))

        on-pin-version
        (mf/use-fn
         (fn [_]
           (st/emit! (dwv/create-version))))

        on-pin-version-key-down
        (mf/use-fn
         (mf/deps on-pin-version)
         (fn [event]
           (when (kbd/enter? event)
             (on-pin-version event))))

        on-export-shapes
        (mf/use-fn
         #(st/emit! (de/show-workspace-export-dialog {:origin "workspace:menu"})))

        on-export-shapes-key-down
        (mf/use-fn
         (mf/deps on-export-shapes)
         (fn [event]
           (when (kbd/enter? event)
             (on-export-shapes event))))

        on-export-file
        (mf/use-fn
         (mf/deps file)
         (fn [_]
           (st/emit! (-> (fexp/open-export-dialog [file])
                         (with-meta {::ev/origin "workspace"})))))

        on-export-file-key-down
        (mf/use-fn
         (mf/deps on-export-file)
         (fn [event]
           (when (kbd/enter? event)
             (on-export-file event))))

        on-export-frames
        (mf/use-fn
         (mf/deps frames)
         (fn [_]
           (st/emit! (de/show-workspace-export-frames-dialog (reverse frames)))))

        on-export-frames-key-down
        (mf/use-fn
         (mf/deps on-export-frames)
         (fn [event]
           (when (kbd/enter? event)
             (on-export-frames event))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css :base-menu :sub-menu :pos-1)
                        :on-close on-close}

     (if ^boolean shared?
       (when can-edit
         [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                  :on-click    on-remove-shared
                                  :on-key-down on-remove-shared-key-down
                                  :id          "file-menu-remove-shared"}
          [:span {:class (stl/css :item-name)}
           (tr "dashboard.unpublish-shared")]])

       (when can-edit
         [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                  :on-click    on-add-shared
                                  :on-key-down on-add-shared-key-down
                                  :id          "file-menu-add-shared"}
          [:span {:class (stl/css :item-name)}
           (tr "dashboard.add-shared")]]))

     (when can-edit
       [:*
        [:div {:class (stl/css :separator)}]

        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                 :on-click    on-pin-version
                                 :on-key-down on-pin-version-key-down
                                 :id          "file-menu-create-version"}
         [:span {:class (stl/css :item-name)}
          (tr "dashboard.create-version-menu")]]

        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                 :on-click    on-show-version-history
                                 :on-key-down on-show-version-history-key-down
                                 :id          "file-menu-show-version-history"}
         [:span {:class (stl/css :item-name)}
          (tr "dashboard.show-version-history")]
         [:> shortcuts* {:id :toggle-history}]]

        [:div {:class (stl/css :separator)}]])

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    on-export-shapes
                              :on-key-down on-export-shapes-key-down
                              :id          "file-menu-export-shapes"}
      [:span {:class (stl/css :item-name)}
       (tr "dashboard.export-shapes")]
      [:> shortcuts* {:id :export-shapes}]]

     [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                              :on-click    on-export-file
                              :on-key-down on-export-file-key-down
                              :data-format "binfile-v3"
                              :id          "file-menu-binary-file"}
      [:span {:class (stl/css :item-name)}
       (tr "dashboard.download-binary-file")]]

     (when (seq frames)
       [:> dropdown-menu-item* {:class (stl/css :base-menu-item :submenu-item)
                                :on-click    on-export-frames
                                :on-key-down on-export-frames-key-down
                                :id          "file-menu-export-frames"}
        [:span {:class (stl/css :item-name)}
         (tr "dashboard.export-frames")]])]))

(mf/defc plugins-menu*
  {::mf/private true
   ::mf/wrap [mf/memo]}
  [{:keys [open-plugins on-close]}]
  (when (features/active-feature? @st/state "plugins/runtime")
    (let [plugins          (preg/plugins-list)
          user-can-edit?   (:can-edit (deref refs/permissions))
          permissions-peek (deref refs/plugins-permissions-peek)]
      [:> dropdown-menu* {:show true
                          :class (stl/css :base-menu :sub-menu :pos-5 :plugins)
                          :on-close on-close}
       [:> dropdown-menu-item* {:on-click    open-plugins
                                :class       (stl/css :base-menu-item :submenu-item)
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (open-plugins event)))
                                :data-testid "open-plugins"
                                :id          "file-menu-open-plugins"}
        [:span {:class (stl/css :item-name)}
         (tr "workspace.plugins.menu.plugins-manager")]
        [:> shortcuts* {:id :plugins}]]


       (when (d/not-empty? plugins)
         [:div {:class (stl/css :separator)}])

       (for [[idx {:keys [plugin-id name host permissions] :as manifest}] (d/enumerate plugins)]
         (let [permissions        (or (get permissions-peek plugin-id) permissions)
               is-edition-plugin? (or (contains? permissions "content:write")
                                      (contains? permissions "library:write"))
               can-open?          (or user-can-edit?
                                      (not is-edition-plugin?))
               on-click
               (mf/use-fn
                (mf/deps can-open? name host manifest user-can-edit?)
                (fn [event]
                  (if can-open?
                    (do
                      (st/emit! (ptk/event ::ev/event {::ev/name "start-plugin"
                                                       ::ev/origin "workspace:menu"
                                                       :name name
                                                       :host host}))
                      (dp/open-plugin! manifest user-can-edit?))
                    (dom/stop-propagation event))))
               on-key-down
               (mf/use-fn
                (mf/deps can-open? name host manifest user-can-edit?)
                (fn [event]
                  (when can-open?
                    (when (kbd/enter? event)
                      (st/emit! (ptk/event ::ev/event {::ev/name "start-plugin"
                                                       ::ev/origin "workspace:menu"
                                                       :name name
                                                       :host host}))
                      (dp/open-plugin! manifest user-can-edit?)))))]

           [:> dropdown-menu-item* {:key         (dm/str "plugins-menu-" idx)
                                    :on-click    on-click
                                    :class       (stl/css-case :base-menu-item true
                                                               :submenu-item true
                                                               :disabled (not can-open?))
                                    :on-key-down on-key-down}
            [:span {:class (stl/css :item-name)} name]
            (when-not can-open?
              [:span {:title (tr "workspace.plugins.error.need-editor")}
               [:> icon* {:icon-id i/help
                          :class (stl/css :item-icon)}]])]))])))

(mf/defc mcp-menu*
  {::mf/private true}
  [{:keys [on-close]}]
  (let [plugins? (features/active-feature? @st/state "plugins/runtime")

        profile         (mf/deref refs/profile)
        workspace-local (mf/deref refs/workspace-local)

        mcp-enabled?    (-> profile :props :mcp-enabled)
        mcp-connected?  (-> workspace-local :mcp :connected)

        on-nav-to-integrations
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "manage-mpc-option"
                                            ::ev/origin "workspace-menu"}))
           (dom/open-new-window "/#/settings/integrations")))

        on-nav-to-integrations-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (on-nav-to-integrations))))

        on-toggle-mcp-plugin
        (mf/use-fn
         (fn []
           (if mcp-connected?
             (st/emit! (mcp/disconnect-mcp)
                       (ptk/event ::ev/event {::ev/name "disconnect-mcp-plugin"
                                              ::ev/origin "workspace-menu"}))
             (st/emit! (mcp/connect-mcp)
                       (ptk/event ::ev/event {::ev/name "connect-mcp-plugin"
                                              ::ev/origin "workspace-menu"})))))

        on-toggle-mcp-plugin-key-down
        (mf/use-fn
         (fn [event]
           (when (kbd/enter? event)
             (on-toggle-mcp-plugin))))]

    [:> dropdown-menu* {:show true
                        :class (stl/css-case :base-menu true
                                             :sub-menu true
                                             :pos-5 (not plugins?)
                                             :pos-6 plugins?)
                        :on-close on-close}

     (when mcp-enabled?
       [:> dropdown-menu-item* {:id          "mcp-menu-toggle-mcp-plugin"
                                :class       (stl/css :base-menu-item :submenu-item)
                                :on-click    on-toggle-mcp-plugin
                                :on-key-down on-toggle-mcp-plugin-key-down}
        [:span {:class (stl/css :item-name)}
         (if mcp-connected?
           (tr "workspace.header.menu.mcp.plugin.status.disconnect")
           (tr "workspace.header.menu.mcp.plugin.status.connect"))]])

     [:> dropdown-menu-item* {:id          "mcp-menu-nav-to-integrations"
                              :class       (stl/css :base-menu-item :submenu-item)
                              :on-click    on-nav-to-integrations
                              :on-key-down on-nav-to-integrations-key-down}
      [:span {:class (stl/css :item-name)}
       (if mcp-enabled?
         (tr "workspace.header.menu.mcp.server.status.enabled")
         (tr "workspace.header.menu.mcp.server.status.disabled"))]]]))

(mf/defc menu*
  [{:keys [layout file]}]
  (let [profile         (mf/deref refs/profile)
        workspace-local (mf/deref refs/workspace-local)

        show-menu*         (mf/use-state false)
        show-menu?         (deref show-menu*)
        selected-sub-menu* (mf/use-state nil)
        selected-sub-menu  (deref selected-sub-menu*)

        toggle-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (swap! show-menu* not)
           (when (not show-menu?)
             (reset! selected-sub-menu* nil))))

        close-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)))

        close-sub-menu
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! selected-sub-menu* nil)))

        close-all-menus
        (mf/use-fn
         (fn []
           (reset! show-menu* false)
           (reset! selected-sub-menu* nil)))

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [menu (-> (dom/get-current-target event)
                          (dom/get-data "testid")
                          (keyword))]
             (reset! selected-sub-menu* menu))))

        on-power-up-click
        (mf/use-fn
         (fn []
           (st/emit! (ptk/event ::ev/event {::ev/name "explore-pricing-click"
                                            ::ev/origin "workspace-menu"}))
           (dom/open-new-window "https://penpot.app/pricing")))

        toggle-flag
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [flag (-> (dom/get-current-target event)
                          (dom/get-data "testid")
                          (keyword))]
             (st/emit!
              (-> (dw/toggle-layout-flag flag)
                  (vary-meta assoc ::ev/origin "workspace-menu")))
             (reset! show-menu* false)
             (reset! selected-sub-menu* nil))))

        toggle-theme
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (du/toggle-theme))))

        open-plugins-manager
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! show-menu* false)
           (reset! selected-sub-menu* nil)
           (st/emit!
            (ptk/event ::ev/event {::ev/name "open-plugins-manager"
                                   ::ev/origin "workspace:menu"})
            (modal/show :plugin-management {}))))

        subscription           (:subscription (:props profile))
        subscription-type      (get-subscription-type subscription)]

    (mf/with-effect []
      (let [disposable (->> st/stream
                            (rx/filter #(= :interrupt %))
                            (rx/subs! close-all-menus))]
        (partial rx/dispose! disposable)))


    [:*
     [:> icon-button* {:variant "ghost"
                       :aria-pressed show-menu?
                       :aria-label (tr "shortcut-subsection.main-menu")
                       :on-click toggle-menu
                       :icon i/menu}]

     [:> dropdown-menu* {:show show-menu?
                         :id "workspace-menu"
                         :on-close close-menu
                         :class (stl/css :base-menu :menu)}
      [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid   "file"
                               :id          "file-menu-file"}
       [:span {:class (stl/css :item-name)}
        (tr "workspace.header.menu.option.file")]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :item-arrow)}]]

      [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid "edit"
                               :id          "file-menu-edit"}
       [:span {:class (stl/css :item-name)}
        (tr "workspace.header.menu.option.edit")]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :item-arrow)}]]

      [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid "view"
                               :id          "file-menu-view"}
       [:span {:class (stl/css :item-name)}
        (tr "workspace.header.menu.option.view")]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :item-arrow)}]]

      [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid "preferences"
                               :id          "file-menu-preferences"}
       [:span {:class (stl/css :item-name)}
        (tr "workspace.header.menu.option.preferences")]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :item-arrow)}]]

      (when (features/active-feature? @st/state "plugins/runtime")
        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                                 :on-click    on-menu-click
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (on-menu-click event)))
                                 :on-pointer-enter on-menu-click
                                 :data-testid "plugins"
                                 :id          "file-menu-plugins"}
         [:span {:class (stl/css :item-name)}
          (tr "workspace.plugins.menu.title")]
         [:> icon* {:icon-id i/arrow-right
                    :class (stl/css :item-arrow)}]])

      (when (contains? cf/flags :mcp)
        (let [mcp-enabled?   (-> profile :props :mcp-enabled)
              mcp-connected? (-> workspace-local :mcp :connected)
              mcp-active?    (and mcp-enabled? mcp-connected?)]
          [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                                   :on-click    on-menu-click
                                   :on-key-down (fn [event]
                                                  (when (kbd/enter? event)
                                                    (on-menu-click event)))
                                   :on-pointer-enter on-menu-click
                                   :data-testid "mcp"
                                   :id          "file-menu-mcp"}
           [:span {:class (stl/css :item-name)}
            (tr "workspace.header.menu.option.mcp")]
           [:span {:class (stl/css-case :item-indicator true
                                        :active mcp-active?)}]
           [:> icon* {:icon-id i/arrow-right
                      :class (stl/css :item-arrow)}]]))

      [:div {:class (stl/css :separator)}]

      [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-testid "help-info"
                               :id          "file-menu-help-info"}
       [:span {:class (stl/css :item-name)}
        (tr "workspace.header.menu.option.help-info")]
       [:> icon* {:icon-id i/arrow-right
                  :class (stl/css :item-arrow)}]]

      (when (and (contains? cf/flags :subscriptions)
                 (not= "enterprise" subscription-type))
        [:> main-menu-power-up* {:close-sub-menu close-sub-menu}])

      ;; TODO remove this block when subscriptions is full implemented
      (when (contains? cf/flags :subscriptions-old)
        [:> dropdown-menu-item* {:class (stl/css :base-menu-item :menu-item)
                                 :on-click    on-power-up-click
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (on-power-up-click)))
                                 :on-pointer-enter close-sub-menu
                                 :id          "file-menu-power-up"}
         [:span {:class (stl/css :item-name)}
          (tr "subscription.workspace.header.menu.option.power-up")]])]

     (case selected-sub-menu
       :file
       [:> file-menu* {:file file
                       :on-close close-sub-menu}]

       :edit
       [:> edit-menu* {:on-close close-sub-menu}]

       :view
       [:> view-menu* {:layout layout
                       :toggle-flag toggle-flag
                       :on-close close-sub-menu}]

       :preferences
       [:> preferences-menu* {:layout layout
                              :profile profile
                              :toggle-flag toggle-flag
                              :toggle-theme toggle-theme
                              :on-close close-sub-menu}]

       :plugins
       [:> plugins-menu* {:open-plugins open-plugins-manager
                          :on-close close-sub-menu}]

       :mcp
       [:> mcp-menu* {:on-close close-sub-menu}]

       :help-info
       [:> help-info-menu* {:layout layout
                            :on-close close-sub-menu}]

       nil)]))

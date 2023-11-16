;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.left-header
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.common :as dcm]
   [app.main.data.events :as ev]
   [app.main.data.exports :as de]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as dc]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [cuerdas.core :as str]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

;; --- Header menu and submenus

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

    [:& dropdown-menu {:show true
                       :on-close on-close
                       :list-class (stl/css-case :sub-menu true
                                                 :help-info true)}
     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-helpc-center
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-helpc-center event)))
                              :id          "file-menu-help-center"}
      [:span {:class (stl/css :item-name)} (tr "labels.help-center")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-community
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-community event)))
                              :id          "file-menu-community"}
      [:span {:class (stl/css :item-name)} (tr "labels.community")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-youtube
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-youtube event)))
                              :id          "file-menu-youtube"}
      [:span {:class (stl/css :item-name)} (tr "labels.tutorials")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    show-release-notes
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-release-notes event)))
                              :id          "file-menu-release-notes"}
      [:span {:class (stl/css :item-name)} (tr "labels.release-notes")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-templates
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-templates event)))
                              :id          "file-menu-templates"}
      [:span {:class (stl/css :item-name)} (tr "labels.libraries-and-templates")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-github
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-github event)))
                              :id          "file-menu-github"}
      [:span {:class (stl/css :item-name)} (tr "labels.github-repo")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    nav-to-terms
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (nav-to-terms event)))
                              :id          "file-menu-terms"}
      [:span {:class (stl/css :item-name)} (tr "auth.terms-of-service")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    show-shortcuts
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-shortcuts event)))
                              :id          "file-menu-shortcuts"}
      [:span {:class (stl/css :item-name)} (tr "label.shortcuts")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :show-shortcuts))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     (when (contains? cf/flags :user-feedback)
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    nav-to-feedback
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (nav-to-feedback event)))
                                :id          "file-menu-feedback"}
        [:span {:class (stl/css-case :feedback true
                                     :item-name true)} (tr "labels.give-feedback")]])]))

(mf/defc preferences-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [layout toggle-flag on-close]}]
  (let [show-nudge-options (mf/use-fn #(modal/show! {:type :nudge-option}))]

    [:& dropdown-menu {:show true
                       :list-class (stl/css-case :sub-menu true
                                                 :preferences true)
                       :on-close on-close}
     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "scale.-text"
                              :id          "file-menu-scale-text"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :scale-text)
         (tr "workspace.header.menu.disable-scale-content")
         (tr "workspace.header.menu.enable-scale-content"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-scale-text))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "snap-guides"
                              :id          "file-menu-snap-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-guides)
         (tr "workspace.header.menu.disable-snap-guides")
         (tr "workspace.header.menu.enable-snap-guides"))]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-guide))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "snap-grid"
                              :id          "file-menu-snap-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-grid)
         (tr "workspace.header.menu.disable-snap-grid")
         (tr "workspace.header.menu.enable-snap-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "dynamic-alignment"
                              :id          "file-menu-dynamic-alignment"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :dynamic-alignment)
         (tr "workspace.header.menu.disable-dynamic-alignment")
         (tr "workspace.header.menu.enable-dynamic-alignment"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-alignment))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    toggle-flag
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "snap-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-pixel-grid)
         (tr "workspace.header.menu.disable-snap-pixel-grid")
         (tr "workspace.header.menu.enable-snap-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :snap-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:on-click    show-nudge-options
                              :class       (stl/css :submenu-item)
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (show-nudge-options event)))
                              :data-test   "snap-pixel-grid"
                              :id          "file-menu-nudge"}
      [:span {:class (stl/css :item-name)} (tr "modals.nudge-title")]]]))

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

    [:& dropdown-menu {:show true
                       :list-class (stl/css-case :sub-menu true
                                                 :view true)
                       :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "rules"
                              :id          "file-menu-rules"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :rules)
         (tr "workspace.header.menu.hide-rules")
         (tr "workspace.header.menu.show-rules"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-rules))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "display-grid"
                              :id          "file-menu-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-grid)
         (tr "workspace.header.menu.hide-grid")
         (tr "workspace.header.menu.show-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     (when-not ^boolean read-only?
       [:*
        [:> dropdown-menu-item* {:class (stl/css :submenu-item)         :on-click    toggle-color-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-color-palette event)))
                                 :id          "file-menu-color-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :colorpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:span {:class (stl/css :shortcut)}
          (for [sc (scd/split-sc (sc/get-tooltip :toggle-colorpalette))]
            [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

        [:> dropdown-menu-item* {:class (stl/css :submenu-item)         :on-click    toggle-text-palette
                                 :on-key-down (fn [event]
                                                (when (kbd/enter? event)
                                                  (toggle-text-palette event)))
                                 :id          "file-menu-text-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :textpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:span {:class (stl/css :shortcut)}
          (for [sc (scd/split-sc (sc/get-tooltip :toggle-textpalette))]
            [:span {:class (stl/css :shortcut-key) :key sc} sc])]]])

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "display-artboard-names"
                              :id          "file-menu-artboards"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-artboard-names)
         (tr "workspace.header.menu.hide-artboard-names")
         (tr "workspace.header.menu.show-artboard-names"))]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "show-pixel-grid"
                              :id          "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :show-pixel-grid)
         (tr "workspace.header.menu.hide-pixel-grid")
         (tr "workspace.header.menu.show-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :show-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    toggle-flag
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (toggle-flag event)))
                              :data-test   "hide-ui"
                              :id          "file-menu-hide-ui"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.shape.menu.hide-ui")]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :hide-ui))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]]))

(mf/defc edit-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [on-close]}]
  (let [select-all (mf/use-fn #(st/emit! (dw/select-all)))
        undo       (mf/use-fn #(st/emit! dwc/undo))
        redo       (mf/use-fn #(st/emit! dwc/redo))]
    [:& dropdown-menu {:show true
                       :list-class (stl/css-case :sub-menu true
                                                 :edit true)
                       :on-close on-close}

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    select-all
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (select-all event)))
                              :id          "file-menu-select-all"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.header.menu.select-all")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :select-all))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    undo
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (undo event)))
                              :id          "file-menu-undo"}
      [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.undo")]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :undo))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    redo
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (redo event)))
                              :id          "file-menu-redo"}
      [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.redo")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :redo))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]]))

(mf/defc file-menu
  {::mf/wrap-props false}
  [{:keys [on-close file]}]
  (let [file-id   (:id file)
        shared?   (:is-shared file)

        objects   (mf/deref refs/workspace-page-objects)
        frames    (->> (cfh/get-immediate-children objects uuid/zero)
                       (filterv cfh/frame-shape?))

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

        on-export-shapes
        (mf/use-fn #(st/emit! (de/show-workspace-export-dialog)))

        on-export-shapes-key-down
        (mf/use-fn
         (mf/deps on-export-shapes)
         (fn [event]
           (when (kbd/enter? event)
             (on-export-shapes event))))

        on-export-file
        (mf/use-fn
         (mf/deps file)
         (fn [event]
           (let [target  (dom/get-current-target event)
                 binary? (= (dom/get-data target "binary") "true")
                 evname  (if binary?
                           "export-binary-files"
                           "export-standard-files")]
             (st/emit!
              (ptk/event ::ev/event {::ev/name evname
                                     ::ev/origin "workspace"
                                     :num-files 1})
              (dcm/export-files [file] binary?)))))

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

    [:& dropdown-menu {:show true
                       :list-class (stl/css-case :sub-menu true
                                                 :file true)
                       :on-close on-close}

     (if ^boolean shared?
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    on-remove-shared
                                :on-key-down on-remove-shared-key-down
                                :id          "file-menu-remove-shared"}
        [:span {:class (stl/css :item-name)} (tr "dashboard.unpublish-shared")]]

       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    on-add-shared
                                :on-key-down on-add-shared-key-down
                                :id          "file-menu-add-shared"}
        [:span {:class (stl/css :item-name)} (tr "dashboard.add-shared")]])

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    on-export-shapes
                              :on-key-down on-export-shapes-key-down
                              :id          "file-menu-export-shapes"}
      [:span {:class (stl/css :item-name)} (tr "dashboard.export-shapes")]
      [:span  {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :export-shapes))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    on-export-file
                              :on-key-down on-export-file-key-down
                              :data-binary true
                              :id          "file-menu-binary-file"}
      [:span {:class (stl/css :item-name)}
       (tr "dashboard.download-binary-file")]]

     [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                              :on-click    on-export-file
                              :on-key-down on-export-file-key-down
                              :data-binary false
                              :id          "file-menu-standard-file"}
      [:span {:class (stl/css :item-name)}
       (tr "dashboard.download-standard-file")]]

     (when (seq frames)
       [:> dropdown-menu-item* {:class (stl/css :submenu-item)
                                :on-click    on-export-frames
                                :on-key-down on-export-frames-key-down
                                :id          "file-menu-export-frames"}
        [:span {:class (stl/css :item-name)}
         (tr "dashboard.export-frames")]])]))

(mf/defc menu
  {::mf/wrap-props false}
  [{:keys [layout file]}]
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
           (let [menu (-> (dom/get-current-target event)
                          (dom/get-data "test")
                          (keyword))]
             (reset! sub-menu* menu))))

        toggle-flag
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (let [flag (-> (dom/get-current-target event)
                          (dom/get-data "test")
                          (keyword))]
             (st/emit!
              (-> (dw/toggle-layout-flag flag)
                  (vary-meta assoc ::ev/origin "workspace-menu"))))))]


    [:*
     [:div {:on-click open-menu
            :class (stl/css :menu-btn)} i/menu-refactor]

     [:& dropdown-menu {:show show-menu?
                        :on-close close-menu
                        :list-class (stl/css :menu)}

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-test   "file"
                               :id          "file-menu-file"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.file")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-test   "edit"
                               :id          "file-menu-edit"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.edit")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-test   "view"
                               :id          "file-menu-view"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.view")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:> dropdown-menu-item* {:class (stl/css :menu-item)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-test   "preferences"
                               :id          "file-menu-preferences"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.preferences")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]
      [:div {:class (stl/css :separator)}]
      [:> dropdown-menu-item* {:class (stl/css-case :menu-item true)
                               :on-click    on-menu-click
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-menu-click event)))
                               :on-pointer-enter on-menu-click
                               :data-test   "help-info"
                               :id          "file-menu-help-info"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.help-info")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]]

     (case sub-menu
       :file
       [:& file-menu
        {:file file
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

(mf/defc left-header
  {::mf/wrap-props false}
  [{:keys [file layout project page-id]}]
  (let [file-id     (:id file)
        file-name   (:name file)
        project-id  (:id project)
        team-id     (:team-id project)
        shared?     (:is-shared file)
        read-only?  (mf/use-ctx ctx/workspace-read-only?)

        editing*    (mf/use-state false)
        editing?    (deref editing*)
        input-ref   (mf/use-ref nil)

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

        close-modals
        (mf/use-fn
         #(st/emit! (dc/stop-picker)
                    (modal/hide)))

        go-back
        (mf/use-fn
         (mf/deps project)
         (fn []
           (close-modals)
           (st/emit! (dw/go-to-dashboard project))))

        nav-to-project
        (mf/use-fn
         (mf/deps team-id project-id)
         #(st/emit! (rt/nav-new-window* {:rname :dashboard-files
                                         :path-params {:team-id team-id
                                                       :project-id project-id}})))]

    (mf/with-effect [editing?]
      (when ^boolean editing?
        (dom/select-text! (mf/ref-val input-ref))))
    [:header {:class (stl/css :workspace-header-left)}
     [:a {:on-click go-back
          :class (stl/css :main-icon)} i/logo-icon]
     [:div {:alt (tr "workspace.sitemap")
            :class (stl/css :project-tree)}
      [:div
       {:class (stl/css :project-name)
        :on-click nav-to-project}
       (:name project)]
      (if ^boolean editing?
        [:input
         {:class (stl/css :file-name-input)
          :type "text"
          :ref input-ref
          :on-blur handle-blur
          :on-key-down handle-name-keydown
          :auto-focus true
          :default-value (:name file "")}]
        [:div
         {:class (stl/css :file-name)
          :title file-name
          :on-double-click start-editing-name}
         file-name])]
     (when ^boolean shared?
       [:span {:class (stl/css :shared-badge)} i/library-refactor])
     [:div {:class (stl/css :menu-section)}
      [:& menu {:layout layout
                :file file
                :read-only? read-only?
                :team-id team-id
                :page-id page-id}]]]))


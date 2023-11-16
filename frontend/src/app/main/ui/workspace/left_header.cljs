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
   [app.main.data.common :refer [show-shared-dialog]]
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
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.dropdown-menu :refer [dropdown-menu dropdown-menu-item]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.router :as rt]
   [beicon.core :as rx]
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
     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-helpc-center
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-helpc-center event)))
                             :id          "file-menu-help-center"
                             :unique-key  "file-menu-help-center"}
      [:span {:class (stl/css :item-name)} (tr "labels.help-center")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-community
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-community event)))
                             :id          "file-menu-community"
                             :unique-key  "file-menu-community"}
      [:span {:class (stl/css :item-name)} (tr "labels.community")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-youtube
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-youtube event)))
                             :id          "file-menu-youtube"
                             :unique-key  "file-menu-youtube"}
      [:span {:class (stl/css :item-name)} (tr "labels.tutorials")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    show-release-notes
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (show-release-notes event)))
                             :id          "file-menu-release-notes"
                             :unique-key  "file-menu-release-notes"}
      [:span {:class (stl/css :item-name)} (tr "labels.release-notes")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-templates
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-templates event)))
                             :id          "file-menu-templates"
                             :unique-key  "file-menu-templates"}
      [:span {:class (stl/css :item-name)} (tr "labels.libraries-and-templates")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-github
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-github event)))
                             :id          "file-menu-github"
                             :unique-key  "file-menu-github"}
      [:span {:class (stl/css :item-name)} (tr "labels.github-repo")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    nav-to-terms
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (nav-to-terms event)))
                             :id          "file-menu-terms"
                             :unique-key  "file-menu-terms"}
      [:span {:class (stl/css :item-name)} (tr "auth.terms-of-service")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    show-shortcuts
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (show-shortcuts event)))
                             :id          "file-menu-shortcuts"
                             :unique-key  "file-menu-shortcuts"}
      [:span {:class (stl/css :item-name)} (tr "label.shortcuts")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :show-shortcuts))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     (when (contains? cf/flags :user-feedback)
       [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                               :on-click    nav-to-feedback
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (nav-to-feedback event)))
                               :id          "file-menu-feedback"
                               :unique-key  "file-menu-feedback"}
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
     [:& dropdown-menu-item {:on-click    toggle-flag
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "scale.-text"
                             :id          "file-menu-scale-text"
                             :unique-key  "file-menu-scale-text"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :scale-text)
         (tr "workspace.header.menu.disable-scale-content")
         (tr "workspace.header.menu.enable-scale-content"))]
      [:span {:class (stl/css :shortcut)}
      (for [sc (scd/split-sc (sc/get-tooltip :toggle-scale-text))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:on-click    toggle-flag
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "snap-guides"
                             :id          "file-menu-snap-guides"
                             :unique-key  "file-menu-snap-guides"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-guides)
         (tr "workspace.header.menu.disable-snap-guides")
         (tr "workspace.header.menu.enable-snap-guides"))]
      [:span {:class (stl/css :shortcut)}

      (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-guide))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:on-click    toggle-flag
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "snap-grid"
                             :id          "file-menu-snap-grid"
                             :unique-key  "file-menu-snap-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-grid)
         (tr "workspace.header.menu.disable-snap-grid")
         (tr "workspace.header.menu.enable-snap-grid"))]
      [:span {:class (stl/css :shortcut)}
      (for [sc (scd/split-sc (sc/get-tooltip :toggle-snap-grid))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:on-click    toggle-flag
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "dynamic-alignment"
                             :id          "file-menu-dynamic-alignment"
                             :unique-key  "file-menu-dynamic-alignment"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :dynamic-alignment)
         (tr "workspace.header.menu.disable-dynamic-alignment")
         (tr "workspace.header.menu.enable-dynamic-alignment"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :toggle-alignment))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:on-click    toggle-flag
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "snap-pixel-grid"
                             :id          "file-menu-pixel-grid"
                             :unique-key  "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :snap-pixel-grid)
         (tr "workspace.header.menu.disable-snap-pixel-grid")
         (tr "workspace.header.menu.enable-snap-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :snap-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:on-click    show-nudge-options
                             :klass       (stl/css :submenu-item)
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (show-nudge-options event)))
                             :data-test   "snap-pixel-grid"
                             :id          "file-menu-nudge"
                             :unique-key  "file-menu-nudge"}
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

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    toggle-flag
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "rules"
                             :id          "file-menu-rules"
                             :unique-key  "file-menu-rules"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :rules)
         (tr "workspace.header.menu.hide-rules")
         (tr "workspace.header.menu.show-rules"))]
      [:span {:class (stl/css :shortcut)}
      (for [sc (scd/split-sc (sc/get-tooltip :toggle-rules))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    toggle-flag
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "display-grid"
                             :id          "file-menu-grid"
                             :unique-key  "file-menu-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-grid)
         (tr "workspace.header.menu.hide-grid")
         (tr "workspace.header.menu.show-grid"))]
      [:span {:class (stl/css :shortcut)}
      (for [sc (scd/split-sc (sc/get-tooltip :toggle-grid))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]


     (when-not ^boolean read-only?
       [:*
        [:& dropdown-menu-item {:klass (stl/css :submenu-item)         :on-click    toggle-color-palette
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (toggle-color-palette event)))
                                :id          "file-menu-color-palette"
                                :unique-key  "file-menu-color-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :colorpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:span {:class (stl/css :shortcut)}
         (for [sc (scd/split-sc (sc/get-tooltip :toggle-colorpalette))]
           [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

        [:& dropdown-menu-item {:klass (stl/css :submenu-item)         :on-click    toggle-text-palette
                                :on-key-down (fn [event]
                                               (when (kbd/enter? event)
                                                 (toggle-text-palette event)))
                                :id          "file-menu-text-palette"
                                :unique-key  "file-menu-text-palette"}
         [:span {:class (stl/css :item-name)}
          (if (contains? layout :textpalette)
            (tr "workspace.header.menu.hide-palette")
            (tr "workspace.header.menu.show-palette"))]
         [:span {:class (stl/css :shortcut)}
         (for [sc (scd/split-sc (sc/get-tooltip :toggle-textpalette))]
           [:span {:class (stl/css :shortcut-key) :key sc} sc])]]])

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    toggle-flag
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "display-artboard-names"
                             :id          "file-menu-artboards"
                             :unique-key  "file-menu-artboards"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :display-artboard-names)
         (tr "workspace.header.menu.hide-artboard-names")
         (tr "workspace.header.menu.show-artboard-names"))]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    toggle-flag
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "show-pixel-grid"
                             :id          "file-menu-pixel-grid"
                             :unique-key  "file-menu-pixel-grid"}
      [:span {:class (stl/css :item-name)}
       (if (contains? layout :show-pixel-grid)
         (tr "workspace.header.menu.hide-pixel-grid")
         (tr "workspace.header.menu.show-pixel-grid"))]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :show-pixel-grid))]
         [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    toggle-flag
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (toggle-flag event)))
                             :data-test   "hide-ui"
                             :id          "file-menu-hide-ui"
                             :unique-key  "file-menu-hide-ui"}
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

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    select-all
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (select-all event)))
                             :id          "file-menu-select-all"
                             :unique-key  "file-menu-select-all"}
      [:span {:class (stl/css :item-name)}
       (tr "workspace.header.menu.select-all")]
      [:span {:class (stl/css :shortcut)}

       (for [sc (scd/split-sc (sc/get-tooltip :select-all))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    undo
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (undo event)))
                             :id          "file-menu-undo"
                             :unique-key  "file-menu-undo"}
      [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.undo")]
      [:span {:class (stl/css :shortcut)}
       (for [sc (scd/split-sc (sc/get-tooltip :undo))]
         [:span {:class (stl/css :shortcut-key)
                 :key sc}
          sc])]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    redo
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (redo event)))
                             :id          "file-menu-redo"
                             :unique-key  "file-menu-redo"}
      [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.redo")]
      [:span {:class (stl/css :shortcut)}

      (for [sc (scd/split-sc (sc/get-tooltip :redo))]
        [:span {:class (stl/css :shortcut-key)
                :key sc}
         sc])]]]))

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
        (mf/use-fn (mf/deps file-id)
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

    [:& dropdown-menu {:show true
                       :list-class (stl/css-case :sub-menu true
                                                 :file true)
                       :on-close on-close}
     (if ^boolean shared?
       [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                               :on-click    on-remove-shared
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-remove-shared event)))
                               :id          "file-menu-remove-shared"
                               :unique-key  "file-menu-remove-shared"}
        [:span {:class (stl/css :item-name)} (tr "dashboard.unpublish-shared")]]

       [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                               :on-click    on-add-shared
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-add-shared event)))
                               :id          "file-menu-add-shared"
                               :unique-key  "file-menu-add-shared"}
        [:span {:class (stl/css :item-name)} (tr "dashboard.add-shared")]])

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    on-export-shapes
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (on-export-shapes event)))
                             :id          "file-menu-export-shapes"
                             :unique-key  "file-menu-export-shapes"}
      [:span {:class (stl/css :item-name)} (tr "dashboard.export-shapes")]
      [:span  {:class (stl/css :shortcut)}
      (for [sc (scd/split-sc (sc/get-tooltip :export-shapes))]
        [:span {:class (stl/css :shortcut-key) :key sc} sc])]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    on-export-binary-file
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (on-export-binary-file event)))
                             :id          "file-menu-binary-file"
                             :unique-key  "file-menu-binary-file"}
      [:span {:class (stl/css :item-name)}  (tr "dashboard.download-binary-file")]]

     [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                             :on-click    on-export-standard-file
                             :on-key-down (fn [event]
                                            (when (kbd/enter? event)
                                              (on-export-standard-file event)))
                             :id          "file-menu-standard-file"
                             :unique-key  "file-menu-standard-file"}
      [:span {:class (stl/css :item-name)} (tr "dashboard.download-standard-file")]]


     (when (seq frames)
       [:& dropdown-menu-item {:klass (stl/css :submenu-item)
                               :on-click    on-export-frames
                               :on-key-down (fn [event]
                                              (when (kbd/enter? event)
                                                (on-export-frames event)))
                               :id          "file-menu-export-frames"
                               :unique-key  "file-menu-export-frames"}
        [:span {:class (stl/css :item-name)}
         (tr "dashboard.export-frames")]])]))

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

      [:& dropdown-menu-item {:klass (stl/css :menu-item)
                              :on-click    on-menu-click
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (on-menu-click event)))
                              :on-pointer-enter on-menu-click
                              :data-test   "file"
                              :id          "file-menu-file"
                              :unique-key  "file-menu-file"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.file")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:& dropdown-menu-item {:klass (stl/css :menu-item)
                              :on-click    on-menu-click
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (on-menu-click event)))
                              :on-pointer-enter on-menu-click
                              :data-test   "edit"
                              :id          "file-menu-edit"
                              :unique-key  "file-menu-edit"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.edit")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:& dropdown-menu-item {:klass (stl/css :menu-item)
                              :on-click    on-menu-click
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (on-menu-click event)))
                              :on-pointer-enter on-menu-click
                              :data-test   "view"
                              :id          "file-menu-view"
                              :unique-key  "file-menu-view"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.view")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]

      [:& dropdown-menu-item {:klass (stl/css :menu-item)
                              :on-click    on-menu-click
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (on-menu-click event)))
                              :on-pointer-enter on-menu-click
                              :data-test   "preferences"
                              :id          "file-menu-preferences"
                              :unique-key  "file-menu-preferences"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.preferences")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]
      [:div {:class (stl/css :separator)}]
      [:& dropdown-menu-item {:klass (stl/css-case :menu-item true)
                              :on-click    on-menu-click
                              :on-key-down (fn [event]
                                             (when (kbd/enter? event)
                                               (on-menu-click event)))
                              :on-pointer-enter on-menu-click
                              :data-test   "help-info"
                              :id          "file-menu-help-info"
                              :unique-key  "file-menu-help-info"}
       [:span {:class (stl/css :item-name)} (tr "workspace.header.menu.option.help-info")]
       [:span {:class (stl/css :open-arrow)} i/arrow-refactor]]]

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


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.header
  (:require
   [rumext.alpha :as mf]
   [uxbox.main.ui.icons :as i :include-macros true]
   [uxbox.config :as cfg]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.workspace.presence :as presence]
   [uxbox.util.i18n :as i18n :refer [t]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.common.math :as mth]
   [uxbox.util.router :as rt]))

;; --- Zoom Widget

(mf/defc zoom-widget
  {:wrap [mf/memo]}
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
  [{:keys [layout project file] :as props}]
  (let [show-menu? (mf/use-state false)
        locale (i18n/use-locale)

        add-shared-fn #(st/emit! nil (dw/set-file-shared (:id file) true))
        on-add-shared
        #(modal/show! confirm-dialog
                        {:message (t locale "dashboard.grid.add-shared-message" (:name file))
                         :hint (t locale "dashboard.grid.add-shared-hint")
                         :accept-text (t locale "dashboard.grid.add-shared-accept")
                         :not-danger? true
                         :on-accept add-shared-fn})

        remove-shared-fn #(st/emit! nil (dw/set-file-shared (:id file) false))
        on-remove-shared
        #(modal/show! confirm-dialog
                        {:message (t locale "dashboard.grid.remove-shared-message" (:name file))
                         :hint (t locale "dashboard.grid.remove-shared-hint")
                         :accept-text (t locale "dashboard.grid.remove-shared-accept")
                         :not-danger? false
                         :on-accept remove-shared-fn})]

    [:div.menu-section
     [:div.btn-icon-dark.btn-small {:on-click #(reset! show-menu? true)} i/actions]
     [:div.project-tree {:alt (t locale "header.sitemap")}
      [:span.project-name (:name project) " /"]
      [:span (:name file)]]
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
        [:span.shortcut "Ctrl+r"]]

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
          [:span (t locale "dashboard.grid.remove-shared")]]
         [:li {:on-click on-add-shared}
          [:span (t locale "dashboard.grid.add-shared")]])
       ]]]))

;; --- Header Component

(mf/defc header
  [{:keys [file layout project] :as props}]
  (let [locale (i18n/use-locale)
        team-id (:team-id project)
        go-back #(st/emit! (rt/nav :dashboard-team {:team-id team-id}))
        zoom (mf/deref refs/selected-zoom)
        page (mf/deref refs/workspace-page)
        locale (i18n/use-locale)
        router (mf/deref refs/router)
        view-url (rt/resolve router :viewer {:page-id (:id page)} {:index 0})]
    [:header.workspace-header
     [:div.main-icon
      [:a {:on-click go-back} i/logo-icon]]

     [:& menu {:layout layout
               :project project
               :file file}]

     [:div.users-section
      [:& presence/active-sessions]]

     [:div.options-section
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


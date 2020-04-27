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
   [uxbox.builtins.icons :as i :include-macros true]
   [uxbox.config :as cfg]
   [uxbox.main.data.history :as udh]
   [uxbox.main.data.workspace :as dw]
   [uxbox.main.refs :as refs]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.images :refer [import-image-modal]]
   [uxbox.main.ui.components.dropdown :refer [dropdown]]
   [uxbox.main.ui.workspace.presence :as presence]
   [uxbox.util.i18n :as i18n :refer [tr t]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.math :as mth]
   [uxbox.util.router :as rt]))

;; --- Zoom Widget

(mf/defc zoom-widget
  {:wrap [mf/memo]}
  [{:keys [zoom
           on-increase
           on-decrease
           on-zoom-to-50
           on-zoom-to-100
           on-zoom-to-200]
    :as props}]
  (let [show-dropdown? (mf/use-state false)
        ;; increase #(st/emit! dv/increase-zoom)
        ;; decrease #(st/emit! dv/decrease-zoom)
        ;; zoom-to-50 #(st/emit! dv/zoom-to-50)
        ;; zoom-to-100 #(st/emit! dv/reset-zoom)
        ;; zoom-to-200 #(st/emit! dv/zoom-to-200)
        ]
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
       [:li {:on-click on-zoom-to-50}
        "Zoom to 50%" [:span "Shift + 0"]]
       [:li {:on-click on-zoom-to-100}
        "Zoom to 100%" [:span "Shift + 1"]]
       [:li {:on-click on-zoom-to-200}
        "Zoom to 200%" [:span "Shift + 2"]]]]]))

;; --- Header Users

(mf/defc menu
  [{:keys [layout project file] :as props}]
  (let [show-menu? (mf/use-state false)
        toggle-sitemap #(st/emit! (dw/toggle-layout-flag :sitemap))
        locale (i18n/use-locale)]

    [:div.menu-section
     [:div.menu-button {:on-click #(reset! show-menu? true)} i/actions]
     [:div.project-tree {:alt (t locale "header.sitemap")
                         :class (classnames :selected (contains? layout :sitemap))
                         :on-click toggle-sitemap}
      [:span.project-name (:name project) " /"]
      [:span (:name file)]]

     [:& dropdown {:show @show-menu?
                   :on-close #(reset! show-menu? false)}
      [:ul.menu
       [:li {:on-click #(st/emit! (dw/toggle-layout-flag :rules))}
        [:span i/ruler]
        [:span
         (if (contains? layout :rules)
           (t locale "workspace.header.menu.hide-rules")
           (t locale "workspace.header.menu.show-rules"))]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flag :grid))}
        [:span i/grid]
        [:span
         (if (contains? layout :grid)
           (t locale "workspace.header.menu.hide-grid")
           (t locale "workspace.header.menu.show-grid"))]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flag :layers))}
        [:span i/layers]
        [:span
         (if (contains? layout :layers)
           (t locale "workspace.header.menu.hide-layers")
           (t locale "workspace.header.menu.show-layers"))]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flag :colorpalette))}
        [:span i/palette]
        [:span
         (if (contains? layout :colorpalette)
           (t locale "workspace.header.menu.hide-palette")
           (t locale "workspace.header.menu.show-palette"))]]

       [:li {:on-click #(st/emit! (dw/toggle-layout-flag :libraries))}
        [:span i/icon-set]
        [:span
         (if (contains? layout :libraries)
           (t locale "workspace.header.menu.hide-libraries")
           (t locale "workspace.header.menu.show-libraries"))]]
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
        :on-increase #(st/emit! dw/increase-zoom)
        :on-decrease #(st/emit! dw/decrease-zoom)
        :on-zoom-to-50 #(st/emit! dw/zoom-to-50)
        :on-zoom-to-100 #(st/emit! dw/reset-zoom)
        :on-zoom-to-200 #(st/emit! dw/zoom-to-200)}]

      [:a.preview-button
       {;; :target "__blank"
        :alt (t locale "workspace.header.viewer")
        :href (str "#" view-url)} i/play]]]))


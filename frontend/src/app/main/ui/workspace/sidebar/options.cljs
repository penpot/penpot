;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.tabs-container :refer [tabs-container tabs-element]]
   [app.main.ui.context :as ctx]
   [app.main.ui.viewer.inspect.right-sidebar :as hrs]
   [app.main.ui.workspace.sidebar.options.menus.align :refer [align-options]]
   [app.main.ui.workspace.sidebar.options.menus.bool :refer [bool-options]]
   [app.main.ui.workspace.sidebar.options.menus.exports :refer [exports-menu]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.interactions :refer [interactions-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :as layout-container]
   [app.main.ui.workspace.sidebar.options.page :as page]
   [app.main.ui.workspace.sidebar.options.shapes.bool :as bool]
   [app.main.ui.workspace.sidebar.options.shapes.circle :as circle]
   [app.main.ui.workspace.sidebar.options.shapes.frame :as frame]
   [app.main.ui.workspace.sidebar.options.shapes.group :as group]
   [app.main.ui.workspace.sidebar.options.shapes.image :as image]
   [app.main.ui.workspace.sidebar.options.shapes.multiple :as multiple]
   [app.main.ui.workspace.sidebar.options.shapes.path :as path]
   [app.main.ui.workspace.sidebar.options.shapes.rect :as rect]
   [app.main.ui.workspace.sidebar.options.shapes.svg-raw :as svg-raw]
   [app.main.ui.workspace.sidebar.options.shapes.text :as text]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

;; --- Options

(mf/defc shape-options
  {::mf/wrap [#(mf/throttle % 60)]}
  [{:keys [shape shapes-with-children page-id file-id shared-libs]}]
  (let [workspace-modifiers (mf/deref refs/workspace-modifiers)
        modifiers (get-in workspace-modifiers [(:id shape) :modifiers])
        shape (gsh/transform-shape shape modifiers)]
    [:*
     (case (:type shape)
       :frame   [:& frame/options {:shape shape :shape-with-children shapes-with-children :file-id file-id :shared-libs shared-libs}]
       :group   [:& group/options {:shape shape :shape-with-children shapes-with-children :file-id file-id :shared-libs shared-libs}]
       :text    [:& text/options {:shape shape  :file-id file-id :shared-libs shared-libs}]
       :rect    [:& rect/options {:shape shape}]
       :circle  [:& circle/options {:shape shape}]
       :path    [:& path/options {:shape shape}]
       :image   [:& image/options {:shape shape}]
       :svg-raw [:& svg-raw/options {:shape shape}]
       :bool    [:& bool/options {:shape shape}]
       nil)
     [:& exports-menu
      {:ids [(:id shape)]
       :values (select-keys shape [:exports])
       :shape shape
       :page-id page-id
       :file-id file-id}]]))

(mf/defc options-content
  {::mf/wrap [mf/memo]}
  [{:keys [selected section shapes shapes-with-children page-id file-id on-change-section on-expand]}]
  (let [drawing              (mf/deref refs/workspace-drawing)
        objects              (mf/deref refs/workspace-page-objects)
        shared-libs          (mf/deref refs/workspace-libraries)
        edition              (mf/deref refs/selected-edition)
        grid-edition         (mf/deref refs/workspace-grid-edition)

        selected-shapes      (into [] (keep (d/getf objects)) selected)
        first-selected-shape (first selected-shapes)
        shape-parent-frame   (cph/get-frame objects (:frame-id first-selected-shape))

        edit-grid?           (ctl/grid-layout? objects edition)
        selected-cell        (dm/get-in grid-edition [edition :selected])

        on-change-tab
        (fn [options-mode]
          (st/emit! (udw/set-options-mode options-mode))
          (if (= options-mode :inspect)
            (st/emit! :interrupt (udw/set-workspace-read-only true))
            (st/emit! :interrupt (udw/set-workspace-read-only false))))]

    [:div.tool-window
     [:div.tool-window-content
      [:& tabs-container {:on-change-tab on-change-tab
                         :selected section}
       [:& tabs-element {:id :design
                         :title (tr "workspace.options.design")}
        [:div.element-options
         [:& align-options]
         [:& bool-options]
         (cond
           (some? selected-cell)
           [:& grid-cell/options
            {:shape (get objects edition)
             :cell (dm/get-in objects [edition :layout-grid-cells selected-cell])}]

           edit-grid?
           [:& layout-container/grid-layout-edition
            {:ids [edition]
             :values (get objects edition)}]

           (d/not-empty? drawing)
           [:& shape-options
            {:shape (:object drawing)
             :page-id page-id
             :file-id file-id
             :shared-libs shared-libs}]

           (= 0 (count selected))
           [:& page/options]

           (= 1 (count selected))
           [:& shape-options
            {:shape (first selected-shapes)
             :page-id page-id
             :file-id file-id
             :shared-libs shared-libs
             :shapes-with-children shapes-with-children}]

           :else
           [:& multiple/options
            {:shapes-with-children shapes-with-children
             :shapes selected-shapes
             :page-id page-id
             :file-id file-id
             :shared-libs shared-libs}])]]

       [:& tabs-element
        {:id :prototype
         :title (tr "workspace.options.prototype")}

        [:div.element-options
         [:& interactions-menu {:shape (first shapes)}]]]

       [:& tabs-element {:id :inspect
                         :title (tr "workspace.options.inspect")}

        [:div.element-options.element-options-inspect
         [:& hrs/right-sidebar {:page-id           page-id
                                :file-id           file-id
                                :frame             shape-parent-frame
                                :shapes            selected-shapes
                                :on-change-section on-change-section
                                :on-expand         on-expand
                                :from              :workspace}]]]]]]))

;; TODO: this need optimizations, selected-objects and
;; selected-objects-with-children are derefed always but they only
;; need on multiple selection in majority of cases

(mf/defc options-toolbox
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [section              (obj/get props "section")
        selected             (obj/get props "selected")
        on-change-section    (obj/get props "on-change-section")
        on-expand            (obj/get props "on-expand")
        page-id              (mf/use-ctx ctx/current-page-id)
        file-id              (mf/use-ctx ctx/current-file-id)
        shapes               (mf/deref refs/selected-objects)
        shapes-with-children (mf/deref refs/selected-shapes-with-children)]

    [:& options-content {:shapes shapes
                         :selected selected
                         :shapes-with-children shapes-with-children
                         :file-id file-id
                         :page-id page-id
                         :section section
                         :on-change-section on-change-section
                         :on-expand on-expand}]))

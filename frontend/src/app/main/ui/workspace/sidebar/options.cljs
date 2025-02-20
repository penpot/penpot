;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes :as gsh]
   [app.common.types.shape.layout :as ctl]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.inspect.right-sidebar :as hrs]
   [app.main.ui.workspace.sidebar.options.menus.align :refer [align-options]]
   [app.main.ui.workspace.sidebar.options.menus.bool :refer [bool-options]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu]]
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
   [rumext.v2 :as mf]))

;; --- Options

(mf/defc shape-options*
  {::mf/wrap [#(mf/throttle % 60)]}
  [{:keys [shape shapes-with-children page-id file-id libraries] :as props}]
  (let [shape-type (dm/get-prop shape :type)
        shape-id   (dm/get-prop shape :id)

        modifiers  (mf/deref refs/workspace-modifiers)
        modifiers  (dm/get-in modifiers [shape-id :modifiers])

        shape      (gsh/transform-shape shape modifiers)]

    [:*
     (case shape-type
       :frame   [:> frame/options* props]
       :group   [:& group/options {:shape shape :shape-with-children shapes-with-children :file-id file-id :libraries libraries}]
       :text    [:& text/options {:shape shape  :file-id file-id :libraries libraries}]
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

(mf/defc specialized-panel
  {::mf/wrap [mf/memo]}
  [{:keys [panel]}]
  (when (= (:type panel) :component-swap)
    [:& component-menu {:shapes (:shapes panel) :swap-opened? true}]))

(mf/defc design-menu*
  {::mf/wrap [mf/memo]}
  [{:keys [selected objects page-id file-id selected-shapes shapes-with-children]}]
  (let [sp-panel             (mf/deref refs/specialized-panel)
        drawing              (mf/deref refs/workspace-drawing)
        libraries            (mf/deref refs/libraries)
        edition              (mf/deref refs/selected-edition)
        edit-grid?           (ctl/grid-layout? objects edition)
        grid-edition         (mf/deref refs/workspace-grid-edition)
        selected-cells       (->> (dm/get-in grid-edition [edition :selected])
                                  (map #(dm/get-in objects [edition :layout-grid-cells %])))]

    [:div {:class (stl/css :element-options :design-options)}
     [:& align-options]
     [:& bool-options]

     (cond
       (and edit-grid? (d/not-empty? selected-cells))
       [:& grid-cell/options
        {:shape (get objects edition)
         :cells selected-cells}]

       edit-grid?
       [:& layout-container/grid-layout-edition
        {:ids [edition]
         :values (get objects edition)}]

       (not (nil? sp-panel))
       [:& specialized-panel {:panel sp-panel}]

       (d/not-empty? drawing)
       [:> shape-options*
        {:shape (:object drawing)
         :page-id page-id
         :file-id file-id
         :libraries libraries}]

       (= 0 (count selected))
       [:> page/options*]

       (= 1 (count selected))
       [:> shape-options*
        {:shape (first selected-shapes)
         :page-id page-id
         :file-id file-id
         :libraries libraries
         :shapes-with-children shapes-with-children}]

       :else
       [:& multiple/options
        {:shapes-with-children shapes-with-children
         :shapes selected-shapes
         :page-id page-id
         :file-id file-id
         :libraries libraries}])]))

(mf/defc options-content*
  {::mf/memo true
   ::mf/private true}
  [{:keys [selected shapes shapes-with-children page-id file-id on-change-section on-expand]}]
  (let [objects              (mf/deref refs/workspace-page-objects)
        permissions          (mf/use-ctx ctx/permissions)

        selected-shapes      (into [] (keep (d/getf objects)) selected)
        first-selected-shape (first selected-shapes)
        shape-parent-frame   (cfh/get-frame objects (:frame-id first-selected-shape))

        options-mode         (mf/deref refs/options-mode-global)

        on-change-tab
        (fn [options-mode]
          (let [options-mode (keyword options-mode)]
            (st/emit! (udw/set-options-mode options-mode))
            (if (= options-mode :inspect)
              (st/emit! :interrupt (dwc/set-workspace-read-only true))
              (st/emit! :interrupt (dwc/set-workspace-read-only false)))))

        design-content
        (mf/html [:> design-menu*
                  {:selected selected
                   :objects objects
                   :page-id page-id
                   :file-id file-id
                   :selected-shapes selected-shapes
                   :shapes-with-children shapes-with-children}])

        inspect-content
        (mf/html [:div {:class (stl/css :element-options :inspect-options)}
                  [:& hrs/right-sidebar {:page-id           page-id
                                         :objects           objects
                                         :file-id           file-id
                                         :frame             shape-parent-frame
                                         :shapes            selected-shapes
                                         :on-change-section on-change-section
                                         :on-expand         on-expand
                                         :from              :workspace}]])

        interactions-content
        (mf/html [:div {:class (stl/css :element-options :interaction-options)}
                  [:& interactions-menu {:shape (first shapes)}]])


        tabs
        (if (:can-edit permissions)
          #js [#js {:label (tr "workspace.options.design")
                    :id "design"
                    :content design-content}

               #js {:label (tr "workspace.options.prototype")
                    :id "prototype"
                    :content interactions-content}

               #js {:label (tr "workspace.options.inspect")
                    :id "inspect"
                    :content inspect-content}]
          #js [#js {:label (tr "workspace.options.inspect")
                    :id "inspect"
                    :content inspect-content}])]

    (mf/with-effect [permissions]
      (when-not (:can-edit permissions)
        (on-change-tab :inspect)))

    [:div {:class (stl/css :tool-window)}
     [:> tab-switcher* {:tabs tabs
                        :default-selected "info"
                        :on-change-tab on-change-tab
                        :selected (name options-mode)
                        :class (stl/css :options-tab-switcher)}]]))

;; TODO: this need optimizations, selected-objects and
;; selected-objects-with-children are derefed always but they only
;; need on multiple selection in majority of cases

(mf/defc options-toolbox*
  {::mf/memo true}
  [{:keys [section selected on-change-section on-expand]}]
  (let [page-id              (mf/use-ctx ctx/current-page-id)
        file-id              (mf/use-ctx ctx/current-file-id)
        shapes               (mf/deref refs/selected-objects)
        shapes-with-children (mf/deref refs/selected-shapes-with-children)]

    [:> options-content* {:shapes shapes
                          :selected selected
                          :shapes-with-children shapes-with-children
                          :file-id file-id
                          :page-id page-id
                          :section section
                          :on-change-section on-change-section
                          :on-expand on-expand}]))

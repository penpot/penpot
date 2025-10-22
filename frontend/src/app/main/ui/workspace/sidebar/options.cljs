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
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.common :as dwc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.inspect.right-sidebar :as hrs]
   [app.main.ui.workspace.sidebar.options.drawing :as drawing]
   [app.main.ui.workspace.sidebar.options.menus.align :refer [align-options*]]
   [app.main.ui.workspace.sidebar.options.menus.bool :refer [bool-options*]]
   [app.main.ui.workspace.sidebar.options.menus.component :refer [component-menu*]]
   [app.main.ui.workspace.sidebar.options.menus.grid-cell :as grid-cell]
   [app.main.ui.workspace.sidebar.options.menus.interactions :refer [interactions-menu]]
   [app.main.ui.workspace.sidebar.options.menus.layout-container :as layout-container]
   [app.main.ui.workspace.sidebar.options.page :as page]
   [app.main.ui.workspace.sidebar.options.shapes.bool :as bool]
   [app.main.ui.workspace.sidebar.options.shapes.circle :as circle]
   [app.main.ui.workspace.sidebar.options.shapes.frame :as frame]
   [app.main.ui.workspace.sidebar.options.shapes.group :as group]
   [app.main.ui.workspace.sidebar.options.shapes.multiple :as multiple]
   [app.main.ui.workspace.sidebar.options.shapes.path :as path]
   [app.main.ui.workspace.sidebar.options.shapes.rect :as rect]
   [app.main.ui.workspace.sidebar.options.shapes.svg-raw :as svg-raw]
   [app.main.ui.workspace.sidebar.options.shapes.text :as text]
   [app.util.i18n :as i18n :refer [tr]]
   [okulary.core :as l]

   [rumext.v2 :as mf]))

;; --- Options

(mf/defc single-shape-options*
  {::mf/private true}
  [{:keys [shape page-id file-id libraries] :rest props}]
  (let [shape-type (dm/get-prop shape :type)
        shape-id   (dm/get-prop shape :id)

        modifiers  (mf/deref refs/workspace-modifiers)
        modifiers  (dm/get-in modifiers [shape-id :modifiers])

        shape      (gsh/transform-shape shape modifiers)
        props      (mf/spread-props props {:shape shape :file-id file-id :page-id page-id})]

    (case shape-type
      :frame   [:> frame/options* props]
      :group   [:> group/options* props]
      :text    [:> text/options* {:shape shape :file-id file-id :page-id page-id :libraries libraries}]
      :rect    [:> rect/options* {:shape shape :file-id file-id :page-id page-id}]
      :circle  [:> circle/options* {:shape shape :file-id file-id :page-id page-id}]
      :path    [:> path/options* {:shape shape :file-id file-id :page-id page-id}]
      :svg-raw [:> svg-raw/options* {:shape shape :file-id file-id :page-id page-id}]
      :bool    [:> bool/options* {:shape shape :file-id file-id :page-id page-id}]
      nil)))

(mf/defc shape-options*
  {::mf/wrap [#(mf/throttle % 100)]
   ::mf/private true}
  [{:keys [shapes shapes-with-children selected page-id file-id libraries]}]
  (if (= 1 (count selected))
    [:> single-shape-options*
     {:page-id page-id
      :file-id file-id
      :libraries libraries
      :shape (first shapes)
      :shapes-with-children shapes-with-children}]
    [:> multiple/options*
     {:shapes-with-children shapes-with-children
      :shapes shapes
      :page-id page-id
      :file-id file-id
      :libraries libraries}]))

(mf/defc specialized-panel*
  {::mf/private true}
  [{:keys [panel]}]
  (when (= (:type panel) :component-swap)
    [:> component-menu* {:shapes (:shapes panel) :is-swap-opened true}]))

(mf/defc design-menu*
  {::mf/private true}
  [{:keys [selected objects page-id file-id shapes]}]
  (let [sp-panel (mf/deref refs/specialized-panel)
        drawing  (mf/deref refs/workspace-drawing)
        edition  (mf/deref refs/selected-edition)

        files
        (mf/deref refs/files)

        libraries
        (mf/with-memo [files file-id]
          (refs/select-libraries files file-id))

        edit-grid?
        (mf/with-memo [objects edition]
          (ctl/grid-layout? objects edition))

        grid-edition
        (mf/deref refs/workspace-grid-edition)

        selected-cells
        (->> (dm/get-in grid-edition [edition :selected])
             (map #(dm/get-in objects [edition :layout-grid-cells %])))

        shapes-with-children
        (mf/with-memo [selected objects shapes]
          (let [xform    (comp (remove nil?)
                               (mapcat #(cfh/get-children-ids objects %)))
                selected (into selected xform selected)]
            (sequence (keep (d/getf objects)) selected)))


        total-selected
        (count selected)]

    [:div {:class (stl/css :element-options :design-options)}
     [:> align-options* {:shapes shapes
                         :objects objects}]
     [:> bool-options* {:total-selected total-selected
                        :shapes shapes
                        :shapes-with-children shapes-with-children}]

     (cond
       (and edit-grid? (d/not-empty? selected-cells))
       [:& grid-cell/options
        {:shape (get objects edition)
         :cells selected-cells}]

       edit-grid?
       [:& layout-container/grid-layout-edition
        {:ids [edition]
         :values (get objects edition)}]

       (some? sp-panel)
       [:> specialized-panel* {:panel sp-panel}]

       (d/not-empty? drawing)
       [:> drawing/drawing-options*
        {:drawing-state drawing}]

       (zero? total-selected)
       [:> page/options*]

       :else
       [:> shape-options*
        {:shapes shapes
         :shapes-with-children shapes-with-children
         :page-id page-id
         :file-id file-id
         :selected selected
         :libraries libraries}])]))

(mf/defc inspect-tab*
  {::mf/private true}
  [{:keys [objects shapes] :as props}]
  (let [frame
        (cfh/get-frame objects (first shapes))

        props
        (mf/spread-props props
                         {:frame frame
                          :from :workspace})]

    [:> hrs/right-sidebar* props]))

(def ^:private options-tabs
  [{:label (tr "workspace.options.design")
    :id "design"}
   {:label (tr "workspace.options.prototype")
    :id "prototype"}
   {:label (tr "workspace.options.inspect")
    :id "inspect"}])

(defn- on-option-tab-change
  [mode]
  (let [mode (keyword mode)]
    (st/emit! (udw/set-options-mode mode))
    (if (= mode :inspect)
      (st/emit! :interrupt (dwc/set-workspace-read-only true))
      (st/emit! :interrupt (dwc/set-workspace-read-only false)))))

(mf/defc options-content*
  {::mf/private true}
  [{:keys [objects selected page-id file-id on-change-section on-expand]}]
  (let [permissions
        (mf/use-ctx ctx/permissions)

        options-mode
        (mf/deref refs/options-mode-global)

        shapes
        (mf/with-memo [selected objects]
          (sequence (keep (d/getf objects)) selected))]

    [:div {:class (stl/css :tool-window)}
     (if (:can-edit permissions)
       [:> tab-switcher* {:tabs options-tabs
                          :on-change on-option-tab-change
                          :selected (name options-mode)
                          :class (stl/css :options-tab-switcher)}
        (case options-mode
          :prototype
          [:div {:class (stl/css :element-options :interaction-options)}
           [:& interactions-menu {:shape (first shapes)}]]

          :inspect
          [:div {:class (stl/css :element-options :inspect-options)}
           [:> inspect-tab* {:page-id page-id
                             :file-id file-id
                             :objects objects
                             :selected selected
                             :shapes shapes
                             :on-change-section on-change-section
                             :on-expand on-expand}]]

          :design
          [:> design-menu* {:selected selected
                            :objects objects
                            :page-id page-id
                            :file-id file-id
                            :shapes shapes}])]

       [:div {:class (stl/css :element-options :inspect-options :read-only)}
        [:> inspect-tab* {:page-id page-id
                          :file-id file-id
                          :objects objects
                          :selected selected
                          :shapes shapes
                          :on-change-section on-change-section
                          :on-expand on-expand}]])]))

(defn- make-page-objects-ref
  [file-id page-id]
  (l/derived #(dsh/lookup-page-objects % file-id page-id) st/state))

(mf/defc options-toolbox*
  {::mf/memo true}
  [{:keys [page-id file-id section selected on-change-section on-expand]}]
  (let [objects-ref
        (mf/with-memo [page-id file-id]
          (make-page-objects-ref file-id page-id))

        objects
        (mf/deref objects-ref)]

    [:> options-content* {:objects objects
                          :selected selected
                          :file-id file-id
                          :page-id page-id
                          :section section
                          :on-change-section on-change-section
                          :on-expand on-expand}]))

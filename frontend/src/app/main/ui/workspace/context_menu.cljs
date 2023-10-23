;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.uuid :as uuid]
   [app.main.data.events :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.components.shape-icon-refactor :as sic]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr] :as i18n]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def menu-ref
  (l/derived :context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  [{:keys [title shortcut on-click on-pointer-enter on-pointer-leave on-unmount children selected? icon] :as props}]
  (let [submenu-ref (mf/use-ref nil)
        hovering? (mf/use-ref false)
        new-css-system (mf/use-ctx ctx/new-css-system)
        on-pointer-enter
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? true)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (dom/set-css-property! submenu-node "display" "block")))
           (when on-pointer-enter (on-pointer-enter))))

        on-pointer-leave
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? false)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (timers/schedule
                200
                #(when-not (mf/ref-val hovering?)
                   (dom/set-css-property! submenu-node "display" "none")))))
           (when on-pointer-leave (on-pointer-leave))))

        set-dom-node
        (mf/use-callback
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node))
               (dom/set-css-property! submenu-node "top" (str (.-offsetTop dom) "px"))))))]

    (mf/use-effect
     (mf/deps on-unmount)
     (constantly on-unmount))

    (if icon
      [:li {:class (if new-css-system
                     (dom/classnames (css :icon-menu-item) true)
                     (dom/classnames :icon-menu-item true))
            :ref set-dom-node
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span
        {:class (if new-css-system
                  (dom/classnames (css :icon-wrapper) true)
                  (dom/classnames :icon-wrapper true))}
        (if selected? [:span {:class (if new-css-system
                                       (dom/classnames (css :selected-icon) true)
                                       (dom/classnames :selected-icon true))}
                       (if new-css-system
                         i/tick-refactor
                         i/tick)]
            [:span {:class (if new-css-system
                             (dom/classnames (css :selected-icon) true)
                             (dom/classnames :selected-icon true))}])
        [:span {:class (if new-css-system
                         (dom/classnames (css :shape-icon) true)
                         (dom/classnames :shape-icon true))} icon]]
       [:span {:class (if new-css-system
                        (dom/classnames (css :title) true)
                        (dom/classnames :title true))} title]]
      [:li {:class (dom/classnames (css :context-menu-item) new-css-system)
            :ref set-dom-node
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span {:class (if new-css-system
                        (dom/classnames (css :title) true)
                        (dom/classnames :title true))} title]
       (when shortcut
         [:span   {:class (if new-css-system
                            (dom/classnames (css :shortcut) true)
                            (dom/classnames :shortcut true))}
          (if new-css-system
            (for [sc (scd/split-sc shortcut)]
              [:span {:class (dom/classnames (css :shortcut-key) true)} sc])
            (or shortcut ""))])

       (when (> (count children) 1)
         (if new-css-system
           [:span {:class (dom/classnames (css :submenu-icon) true)} i/arrow-refactor]
           [:span.submenu-icon i/arrow-slide]))

       (when (> (count children) 1)
         [:ul
          {:class (if new-css-system
                    (dom/classnames (css :workspace-context-submenu) true)
                    (dom/classnames :workspace-context-menu true))
           :ref submenu-ref
           :style {:display "none" :left 250}
           :on-context-menu prevent-default}
          children])])))
(mf/defc menu-separator
  []
  (let [new-css-system (mf/use-ctx ctx/new-css-system)]
    [:li {:class (if new-css-system
                 (dom/classnames (css :separator) true)
                 (dom/classnames :separator true))}]))

(mf/defc context-menu-edit
  [_]
  (let [do-copy           #(st/emit! (dw/copy-selected))
        do-cut            #(st/emit! (dw/copy-selected)
                                     (dw/delete-selected))
        do-paste          #(st/emit! dw/paste)
        do-duplicate      #(st/emit! (dw/duplicate-selected true))]
    [:*
     [:& menu-entry {:title (tr "workspace.shape.menu.copy")
                     :shortcut (sc/get-tooltip :copy)
                     :on-click do-copy}]
     [:& menu-entry {:title (tr "workspace.shape.menu.cut")
                     :shortcut (sc/get-tooltip :cut)
                     :on-click do-cut}]
     [:& menu-entry {:title (tr "workspace.shape.menu.paste")
                     :shortcut (sc/get-tooltip :paste)
                     :on-click do-paste}]
     [:& menu-entry {:title (tr "workspace.shape.menu.duplicate")
                     :shortcut (sc/get-tooltip :duplicate)
                     :on-click do-duplicate}]

     [:& menu-separator]]))

(mf/defc context-menu-layer-position
  [{:keys [shapes]}]
  (let [do-bring-forward  (mf/use-fn #(st/emit! (dw/vertical-order-selected :up)))
        do-bring-to-front (mf/use-fn #(st/emit! (dw/vertical-order-selected :top)))
        do-send-backward  (mf/use-fn #(st/emit! (dw/vertical-order-selected :down)))
        do-send-to-back   (mf/use-fn #(st/emit! (dw/vertical-order-selected :bottom)))

        select-shapes     (fn [id] #(st/emit! (dws/select-shape id)))
        on-pointer-enter  (fn [id] #(st/emit! (dw/highlight-shape id)))
        on-pointer-leave  (fn [id] #(st/emit! (dw/dehighlight-shape id)))
        on-unmount        (fn [id] #(st/emit! (dw/dehighlight-shape id)))

        ;; NOTE: we use deref instead of mf/deref on objects because
        ;; we really don't want rerender on object changes
        hover-ids         (deref refs/current-hover-ids)
        objects           (deref refs/workspace-page-objects)
        hover-objs        (into [] (keep (d/getf objects)) hover-ids)]

    [:*
     (when (> (count hover-objs) 1)
       [:& menu-entry {:title (tr "workspace.shape.menu.select-layer")}
        (for [object hover-objs]
          [:& menu-entry {:title (:name object)
                          :key (dm/str (:id object))
                          :selected? (some #(= object %) shapes)
                          :on-click (select-shapes (:id object))
                          :on-pointer-enter (on-pointer-enter (:id object))
                          :on-pointer-leave (on-pointer-leave (:id object))
                          :on-unmount (on-unmount (:id object))
                          :icon (sic/element-icon-refactor {:shape object})}])])
     [:& menu-entry {:title (tr "workspace.shape.menu.forward")
                     :shortcut (sc/get-tooltip :bring-forward)
                     :on-click do-bring-forward}]
     [:& menu-entry {:title (tr "workspace.shape.menu.front")
                     :shortcut (sc/get-tooltip :bring-front)
                     :on-click do-bring-to-front}]
     [:& menu-entry {:title (tr "workspace.shape.menu.backward")
                     :shortcut (sc/get-tooltip :bring-backward)
                     :on-click do-send-backward}]
     [:& menu-entry {:title (tr "workspace.shape.menu.back")
                     :shortcut (sc/get-tooltip :bring-back)
                     :on-click do-send-to-back}]

     [:& menu-separator]]))

(mf/defc context-menu-flip
  []
  (let [do-flip-vertical #(st/emit! (dw/flip-vertical-selected))
        do-flip-horizontal #(st/emit! (dw/flip-horizontal-selected))]
    [:*
     [:& menu-entry {:title (tr "workspace.shape.menu.flip-vertical")
                     :shortcut (sc/get-tooltip :flip-vertical)
                     :on-click do-flip-vertical}]

     [:& menu-entry {:title (tr "workspace.shape.menu.flip-horizontal")
                     :shortcut (sc/get-tooltip :flip-horizontal)
                     :on-click do-flip-horizontal}]
     [:& menu-separator]]))

(mf/defc context-menu-thumbnail
  [{:keys [shapes]}]
  (let [single?    (= (count shapes) 1)
        has-frame? (some cph/frame-shape? shapes)
        do-toggle-thumbnail #(st/emit! (dw/toggle-file-thumbnail-selected))]
    (when (and single? has-frame?)
      [:*
       (if (every? :use-for-thumbnail shapes)
         [:& menu-entry {:title (tr "workspace.shape.menu.thumbnail-remove")
                         :on-click do-toggle-thumbnail}]
         [:& menu-entry {:title (tr "workspace.shape.menu.thumbnail-set")
                         :shortcut (sc/get-tooltip :thumbnail-set)
                         :on-click do-toggle-thumbnail}])
       [:& menu-separator]])))

(mf/defc context-menu-group
  [{:keys [shapes]}]

  (let [multiple? (> (count shapes) 1)
        single?   (= (count shapes) 1)
        do-create-artboard-from-selection #(st/emit! (dwsh/create-artboard-from-selection))

        has-frame? (->> shapes (d/seek cph/frame-shape?))
        has-group? (->> shapes (d/seek cph/group-shape?))
        has-bool? (->> shapes (d/seek cph/bool-shape?))
        has-mask? (->> shapes (d/seek :masked-group))

        is-group? (and single? has-group?)
        is-bool? (and single? has-bool?)

        do-create-group #(st/emit! dw/group-selected)
        do-mask-group   #(st/emit! dw/mask-group)
        do-remove-group #(st/emit! dw/ungroup-selected)
        do-unmask-group #(st/emit! dw/unmask-group)]

    [:*
     (when (or has-bool? has-group? has-mask? has-frame?)
       [:& menu-entry {:title (tr "workspace.shape.menu.ungroup")
                       :shortcut (sc/get-tooltip :ungroup)
                       :on-click do-remove-group}])

     [:& menu-entry {:title (tr "workspace.shape.menu.group")
                     :shortcut (sc/get-tooltip :group)
                     :on-click do-create-group}]

     (when (or multiple? (and is-group? (not has-mask?)) is-bool?)
       [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                       :shortcut (sc/get-tooltip :mask)
                       :on-click do-mask-group}])

     (when has-mask?
       [:& menu-entry {:title (tr "workspace.shape.menu.unmask")
                       :shortcut (sc/get-tooltip :unmask)
                       :on-click do-unmask-group}])

     [:& menu-entry {:title (tr "workspace.shape.menu.create-artboard-from-selection")
                     :shortcut (sc/get-tooltip :artboard-selection)
                     :on-click do-create-artboard-from-selection}]
     [:& menu-separator]]))

(mf/defc context-focus-mode-menu
  [{:keys []}]
  (let [focus (mf/deref refs/workspace-focus-selected)
        do-toggle-focus-mode #(st/emit! (dw/toggle-focus-mode))]

    [:& menu-entry {:title (if (empty? focus)
                             (tr "workspace.focus.focus-on")
                             (tr "workspace.focus.focus-off"))
                    :shortcut (sc/get-tooltip :toggle-focus-mode)
                    :on-click do-toggle-focus-mode}]))

(mf/defc context-menu-path
  [{:keys [shapes disable-flatten? disable-booleans?]}]
  (let [multiple?            (> (count shapes) 1)
        single?              (= (count shapes) 1)

        has-group?           (->> shapes (d/seek cph/group-shape?))
        has-bool?            (->> shapes (d/seek cph/bool-shape?))
        has-frame?           (->> shapes (d/seek cph/frame-shape?))
        has-path?            (->> shapes (d/seek cph/path-shape?))

        is-group?            (and single? has-group?)
        is-bool?             (and single? has-bool?)
        is-frame?            (and single? has-frame?)

        do-start-editing     (fn [] (timers/schedule #(st/emit! (dw/start-editing-selected))))
        do-transform-to-path #(st/emit! (dw/convert-selected-to-path))

        make-do-bool
        (fn [bool-type]
          #(cond
             multiple?
             (st/emit! (dw/create-bool bool-type))

             is-group?
             (st/emit! (dw/group-to-bool (-> shapes first :id) bool-type))

             is-bool?
             (st/emit! (dw/change-bool-type (-> shapes first :id) bool-type))))]
    [:*
     (when (and single? (not is-frame?))
       [:& menu-entry {:title (tr "workspace.shape.menu.edit")
                       :shortcut (sc/get-tooltip :start-editing)
                       :on-click do-start-editing}])

     (when-not (or disable-flatten? has-frame? has-path?)
       [:& menu-entry {:title (tr "workspace.shape.menu.transform-to-path")
                       :on-click do-transform-to-path}])

     (when (and (not disable-booleans?)
                (or multiple? (and single? (or is-group? is-bool?))))
       [:& menu-entry {:title (tr "workspace.shape.menu.path")}
        [:& menu-entry {:title (tr "workspace.shape.menu.union")
                        :shortcut (sc/get-tooltip :bool-union)
                        :on-click (make-do-bool :union)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.difference")
                        :shortcut (sc/get-tooltip :bool-difference)
                        :on-click (make-do-bool :difference)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.intersection")
                        :shortcut (sc/get-tooltip :bool-intersection)
                        :on-click (make-do-bool :intersection)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.exclude")
                        :shortcut (sc/get-tooltip :bool-exclude)
                        :on-click (make-do-bool :exclude)}]

        (when (and single? is-bool? (not disable-flatten?))
          [:*
           [:& menu-separator]
           [:& menu-entry {:title (tr "workspace.shape.menu.flatten")
                           :on-click do-transform-to-path}]])])]))

(mf/defc context-menu-layer-options
  [{:keys [shapes]}]
  (let [ids (mapv :id shapes)
        do-show-shape #(st/emit! (dw/update-shape-flags ids {:hidden false}))
        do-hide-shape #(st/emit! (dw/update-shape-flags ids {:hidden true}))
        do-lock-shape #(st/emit! (dw/update-shape-flags ids {:blocked true}))
        do-unlock-shape #(st/emit! (dw/update-shape-flags ids {:blocked false}))]
    [:*
     (if (every? :hidden shapes)
       [:& menu-entry {:title (tr "workspace.shape.menu.show")
                       :shortcut (sc/get-tooltip :toggle-visibility)
                       :on-click do-show-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.hide")
                       :shortcut (sc/get-tooltip :toggle-visibility)
                       :on-click do-hide-shape}])

     (if (every? :blocked shapes)
       [:& menu-entry {:title (tr "workspace.shape.menu.unlock")
                       :shortcut (sc/get-tooltip :toggle-lock)
                       :on-click do-unlock-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.lock")
                       :shortcut (sc/get-tooltip :toggle-lock)
                       :on-click do-lock-shape}])]))

(mf/defc context-menu-prototype
  [{:keys [shapes]}]
  (let [options         (mf/deref refs/workspace-page-options)
        options-mode    (mf/deref refs/options-mode-global)
        do-add-flow     #(st/emit! (dwi/add-flow-selected-frame))
        do-remove-flow  #(st/emit! (dwi/remove-flow (:id %)))
        flows           (:flows options)

        prototype?      (= options-mode :prototype)
        single?         (= (count shapes) 1)
        has-frame?      (->> shapes (d/seek cph/frame-shape?))
        is-frame?       (and single? has-frame?)]

    (when (and prototype? is-frame?)
      (let [flow (ctp/get-frame-flow flows (-> shapes first :id))]
        (if (some? flow)
          [:& menu-entry {:title (tr "workspace.shape.menu.delete-flow-start")
                          :on-click (do-remove-flow flow)}]

          [:& menu-entry {:title (tr "workspace.shape.menu.flow-start")
                          :on-click do-add-flow}])))))
(mf/defc context-menu-flex
  [{:keys [shapes]}]
  (let [single?            (= (count shapes) 1)
        has-frame?         (->> shapes (d/seek cph/frame-shape?))
        is-flex-container? (and single? has-frame? (= :flex (:layout (first shapes))))
        ids                (->> shapes (map :id))

        add-layout
        (fn [type]
          (if (and single? has-frame?)
            (st/emit! (dwsl/create-layout-from-id (first ids) type true))
            (st/emit! (dwsl/create-layout-from-selection type))))

        remove-flex
        (fn []
          (st/emit! (dwsl/remove-layout ids)))]

    [:*
     (when (not is-flex-container?)
       [:div
        [:& menu-separator]
        [:& menu-entry {:title (tr "workspace.shape.menu.add-flex")
                        :shortcut (sc/get-tooltip :toggle-layout-flex)
                        :on-click #(add-layout :flex)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.add-grid")
                        :shortcut (sc/get-tooltip :toggle-layout-grid)
                        :on-click #(add-layout :grid)}]])
     (when  is-flex-container?
       [:div
        [:& menu-separator]
        [:& menu-entry {:title (tr "workspace.shape.menu.remove-flex")
                        :shortcut (sc/get-tooltip :toggle-layout-flex)
                        :on-click remove-flex}]])]))

(mf/defc context-menu-component
  [{:keys [shapes]}]
  (let [components-v2              (features/use-feature "components/v2")
        single?                    (= (count shapes) 1)
        objects                    (deref refs/workspace-page-objects)
        any-in-copy?               (some true? (map #(ctn/has-any-copy-parent? objects %) shapes))
        heads                      (filter ctk/instance-head? shapes)
        components-menu-entries    (cmm/generate-components-menu-entries heads components-v2)
        do-add-component           #(st/emit! (dwl/add-component))
        do-add-multiple-components #(st/emit! (dwl/add-multiple-components))]
    [:*
     (when-not any-in-copy? ;; We don't want to change the structure of component copies
       [:*
        [:& menu-separator]

        [:& menu-entry {:title (tr "workspace.shape.menu.create-component")
                        :shortcut (sc/get-tooltip :create-component)
                        :on-click do-add-component}]
        (when (not single?)
          [:& menu-entry {:title (tr "workspace.shape.menu.create-multiple-components")
                          :on-click do-add-multiple-components}])])

     (when (seq components-menu-entries)
       [:*
        [:& menu-separator]
        (for [entry components-menu-entries :when (not (nil? entry))]
          [:& menu-entry {:key (uuid/next)
                          :title (tr (:msg entry))
                          :shortcut (when (contains? entry :shortcut) (sc/get-tooltip (:shortcut entry)))
                          :on-click (:action entry)}])])]))

(mf/defc context-menu-delete
  []
  (let [do-delete #(st/emit! (dw/delete-selected))]
    [:*
     [:& menu-separator]
     [:& menu-entry {:title (tr "workspace.shape.menu.delete")
                    :shortcut (sc/get-tooltip :delete)
                    :on-click do-delete}]]))

(mf/defc shape-context-menu
  {::mf/wrap [mf/memo]}
  [{:keys [mdata] :as props}]
  (let [{:keys [disable-booleans? disable-flatten?]} mdata
        shapes (mf/deref refs/selected-objects)
        props #js {:shapes shapes
                   :disable-booleans? disable-booleans?
                   :disable-flatten? disable-flatten?}]
    (when-not (empty? shapes)
      [:*
       [:> context-menu-edit props]
       [:> context-menu-layer-position props]
       [:> context-menu-flip props]
       [:> context-menu-thumbnail props]
       [:> context-menu-group props]
       [:> context-focus-mode-menu props]
       [:> context-menu-path props]
       [:> context-menu-layer-options props]
       [:> context-menu-prototype props]
       [:> context-menu-flex props]
       [:> context-menu-component props]
       [:> context-menu-delete props]])))

(mf/defc page-item-context-menu
  [{:keys [mdata] :as props}]
  (let [page (:page mdata)
        deletable? (:deletable? mdata)
        id (:id page)
        delete-fn #(st/emit! (dw/delete-page id))
        do-delete #(st/emit! (modal/show
                              {:type :confirm
                               :title (tr "modals.delete-page.title")
                               :message (tr "modals.delete-page.body")
                               :on-accept delete-fn}))
        do-duplicate #(st/emit! (dw/duplicate-page id))
        do-rename #(st/emit! (dw/start-rename-page-item id))]

    [:*
     (when deletable?
       [:& menu-entry {:title (tr "workspace.assets.delete")
                       :on-click do-delete}])

     [:& menu-entry {:title (tr "workspace.assets.rename")
                     :on-click do-rename}]
     [:& menu-entry {:title (tr "workspace.assets.duplicate")
                     :on-click do-duplicate}]]))

(mf/defc viewport-context-menu
  []
  (let [focus      (mf/deref refs/workspace-focus-selected)
        do-paste   #(st/emit! dw/paste)
        do-hide-ui #(st/emit! (-> (dw/toggle-layout-flag :hide-ui)
                                  (vary-meta assoc ::ev/origin "workspace-context-menu")))
        do-toggle-focus-mode #(st/emit! (dw/toggle-focus-mode))]
    [:*
     [:& menu-entry {:title (tr "workspace.shape.menu.paste")
                     :shortcut (sc/get-tooltip :paste)
                     :on-click do-paste}]
     [:& menu-entry {:title (tr "workspace.shape.menu.hide-ui")
                     :shortcut (sc/get-tooltip :hide-ui)
                     :on-click do-hide-ui}]

     (when (d/not-empty? focus)
       [:& menu-entry {:title (tr "workspace.focus.focus-off")
                       :shortcut (sc/get-tooltip :toggle-focus-mode)
                       :on-click do-toggle-focus-mode}])]))

(mf/defc context-menu
  []
  (let [mdata          (mf/deref menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)
        new-css-system (mf/use-ctx ctx/new-css-system)]

    (mf/use-effect
     (mf/deps mdata)
     #(let [dropdown (mf/ref-val dropdown-ref)]
        (when dropdown
          (let [bounding-rect (dom/get-bounding-rect dropdown)
                window-size (dom/get-window-size)
                delta-x (max (- (+ (:right bounding-rect) 250) (:width window-size)) 0)
                delta-y (max (- (:bottom bounding-rect) (:height window-size)) 0)
                new-style (str "top: " (- top delta-y) "px; "
                               "left: " (- left delta-x) "px;")]
            (when (or (> delta-x 0) (> delta-y 0))
              (.setAttribute ^js dropdown "style" new-style))))))

    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! dw/hide-context-menu)}
     [:ul
      {:class (if new-css-system
                (dom/classnames (css :workspace-context-menu) true)
                (dom/classnames :workspace-context-menu true))
       :ref dropdown-ref
       :style {:top top :left left}
       :on-context-menu prevent-default}

      (case (:kind mdata)
        :shape [:& shape-context-menu {:mdata mdata}]
        :page [:& page-item-context-menu {:mdata mdata}]
        [:& viewport-context-menu {:mdata mdata}])]]))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require
   [app.common.data :as d]
   [app.common.types.page-options :as cto]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr] :as i18n]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [rumext.alpha :as mf]))

(def menu-ref
  (l/derived :context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  [{:keys [title shortcut on-click children] :as props}]
  (let [submenu-ref (mf/use-ref nil)
        hovering? (mf/use-ref false)

        on-pointer-enter
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? true)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (dom/set-css-property! submenu-node "display" "block")))))

        on-pointer-leave
        (mf/use-callback
         (fn []
           (mf/set-ref-val! hovering? false)
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (some? submenu-node)
               (timers/schedule
                200
                #(when-not (mf/ref-val hovering?)
                   (dom/set-css-property! submenu-node "display" "none")))))))

        set-dom-node
        (mf/use-callback
         (fn [dom]
           (let [submenu-node (mf/ref-val submenu-ref)]
             (when (and (some? dom) (some? submenu-node))
               (dom/set-css-property! submenu-node "top" (str (.-offsetTop dom) "px"))))))]

    [:li {:ref set-dom-node
          :on-click on-click
          :on-pointer-enter on-pointer-enter
          :on-pointer-leave on-pointer-leave}
     [:span.title title]
     [:span.shortcut (or shortcut "")]

     (when (> (count children) 1)
       [:span.submenu-icon i/arrow-slide])

     (when (> (count children) 1)
       [:ul.workspace-context-menu
        {:ref submenu-ref
         :style {:display "none" :left 250}
         :on-context-menu prevent-default}
        children])]))

(mf/defc menu-separator
  []
  [:li.separator])

(mf/defc context-menu-edit
  []
  (let [do-copy      (st/emitf (dw/copy-selected))
        do-cut       (st/emitf (dw/copy-selected) dw/delete-selected)
        do-paste     (st/emitf dw/paste)
        do-duplicate (st/emitf (dw/duplicate-selected false))]
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
  []
  (let [do-bring-forward  (st/emitf (dw/vertical-order-selected :up))
        do-bring-to-front (st/emitf (dw/vertical-order-selected :top))
        do-send-backward  (st/emitf (dw/vertical-order-selected :down))
        do-send-to-back   (st/emitf (dw/vertical-order-selected :bottom))]
    [:*
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
  (let [do-flip-vertical (st/emitf (dw/flip-vertical-selected))
        do-flip-horizontal (st/emitf (dw/flip-horizontal-selected))]
    [:*
     [:& menu-entry {:title (tr "workspace.shape.menu.flip-vertical")
                     :shortcut (sc/get-tooltip :flip-vertical)
                     :on-click do-flip-vertical}]

     [:& menu-entry {:title (tr "workspace.shape.menu.flip-horizontal")
                     :shortcut (sc/get-tooltip :flip-horizontal)
                     :on-click do-flip-horizontal}]
     [:& menu-separator]]))

(mf/defc context-menu-group
  [{:keys [shapes]}]

  (let [multiple? (> (count shapes) 1)
        single?   (= (count shapes) 1)
        do-create-artboard-from-selection (st/emitf (dw/create-artboard-from-selection))

        has-group? (->> shapes (d/seek #(= :group (:type %))))
        has-bool? (->> shapes (d/seek #(= :bool (:type %))))
        has-mask? (->> shapes (d/seek :masked-group?))
        has-frame? (->> shapes (d/seek #(= :frame (:type %))))

        is-group? (and single? has-group?)
        is-bool? (and single? has-bool?)

        do-create-group (st/emitf dw/group-selected)
        do-mask-group   (st/emitf dw/mask-group)
        do-remove-group (st/emitf dw/ungroup-selected)
        do-unmask-group (st/emitf dw/unmask-group)]

    [:*
     (when (or has-bool? has-group? has-mask?)
       [:& menu-entry {:title (tr "workspace.shape.menu.ungroup")
                       :shortcut (sc/get-tooltip :ungroup)
                       :on-click do-remove-group}])

     (when (not has-frame?)
       [:& menu-entry {:title (tr "workspace.shape.menu.group")
                       :shortcut (sc/get-tooltip :group)
                       :on-click do-create-group}])

     (when (or multiple? (and is-group? (not has-mask?)) is-bool?)
       [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                       :shortcut (sc/get-tooltip :mask)
                       :on-click do-mask-group}])

     (when has-mask?
       [:& menu-entry {:title (tr "workspace.shape.menu.unmask")
                       :shortcut (sc/get-tooltip :unmask)
                       :on-click do-unmask-group}])

     (when (not has-frame?)
       [:*
         [:& menu-entry {:title (tr "workspace.shape.menu.create-artboard-from-selection")
                         :shortcut (sc/get-tooltip :create-artboard-from-selection)
                         :on-click do-create-artboard-from-selection}]
         [:& menu-separator]])]))

(mf/defc context-menu-path
  [{:keys [shapes disable-flatten? disable-booleans?]}]
  (let [multiple? (> (count shapes) 1)
        single?   (= (count shapes) 1)

        has-group? (->> shapes (d/seek #(= :group (:type %))))
        has-bool? (->> shapes (d/seek #(= :bool (:type %))))
        has-frame? (->> shapes (d/seek #(= :frame (:type %))))

        is-group? (and single? has-group?)
        is-bool? (and single? has-bool?)
        is-frame? (and single? has-frame?)

        do-start-editing #(timers/schedule (st/emitf (dw/start-editing-selected)))
        do-transform-to-path (st/emitf (dw/convert-selected-to-path))

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

     (when-not (or disable-flatten? has-frame?)
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
        do-show-shape (st/emitf (dw/update-shape-flags ids {:hidden false}))
        do-hide-shape (st/emitf (dw/update-shape-flags ids {:hidden true}))
        do-lock-shape (st/emitf (dw/update-shape-flags ids {:blocked true}))
        do-unlock-shape (st/emitf (dw/update-shape-flags ids {:blocked false}))]
    [:*
     (if (every? :hidden shapes)
       [:& menu-entry {:title (tr "workspace.shape.menu.show")
                       :on-click do-show-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.hide")
                       :on-click do-hide-shape}])

     (if (every? :blocked shapes)
       [:& menu-entry {:title (tr "workspace.shape.menu.unlock")
                       :on-click do-unlock-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.lock")
                       :on-click do-lock-shape}])]))

(mf/defc context-menu-prototype
  [{:keys [shapes]}]
  (let [options         (mf/deref refs/workspace-page-options)
        options-mode    (mf/deref refs/options-mode)
        do-add-flow     (st/emitf (dwi/add-flow-selected-frame))
        do-remove-flow  #(st/emitf (dwi/remove-flow (:id %)))
        flows           (:flows options)

        prototype?      (= options-mode :prototype)
        single?         (= (count shapes) 1)
        has-frame?      (->> shapes (d/seek #(= :frame (:type %))))
        is-frame?       (and single? has-frame?)]

    (when (and prototype? is-frame?)
      (let [flow (cto/get-frame-flow flows (-> shapes first :id))]
        (if (some? flow)
          [:& menu-entry {:title (tr "workspace.shape.menu.delete-flow-start")
                          :on-click (do-remove-flow flow)}]

          [:& menu-entry {:title (tr "workspace.shape.menu.flow-start")
                          :on-click do-add-flow}])))))

(mf/defc context-menu-component
  [{:keys [shapes]}]
  (let [single?   (= (count shapes) 1)

        has-frame? (->> shapes (d/seek #(= :frame (:type %))))
        has-component? (some true? (map #(contains? % :component-id) shapes))
        is-component? (and single? (-> shapes first :component-id some?))

        shape-id (->> shapes first :id)
        component-id (->> shapes first :component-id)
        component-file (-> shapes first :component-file)

        current-file-id (mf/use-ctx ctx/current-file-id)
        local-component? (= component-file current-file-id)

        do-add-component (st/emitf (dwl/add-component))
        do-detach-component (st/emitf (dwl/detach-component shape-id))
        do-detach-component-in-bulk (st/emitf dwl/detach-selected-components)
        do-reset-component (st/emitf (dwl/reset-component shape-id))
        do-show-component (st/emitf (dw/go-to-component component-id))
        do-navigate-component-file (st/emitf (dwl/nav-to-component-file component-file))
        do-update-component (st/emitf (dwl/update-component-sync shape-id component-file))

        do-update-remote-component
        (st/emitf (modal/show
                   {:type :confirm
                    :message ""
                    :title (tr "modals.update-remote-component.message")
                    :hint (tr "modals.update-remote-component.hint")
                    :cancel-label (tr "modals.update-remote-component.cancel")
                    :accept-label (tr "modals.update-remote-component.accept")
                    :accept-style :primary
                    :on-accept do-update-component}))]
    [:*
     (when (and (not has-frame?) (not is-component?))
       [:*
        [:& menu-separator]
        [:& menu-entry {:title (tr "workspace.shape.menu.create-component")
                        :shortcut (sc/get-tooltip :create-component)
                        :on-click do-add-component}]
        (when has-component?
          [:& menu-entry {:title (tr "workspace.shape.menu.detach-instances-in-bulk")
                          :shortcut (sc/get-tooltip :detach-component)
                          :on-click do-detach-component-in-bulk}])])

     (when is-component?
       ;; WARNING: this menu is the same as the context menu at the sidebar.
       ;;          If you change it, you must change equally the file
       ;;          app/main/ui/workspace/sidebar/options/menus/component.cljs

       [:*
        [:& menu-separator]
        [:& menu-entry {:title (tr "workspace.shape.menu.detach-instance")
                        :shortcut (sc/get-tooltip :detach-component)
                        :on-click do-detach-component}]
        [:& menu-entry {:title (tr "workspace.shape.menu.reset-overrides")
                        :on-click do-reset-component}]


        (if local-component?
          [:*
           [:& menu-entry {:title (tr "workspace.shape.menu.update-main")
                           :on-click do-update-component}]
           [:& menu-entry {:title (tr "workspace.shape.menu.show-main")
                           :on-click do-show-component}]]

          [:*
           [:& menu-entry {:title (tr "workspace.shape.menu.go-main")
                           :on-click do-navigate-component-file}]
           [:& menu-entry {:title (tr "workspace.shape.menu.update-main")
                           :on-click do-update-remote-component}]])])
     [:& menu-separator]]))

(mf/defc context-menu-delete
  []
  (let [do-delete (st/emitf dw/delete-selected)]
    [:& menu-entry {:title (tr "workspace.shape.menu.delete")
                    :shortcut (sc/get-tooltip :delete)
                    :on-click do-delete}]))

(mf/defc shape-context-menu
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
       [:> context-menu-group props]
       [:> context-menu-path props]
       [:> context-menu-layer-options props]
       [:> context-menu-prototype props]
       [:> context-menu-component props]
       [:> context-menu-delete props]])))

(mf/defc viewport-context-menu
  []
  (let [do-paste (st/emitf dw/paste)]
    [:& menu-entry {:title (tr "workspace.shape.menu.paste")
                    :shortcut (sc/get-tooltip :paste)
                    :on-click do-paste}]))

(mf/defc context-menu
  []
  (let [mdata (mf/deref menu-ref)
        top (- (get-in mdata [:position :y]) 20)
        left (get-in mdata [:position :x])
        dropdown-ref (mf/use-ref)]

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
                  :on-close (st/emitf dw/hide-context-menu)}
     [:ul.workspace-context-menu
      {:ref dropdown-ref
       :style {:top top :left left}
       :on-context-menu prevent-default}

      (if (contains? mdata :selected)
        [:& shape-context-menu {:mdata mdata}]
        [:& viewport-context-menu {:mdata mdata}])]]))




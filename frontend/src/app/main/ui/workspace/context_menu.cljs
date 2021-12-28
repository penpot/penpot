;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require
   [app.common.types.page-options :as cto]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.interactions :as dwi]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.undo :as dwu]
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

(mf/defc shape-context-menu
  [{:keys [mdata] :as props}]
  (let [{:keys [shape selected disable-booleans? disable-flatten?]} mdata
        {:keys [id type]} shape

        single? (= (count selected) 1)
        multiple? (> (count selected) 1)
        editable-shape? (#{:group :text :path} type)

        is-group? (and (some? shape) (= :group type))
        is-bool?  (and (some? shape) (= :bool type))

        options (mf/deref refs/workspace-page-options)
        selected-objects (mf/deref refs/selected-objects)
        flows   (:flows options)

        has-group? (some #(= :group (:type %)) selected-objects)
        has-bool? (some #(= :bool (:type %)) selected-objects)
        has-mask-group? (some #(:masked-group? %) selected-objects)

        options-mode (mf/deref refs/options-mode)

        set-bool
        (fn [bool-type]
          #(cond
             (> (count selected) 1)
             (st/emit! (dw/create-bool bool-type))

             (and (= (count selected) 1) is-group?)
             (st/emit! (dw/group-to-bool (:id shape) bool-type))

             (and (= (count selected) 1) is-bool?)
             (st/emit! (dw/change-bool-type (:id shape) bool-type))))

        current-file-id (mf/use-ctx ctx/current-file-id)

        do-duplicate (st/emitf (dw/duplicate-selected false))
        do-delete (st/emitf dw/delete-selected)
        do-copy (st/emitf (dw/copy-selected))
        do-cut (st/emitf (dw/copy-selected) dw/delete-selected)
        do-paste (st/emitf dw/paste)
        do-bring-forward (st/emitf (dw/vertical-order-selected :up))
        do-bring-to-front (st/emitf (dw/vertical-order-selected :top))
        do-send-backward (st/emitf (dw/vertical-order-selected :down))
        do-send-to-back (st/emitf (dw/vertical-order-selected :bottom))
        do-show-shape (st/emitf (dw/update-shape-flags id {:hidden false}))
        do-hide-shape (st/emitf (dw/update-shape-flags id {:hidden true}))
        do-lock-shape (st/emitf (dw/update-shape-flags id {:blocked true}))
        do-unlock-shape (st/emitf (dw/update-shape-flags id {:blocked false}))
        do-add-flow (st/emitf (dwi/add-flow-selected-frame))
        do-remove-flow #(st/emitf (dwi/remove-flow (:id %)))
        do-create-group (st/emitf dw/group-selected)
        do-remove-group (st/emitf dw/ungroup-selected)
        do-mask-group (st/emitf dw/mask-group)
        do-unmask-group (st/emitf dw/unmask-group)
        do-flip-vertical (st/emitf (dw/flip-vertical-selected))
        do-flip-horizontal (st/emitf (dw/flip-horizontal-selected))
        do-add-component (st/emitf (dwl/add-component))
        do-detach-component (st/emitf (dwl/detach-component id))
        do-reset-component (st/emitf (dwl/reset-component id))
        do-start-editing (fn []
                           ;; We defer the execution so the mouse event won't close the editor
                           (timers/schedule #(st/emit! (dw/start-editing-selected))))
        do-update-component (st/emitf
                              (dwu/start-undo-transaction)
                              (dwl/update-component id)
                              (dwl/sync-file current-file-id (:component-file shape))
                              (dwu/commit-undo-transaction))
        confirm-update-remote-component (st/emitf
                                          (dwl/update-component id)
                                          (dwl/sync-file current-file-id
                                                         (:component-file shape))
                                          (dwl/sync-file (:component-file shape)
                                                         (:component-file shape)))
        do-update-remote-component (st/emitf (modal/show
                                                {:type :confirm
                                                 :message ""
                                                 :title (tr "modals.update-remote-component.message")
                                                 :hint (tr "modals.update-remote-component.hint")
                                                 :cancel-label (tr "modals.update-remote-component.cancel")
                                                 :accept-label (tr "modals.update-remote-component.accept")
                                                 :accept-style :primary
                                                 :on-accept confirm-update-remote-component}))
        do-show-component (st/emitf (dw/go-to-layout :assets))
        do-navigate-component-file (st/emitf (dwl/nav-to-component-file
                                              (:component-file shape)))

        do-transform-to-path (st/emitf (dw/convert-selected-to-path))
        do-flatten (st/emitf (dw/convert-selected-to-path))]
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
     [:& menu-separator]
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
     [:& menu-separator]
     [:& menu-entry {:title (tr "workspace.shape.menu.flip-vertical")
                     :shortcut (sc/get-tooltip :flip-vertical)
                     :on-click do-flip-vertical}]
     [:& menu-entry {:title (tr "workspace.shape.menu.flip-horizontal")
                     :shortcut (sc/get-tooltip :flip-horizontal)
                     :on-click do-flip-horizontal}]
     [:& menu-separator]

     (when multiple?
       [:*
        [:& menu-entry {:title (tr "workspace.shape.menu.group")
                        :shortcut (sc/get-tooltip :group)
                        :on-click do-create-group}]
        [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                        :shortcut (sc/get-tooltip :mask)
                        :on-click do-mask-group}]])

     (when (and single? (and (not has-mask-group?) (or has-bool? has-group?)))
       [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                       :shortcut (sc/get-tooltip :mask)
                       :on-click do-mask-group}])

     (when (or has-bool? has-group?)
       [:& menu-entry {:title (tr "workspace.shape.menu.ungroup")
                       :shortcut (sc/get-tooltip :ungroup)
                       :on-click do-remove-group}])
     
     (when has-mask-group?
        [:& menu-entry {:title (tr "workspace.shape.menu.unmask")
                        :shortcut (sc/get-tooltip :unmask)
                        :on-click do-unmask-group}]
       )

     (when (or multiple? has-mask-group? (or is-bool? has-group?) (and single? (or has-bool? has-group?)) )
       [:& menu-separator])

     (when (and single? editable-shape?)
       [:& menu-entry {:title (tr "workspace.shape.menu.edit")
                       :shortcut (sc/get-tooltip :start-editing)
                       :on-click do-start-editing}])

     (when-not disable-flatten?
       [:& menu-entry {:title (tr "workspace.shape.menu.transform-to-path")
                       :on-click do-transform-to-path}])

     (when (and (not disable-booleans?)
                (or multiple? (and single? (or is-group? is-bool?))))
       [:& menu-entry {:title (tr "workspace.shape.menu.path")}
        [:& menu-entry {:title (tr "workspace.shape.menu.union")
                        :shortcut (sc/get-tooltip :bool-union)
                        :on-click (set-bool :union)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.difference")
                        :shortcut (sc/get-tooltip :bool-difference)
                        :on-click (set-bool :difference)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.intersection")
                        :shortcut (sc/get-tooltip :bool-intersection)
                        :on-click (set-bool :intersection)}]
        [:& menu-entry {:title (tr "workspace.shape.menu.exclude")
                        :shortcut (sc/get-tooltip :bool-exclude)
                        :on-click (set-bool :exclude)}]

        (when (and single? is-bool? (not disable-flatten?))
          [:*
           [:& menu-separator]
           [:& menu-entry {:title (tr "workspace.shape.menu.flatten")
                           :on-click do-flatten}]])])

     (if (:hidden shape)
       [:& menu-entry {:title (tr "workspace.shape.menu.show")
                       :on-click do-show-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.hide")
                       :on-click do-hide-shape}])

     (if (:blocked shape)
       [:& menu-entry {:title (tr "workspace.shape.menu.unlock")
                       :on-click do-unlock-shape}]
       [:& menu-entry {:title (tr "workspace.shape.menu.lock")
                       :on-click do-lock-shape}])

     (when (and (= options-mode :prototype) (= (:type shape) :frame))
       (let [flow (cto/get-frame-flow flows (:id shape))]
         (if (nil? flow)
           [:& menu-entry {:title (tr "workspace.shape.menu.flow-start")
                           :on-click do-add-flow}]
           [:& menu-entry {:title (tr "workspace.shape.menu.delete-flow-start")
                           :on-click (do-remove-flow flow)}])))

     (when (and (not= (:type shape) :frame)
                (or multiple? (nil? (:component-id shape))))
       [:*
        [:& menu-separator]
        [:& menu-entry {:title (tr "workspace.shape.menu.create-component")
                        :shortcut (sc/get-tooltip :create-component)
                        :on-click do-add-component}]])

     (when (and (:component-id shape)
                (= (count selected) 1))
       ;; WARNING: this menu is the same as the context menu at the sidebar.
       ;;          If you change it, you must change equally the file
       ;;          app/main/ui/workspace/sidebar/options/component.cljs
       (if (= (:component-file shape) current-file-id)
         [:*
          [:& menu-separator]
          [:& menu-entry {:title (tr "workspace.shape.menu.detach-instance")
                          :shortcut (sc/get-tooltip :detach-component)
                          :on-click do-detach-component}]
          [:& menu-entry {:title (tr "workspace.shape.menu.reset-overrides")
                          :on-click do-reset-component}]
          [:& menu-entry {:title (tr "workspace.shape.menu.update-main")
                          :on-click do-update-component}]
          [:& menu-entry {:title (tr "workspace.shape.menu.show-main")
                          :on-click do-show-component}]]
         [:*
          [:& menu-separator]
          [:& menu-entry {:title (tr "workspace.shape.menu.detach-instance")
                          :shortcut (sc/get-tooltip :detach-component)
                          :on-click do-detach-component}]
          [:& menu-entry {:title (tr "workspace.shape.menu.reset-overrides")
                          :on-click do-reset-component}]
          [:& menu-entry {:title (tr "workspace.shape.menu.go-main")
                          :on-click do-navigate-component-file}]
          [:& menu-entry {:title (tr "workspace.shape.menu.update-main")
                          :on-click do-update-remote-component}]]))

     [:& menu-separator]
     [:& menu-entry {:title (tr "workspace.shape.menu.delete")
                     :shortcut (sc/get-tooltip :delete)
                     :on-click do-delete}]]))

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

      (if (:shape mdata)
        [:& shape-context-menu {:mdata mdata}]
        [:& viewport-context-menu {:mdata mdata}])]]))




;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.data.workspace.undo :as dwu]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
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
  [{:keys [title shortcut on-click] :as props}]
  [:li {:on-click on-click}
   [:span.title title]
   [:span.shortcut (or shortcut "")]])

(mf/defc menu-separator
  []
  [:li.separator])

(mf/defc shape-context-menu
  [{:keys [mdata] :as props}]
  (let [{:keys [id] :as shape} (:shape mdata)
        selected (:selected mdata)

        single? (= (count selected) 1)
        multiple? (> (count selected) 1)
        editable-shape? (#{:group :text :path} (:type shape))

        current-file-id (mf/use-ctx ctx/current-file-id)

        do-duplicate (st/emitf dw/duplicate-selected)
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
        do-create-group (st/emitf dw/group-selected)
        do-remove-group (st/emitf dw/ungroup-selected)
        do-mask-group (st/emitf dw/mask-group)
        do-unmask-group (st/emitf dw/unmask-group)
        do-flip-vertical (st/emitf (dw/flip-vertical-selected))
        do-flip-horizontal (st/emitf (dw/flip-horizontal-selected))
        do-add-component (st/emitf dwl/add-component)
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
                                              (:component-file shape)))]
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

     (when multiple?
       [:*
        [:& menu-entry {:title (tr "workspace.shape.menu.group")
                        :shortcut (sc/get-tooltip :group)
                        :on-click do-create-group}]
        [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                        :shortcut (sc/get-tooltip :mask)
                        :on-click do-mask-group}]
        [:& menu-separator]])

     (when (or single? multiple?)
       [:*
        [:& menu-entry {:title (tr "workspace.shape.menu.flip-vertical")
                        :shortcut (sc/get-tooltip :flip-vertical)
                        :on-click do-flip-vertical}]
        [:& menu-entry {:title (tr "workspace.shape.menu.flip-horizontal")
                        :shortcut (sc/get-tooltip :flip-horizontal)
                        :on-click do-flip-horizontal}]
        [:& menu-separator]])

     (when (and single? (= (:type shape) :group))
       [:*
         [:& menu-entry {:title (tr "workspace.shape.menu.ungroup")
                         :shortcut (sc/get-tooltip :ungroup)
                         :on-click do-remove-group}]
         (if (:masked-group? shape)
           [:& menu-entry {:title (tr "workspace.shape.menu.unmask")
                           :shortcut (sc/get-tooltip :unmask)
                           :on-click do-unmask-group}]
           [:& menu-entry {:title (tr "workspace.shape.menu.mask")
                           :shortcut (sc/get-tooltip :group)
                           :on-click do-mask-group}])])

     (when (and single? editable-shape?)
       [:& menu-entry {:title (tr "workspace.shape.menu.edit")
                       :shortcut (sc/get-tooltip :start-editing)
                       :on-click do-start-editing}])

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

     (when (and (or (nil? (:shape-ref shape))
                    (> (count selected) 1))
                (not= (:type shape) :frame))
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
                 delta-x (max (- (:right bounding-rect) (:width window-size)) 0)
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




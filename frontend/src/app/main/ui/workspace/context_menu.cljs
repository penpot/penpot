;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.context-menu
  "A workspace specific context menu (mouse right click)."
  (:require
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :refer [use-rxsub]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [t] :as i18n]
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]
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
  [props]
  [:li.separator])

(mf/defc shape-context-menu
  [{:keys [mdata] :as props}]
  (let [locale (mf/deref i18n/locale)
        {:keys [id] :as shape} (:shape mdata)
        selected (:selected mdata)

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
        do-update-component (st/emitf
                              (dwc/start-undo-transaction)
                              (dwl/update-component id)
                              (dwl/sync-file current-file-id (:component-file shape))
                              (dwc/commit-undo-transaction))
        confirm-update-remote-component (st/emitf
                                          (dwl/update-component id)
                                          (dwl/sync-file current-file-id
                                                         (:component-file shape))
                                          (dwl/sync-file (:component-file shape)
                                                         (:component-file shape)))
        do-update-remote-component (st/emitf (modal/show
                                                {:type :confirm
                                                 :message ""
                                                 :title (t locale "modals.update-remote-component.message")
                                                 :hint (t locale "modals.update-remote-component.hint")
                                                 :cancel-label (t locale "modals.update-remote-component.cancel")
                                                 :accept-label (t locale "modals.update-remote-component.accept")
                                                 :accept-style :primary
                                                 :on-accept confirm-update-remote-component}))
        do-show-component (st/emitf (dw/go-to-layout :assets))
        do-navigate-component-file (st/emitf (dwl/nav-to-component-file
                                               (:component-file shape)))]
    [:*
     [:& menu-entry {:title (t locale "workspace.shape.menu.copy")
                     :shortcut (sc/get-tooltip :copy)
                     :on-click do-copy}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.cut")
                     :shortcut (sc/get-tooltip :cut)
                     :on-click do-cut}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.paste")
                     :shortcut (sc/get-tooltip :paste)
                     :on-click do-paste}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.duplicate")
                     :shortcut (sc/get-tooltip :duplicate)
                     :on-click do-duplicate}]
     [:& menu-separator]
     [:& menu-entry {:title (t locale "workspace.shape.menu.forward")
                     :shortcut (sc/get-tooltip :bring-forward)
                     :on-click do-bring-forward}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.front")
                     :shortcut (sc/get-tooltip :bring-front)
                     :on-click do-bring-to-front}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.backward")
                     :shortcut (sc/get-tooltip :bring-backward)
                     :on-click do-send-backward}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.back")
                     :shortcut (sc/get-tooltip :bring-back)
                     :on-click do-send-to-back}]
     [:& menu-separator]

     (when (> (count selected) 1)
       [:*
        [:& menu-entry {:title (t locale "workspace.shape.menu.group")
                        :shortcut (sc/get-tooltip :group)
                        :on-click do-create-group}]
        [:& menu-entry {:title (t locale "workspace.shape.menu.mask")
                        :shortcut (sc/get-tooltip :mask)
                        :on-click do-mask-group}]
        [:& menu-separator]])

     (when (>= (count selected) 1)
       [:*
        [:& menu-entry {:title (t locale "workspace.shape.menu.flip-vertical")
                        :shortcut (sc/get-tooltip :flip-vertical)
                        :on-click do-flip-vertical}]
        [:& menu-entry {:title (t locale "workspace.shape.menu.flip-horizontal")
                        :shortcut (sc/get-tooltip :flip-horizontal)
                        :on-click do-flip-horizontal}]
        [:& menu-separator]])

     (when (and (= (count selected) 1) (= (:type shape) :group))
       [:*
         [:& menu-entry {:title (t locale "workspace.shape.menu.ungroup")
                         :shortcut (sc/get-tooltip :ungroup)
                         :on-click do-remove-group}]
         (if (:masked-group? shape)
           [:& menu-entry {:title (t locale "workspace.shape.menu.unmask")
                           :shortcut (sc/get-tooltip :unmask)
                           :on-click do-unmask-group}]
           [:& menu-entry {:title (t locale "workspace.shape.menu.mask")
                           :shortcut (sc/get-tooltip :group)
                           :on-click do-mask-group}])])

     (if (:hidden shape)
       [:& menu-entry {:title (t locale "workspace.shape.menu.show")
                       :on-click do-show-shape}]
       [:& menu-entry {:title (t locale "workspace.shape.menu.hide")
                       :on-click do-hide-shape}])

     (if (:blocked shape)
       [:& menu-entry {:title (t locale "workspace.shape.menu.unlock")
                       :on-click do-unlock-shape}]
       [:& menu-entry {:title (t locale "workspace.shape.menu.lock")
                       :on-click do-lock-shape}])

     (when (and (or (nil? (:shape-ref shape))
                    (> (count selected) 1))
                (not= (:type shape) :frame))
       [:*
        [:& menu-separator]
        [:& menu-entry {:title (t locale "workspace.shape.menu.create-component")
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
          [:& menu-entry {:title (t locale "workspace.shape.menu.detach-instance")
                          :on-click do-detach-component}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.reset-overrides")
                          :on-click do-reset-component}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.update-master")
                          :on-click do-update-component}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.show-master")
                          :on-click do-show-component}]]
         [:*
          [:& menu-separator]
          [:& menu-entry {:title (t locale "workspace.shape.menu.detach-instance")
                          :on-click do-detach-component}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.reset-overrides")
                          :on-click do-reset-component}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.go-master")
                          :on-click do-navigate-component-file}]
          [:& menu-entry {:title (t locale "workspace.shape.menu.update-master")
                          :on-click do-update-remote-component}]]))

     [:& menu-separator]
     [:& menu-entry {:title (t locale "workspace.shape.menu.delete")
                     :shortcut (sc/get-tooltip :delete)
                     :on-click do-delete}]]))

(mf/defc viewport-context-menu
  [{:keys [mdata] :as props}]
  (let [locale (mf/deref i18n/locale)
        do-paste (st/emitf dw/paste)]
    [:*
     [:& menu-entry {:title (t locale "workspace.shape.menu.paste")
                     :shortcut (sc/get-tooltip :paste)
                     :on-click do-paste}]]))

(mf/defc context-menu
  [props]
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




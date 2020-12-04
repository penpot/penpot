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
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.components.dropdown :refer [dropdown]]
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

        do-duplicate #(st/emit! dw/duplicate-selected)
        do-delete #(st/emit! dw/delete-selected)
        do-copy #(st/emit! dw/copy-selected)
        do-cut #(st/emit! dw/copy-selected dw/delete-selected)
        do-paste #(st/emit! dw/paste)
        do-bring-forward #(st/emit! (dw/vertical-order-selected :up))
        do-bring-to-front #(st/emit! (dw/vertical-order-selected :top))
        do-send-backward #(st/emit! (dw/vertical-order-selected :down))
        do-send-to-back #(st/emit! (dw/vertical-order-selected :bottom))
        do-show-shape #(st/emit! (dw/update-shape-flags id {:hidden false}))
        do-hide-shape #(st/emit! (dw/update-shape-flags id {:hidden true}))
        do-lock-shape #(st/emit! (dw/update-shape-flags id {:blocked true}))
        do-unlock-shape #(st/emit! (dw/update-shape-flags id {:blocked false}))
        do-create-group #(st/emit! dw/group-selected)
        do-remove-group #(st/emit! dw/ungroup-selected)
        do-mask-group #(st/emit! dw/mask-group)
        do-unmask-group #(st/emit! dw/unmask-group)
        do-add-component #(st/emit! dwl/add-component)
        do-detach-component #(st/emit! (dwl/detach-component id))
        do-reset-component #(st/emit! (dwl/reset-component id))
        do-update-component #(do
                               (st/emit! (dwc/start-undo-transaction))
                               (st/emit! (dwl/update-component id))
                               (st/emit! (dwl/sync-file current-file-id))
                               (st/emit! (dwc/commit-undo-transaction)))
        do-show-component #(st/emit! (dw/go-to-layout :assets))
        do-navigate-component-file #(st/emit! (dwl/nav-to-component-file
                                                (:component-file shape)))]
    [:*
     [:& menu-entry {:title (t locale "workspace.shape.menu.copy")
                     :shortcut "Ctrl + c"
                     :on-click do-copy}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.cut")
                     :shortcut "Ctrl + x"
                     :on-click do-cut}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.paste")
                     :shortcut "Ctrl + v"
                     :on-click do-paste}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.duplicate")
                     :shortcut "Ctrl + d"
                     :on-click do-duplicate}]
     [:& menu-separator]
     [:& menu-entry {:title (t locale "workspace.shape.menu.forward")
                     :shortcut "Ctrl + ↑"
                     :on-click do-bring-forward}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.front")
                     :shortcut "Ctrl + Shift + ↑"
                     :on-click do-bring-to-front}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.backward")
                     :shortcut "Ctrl + ↓"
                     :on-click do-send-backward}]
     [:& menu-entry {:title (t locale "workspace.shape.menu.back")
                     :shortcut "Ctrl + Shift + ↓"
                     :on-click do-send-to-back}]
     [:& menu-separator]

     (when (> (count selected) 1)
       [:*
        [:& menu-entry {:title (t locale "workspace.shape.menu.group")
                        :shortcut "Ctrl + g"
                        :on-click do-create-group}]
        [:& menu-entry {:title (t locale "workspace.shape.menu.mask")
                        :shortcut "Ctrl + M"
                        :on-click do-mask-group}]])

     (when (and (= (count selected) 1) (= (:type shape) :group))
       [:*
         [:& menu-entry {:title (t locale "workspace.shape.menu.ungroup")
                         :shortcut "Shift + g"
                         :on-click do-remove-group}]
         (if (:masked-group? shape)
           [:& menu-entry {:title (t locale "workspace.shape.menu.unmask")
                           :shortcut "Shift + M"
                           :on-click do-unmask-group}]
           [:& menu-entry {:title "Mask"
                           :shortcut "Ctrl + M"
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
                        :shortcut "Ctrl + K"
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
                          :on-click do-navigate-component-file}]]))

     [:& menu-separator]
     [:& menu-entry {:title (t locale "workspace.shape.menu.delete")
                     :shortcut "Supr"
                     :on-click do-delete}]]))

(mf/defc viewport-context-menu
  [{:keys [mdata] :as props}]
  (let [locale (mf/deref i18n/locale)
        do-paste #(st/emit! dw/paste)]
    [:*
     [:& menu-entry {:title (t locale "workspace.shape.menu.paste")
                     :shortcut "Ctrl + v"
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
                  :on-close #(st/emit! dw/hide-context-menu)}
     [:ul.workspace-context-menu
      {:ref dropdown-ref
       :style {:top top :left left}
       :on-context-menu prevent-default}

      (if (:shape mdata)
        [:& shape-context-menu {:mdata mdata}]
        [:& viewport-context-menu {:mdata mdata}])]]))




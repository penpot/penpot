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
   [beicon.core :as rx]
   [okulary.core :as l]
   [potok.core :as ptk]
   [rumext.alpha :as mf]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.streams :as ms]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.ui.hooks :refer [use-rxsub]]
   [app.main.ui.components.dropdown :refer [dropdown]]))

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
  (let [{:keys [id] :as shape} (:shape mdata)
        selected (:selected mdata)

        do-duplicate #(st/emit! dw/duplicate-selected)
        do-delete #(st/emit! dw/delete-selected)
        do-copy #(st/emit! dw/copy-selected)
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
        do-add-component #(st/emit! dwl/add-component)]
    [:*
     [:& menu-entry {:title "Copy"
                     :shortcut "Ctrl + c"
                     :on-click do-copy}]
     [:& menu-entry {:title "Paste"
                     :shortcut "Ctrl + v"
                     :on-click do-paste}]
     [:& menu-entry {:title "Duplicate"
                     :shortcut "Ctrl + d"
                     :on-click do-duplicate}]
     [:& menu-separator]
     [:& menu-entry {:title "Bring forward"
                     :shortcut "Ctrl + ↑"
                     :on-click do-bring-forward}]
     [:& menu-entry {:title "Bring to front"
                     :shortcut "Ctrl + Shift + ↑"
                     :on-click do-bring-to-front}]
     [:& menu-entry {:title "Send backward"
                     :shortcut "Ctrl + ↓"
                     :on-click do-send-backward}]
     [:& menu-entry {:title "Send to back"
                     :shortcut "Ctrl + Shift + ↓"
                     :on-click do-send-to-back}]
     [:& menu-separator]

     (when (> (count selected) 1)
       [:& menu-entry {:title "Group"
                       :shortcut "Ctrl + g"
                       :on-click do-create-group}])

     (when (and (= (count selected) 1) (= (:type shape) :group))
       [:& menu-entry {:title "Ungroup"
                       :shortcut "Shift + g"
                       :on-click do-remove-group}])

     (if (:hidden shape)
       [:& menu-entry {:title "Show"
                       :on-click do-show-shape}]
       [:& menu-entry {:title "Hide"
                       :on-click do-hide-shape}])

     (if (:blocked shape)
       [:& menu-entry {:title "Unlock"
                       :on-click do-unlock-shape}]
       [:& menu-entry {:title "Lock"
                       :on-click do-lock-shape}])

     [:& menu-separator]
     [:& menu-entry {:title "Create component"
                     :shortcut "Ctrl + K"
                     :on-click do-add-component}]

     [:& menu-separator]
     [:& menu-entry {:title "Delete"
                     :shortcut "Supr"
                     :on-click do-delete}]
     ]))

(mf/defc viewport-context-menu
  [{:keys [mdata] :as props}]
  (let [do-paste #(st/emit! dw/paste)]
    [:*
     [:& menu-entry {:title "Paste"
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




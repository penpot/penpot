;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.events :as ev]
   [app.main.data.shortcuts :as scd]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.tokens.common :refer [hide-token-context-menu]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.timers :as timers]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def tokens-menu-ref
  (l/derived :token-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc token-menu-entry
  {::mf/props :obj}
  [{:keys [title shortcut on-click on-pointer-enter on-pointer-leave
           on-unmount children selected? icon disabled value]}]
  (let [submenu-ref (mf/use-ref nil)
        hovering?   (mf/use-ref false)
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
      [:li {:class (stl/css :icon-menu-item)
            :disabled disabled
            :data-value value
            :ref set-dom-node
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span
        {:class (stl/css :icon-wrapper)}
        (if selected? [:span {:class (stl/css :selected-icon)}
                       i/tick]
            [:span {:class (stl/css :selected-icon)}])
        [:span {:class (stl/css :shape-icon)} icon]]
       [:span {:class (stl/css :title)} title]]
      [:li {:class (stl/css :context-menu-item)
            :disabled disabled
            :ref set-dom-node
            :data-value value
            :on-click on-click
            :on-pointer-enter on-pointer-enter
            :on-pointer-leave on-pointer-leave}
       [:span {:class (stl/css :title)} title]
       (when shortcut
         [:span   {:class (stl/css :shortcut)}
          (for [[idx sc] (d/enumerate (scd/split-sc shortcut))]
            [:span {:key (dm/str shortcut "-" idx)
                    :class (stl/css :shortcut-key)} sc])])

       (when (> (count children) 1)
         [:span {:class (stl/css :submenu-icon)} i/arrow])

       (when (> (count children) 1)
         [:ul {:class (stl/css :token-context-submenu)
               :ref submenu-ref
               :style {:display "none" :left 250}
               :on-context-menu prevent-default}
          children])])))

(mf/defc menu-separator
  []
  [:li {:class (stl/css :separator)}])

(mf/defc token-pill-context-menu
  [{:keys [token-id]}]
  (let [do-delete #(js/console.log "Deleting")
        do-duplicate #(js/console.log "Duplicating")
        do-edit #(js/console.log "Editing")]
    [:ul.context-list
     [:> token-menu-entry {:title (tr "Delete Token") :on-click do-delete}]
     [:> token-menu-entry {:title (tr "Duplicate Token") :on-click do-duplicate}]
     [:> token-menu-entry {:title (tr "Edit Token") :on-click do-edit}]]))

(mf/defc token-context-menu
  []
  (let [mdata          (mf/deref tokens-menu-ref)
        top            (- (get-in mdata [:position :y]) 20)
        left           (get-in mdata [:position :x])
        dropdown-ref   (mf/use-ref)]

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
                  :on-close #(st/emit! hide-token-context-menu)}
     [:div {:class (stl/css :token-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}
      (when  (= :token (:type mdata))
        [:ul {:class (stl/css :context-list)}
         [:& token-pill-context-menu {:token-id (:id mdata)}]])]]))
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.palette
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.events :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.color-palette :refer [color-palette]]
   [app.main.ui.workspace.color-palette-ctx-menu :refer [color-palette-ctx-menu]]
   [app.main.ui.workspace.text-palette :refer [text-palette]]
   [app.main.ui.workspace.text-palette-ctx-menu :refer [text-palette-ctx-menu]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.timers :as ts]
   [goog.events :as events]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def viewport
  (l/derived :vport refs/workspace-local))

(mf/defc palette
  [{:keys [layout]}]
  (let [color-palette?       (:colorpalette layout)
        text-palette?        (:textpalette layout)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        container            (mf/use-ref nil)
        state                (mf/use-state {:show-menu false :hide-palettes false})
        selected             (h/use-shared-state mdc/colorpalette-selected-broadcast-key :recent)
        selected-text        (mf/use-state :file)
        on-select            (mf/use-fn #(reset! selected %))
        {:keys [on-pointer-down on-lost-pointer-capture on-pointer-move parent-ref size]}
        (r/use-resize-hook :palette 72 54 80 :y true :bottom)

        vport (mf/deref viewport)
        vport-width (:width vport)
        on-resize
        (mf/use-callback
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (swap! state assoc :width width))))

        on-close-menu
        (mf/use-callback
         (fn [_]
           (swap! state assoc :show-menu false)))

        on-select-palette
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 value (dom/get-attribute node "data-palette")]
             (on-select (if (or (= "file" value) (= "recent" value))
                          (keyword value)
                          (parse-uuid value))))))

        on-select-text-palette
        (mf/use-fn
         (mf/deps on-select)
         (fn [lib]
           (if (or (nil? lib) (= :file lib))
             (reset! selected-text :file)
             (reset! selected-text (:id lib)))))

        toggle-palettes
        (mf/use-callback
         (fn [_]
           (swap! state update :hide-palettes not)))

        any-palette? (or color-palette? text-palette?)

        size-classname (cond
                         (<= size 64) (css :small-palette)
                         (<= size 72) (css :mid-palette)
                         (<= size 80) (css :big-palette))]

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    (mf/use-layout-effect
     #(let [dom     (mf/ref-val parent-ref)
            width (obj/get dom "clientWidth")]
        (swap! state assoc :width width)))

    [:div {:ref parent-ref
           :class (dom/classnames (css :palettes) true
                                  size-classname true
                                  (css :wide) any-palette?
                                  (css :hidden-bts) (:hide-palettes @state))
           :style #js {"--height" (dm/str size "px")}}

     [:div {:class (dom/classnames (css :resize-area) true)
            :on-pointer-down on-pointer-down
            :on-lost-pointer-capture on-lost-pointer-capture
            :on-pointer-move on-pointer-move}]
     (when-not workspace-read-only?
       [:ul {:class (dom/classnames (css :palette-btn-list) true
                                    (css :hidden-bts) (:hide-palettes @state)
                                    size-classname true)}
        [:li {:class (dom/classnames (css :palette-item) true)}
         [:button
          {:title (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
           :aria-label (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
           :class (dom/classnames (css :palette-btn) true
                                  (css :selected) color-palette?)
           :on-click (fn [event]
                       (let [node (dom/get-current-target event)]
                         (r/set-resize-type! :top)
                         (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
                         (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :textpalette)
                                                     (-> (dw/toggle-layout-flag :colorpalette)
                                                         (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))

                         (dom/blur! node)))}
          i/drop-refactor]]
         
        [:li {:class (dom/classnames (css :palette-item) true)}
         [:button
          {:title (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
           :aria-label (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
           :class (dom/classnames (css :palette-btn) true
                                  (css :selected) text-palette?)
           :on-click (fn [event]
                       (let [node (dom/get-current-target event)]
                         (r/set-resize-type! :top)
                         (dom/add-class!  (dom/get-element-by-class "color-palette") "fade-out-down")
                         (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :colorpalette)
                                                     (-> (dw/toggle-layout-flag :textpalette)
                                                         (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))
                         (dom/blur! node)))}
          i/text-palette-refactor]]])

     (if any-palette?
       [:*
        [:button {:class (dom/classnames (css :palette-actions) true)
                  :on-click #(swap! state update :show-menu not)}
         i/menu-refactor]
        [:div {:class (dom/classnames (css :palette) true)
               :ref container}
         (when text-palette?
           [:*
            [:& text-palette-ctx-menu {:show-menu? (:show-menu @state)
                                       :close-menu on-close-menu
                                       :on-select-palette on-select-text-palette
                                       :selected @selected-text}]
            [:& text-palette {:size size
                              :selected @selected-text
                              :width vport-width}]])
         (when color-palette?
           [:* [:& color-palette-ctx-menu {:show-menu? (:show-menu @state)
                                           :close-menu on-close-menu
                                           :on-select-palette on-select-palette
                                           :selected @selected}]
            [:& color-palette {:size size
                               :selected @selected
                               :width vport-width}]])]]
       [:div {:class (dom/classnames (css :handler) true)
              :on-click toggle-palettes}
        [:div {:class (dom/classnames (css :handler-btn) true)}]])]))

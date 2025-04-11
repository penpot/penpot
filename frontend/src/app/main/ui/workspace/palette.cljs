;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.palette
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.main.data.event :as ev]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.shortcuts :as sc]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.hooks.resize :as r]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.color-palette :refer [color-palette*]]
   [app.main.ui.workspace.color-palette-ctx-menu :refer [color-palette-ctx-menu*]]
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

(defn calculate-palette-padding [rulers?]
  (let [left-sidebar           (dom/get-element "left-sidebar-aside")
        left-sidebar-size      (-> (dom/get-data left-sidebar "size")
                                   (d/parse-integer))
        rulers-width           (if rulers? 22 0)
        min-left-sidebar-width 275
        left-padding           4
        calculate-padding-left (+ rulers-width (or left-sidebar-size min-left-sidebar-width) left-padding 1)]

    #js {"paddingLeft" (dm/str calculate-padding-left "px")
         "paddingRight" "280px"}))

(mf/defc palette
  [{:keys [layout on-change-palette-size]}]
  (let [color-palette?       (:colorpalette layout)
        text-palette?        (:textpalette layout)
        hide-palettes?       (:hide-palettes layout)
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)
        container            (mf/use-ref nil)
        state*               (mf/use-state {:show-menu false})
        state                (deref state*)
        show-menu?           (:show-menu state)
        selected             (h/use-shared-state mdc/colorpalette-selected-broadcast-key :recent)
        selected-text*       (mf/use-state :file)
        selected-text        (deref selected-text*)
        on-select            (mf/use-fn #(reset! selected %))
        rulers?              (mf/deref refs/rulers?)
        {:keys [on-pointer-down on-lost-pointer-capture on-pointer-move parent-ref size]}
        (r/use-resize-hook :palette 72 54 80 :y true :bottom on-change-palette-size)

        vport (mf/deref viewport)
        vport-width (:width vport)

        on-resize
        (mf/use-callback
         (fn [_]
           (let [dom   (mf/ref-val container)
                 width (obj/get dom "clientWidth")]
             (swap! state* assoc :width width))))

        on-close-menu
        (mf/use-callback
         (fn [_]
           (swap! state* assoc :show-menu false)))

        on-select-palette
        (mf/use-fn
         (mf/deps on-select)
         (fn [event]
           (let [node  (dom/get-current-target event)
                 value (dom/get-attribute node "data-palette")]
             (on-select (if (or (= "file" value) (= "recent" value))
                          (keyword value)
                          (uuid/parse value))))))

        on-select-text-palette-menu
        (mf/use-fn
         (mf/deps on-select)
         (fn [lib]
           (if (or (nil? lib) (= :file lib))
             (reset! selected-text* :file)
             (reset! selected-text* (:id lib)))))

        toggle-palettes
        (mf/use-callback
         (fn [_]
           (r/set-resize-type! :top)
           (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
           (st/emit! (-> (dw/toggle-layout-flag :hide-palettes)
                         (vary-meta assoc ::ev/origin "workspace-left-toolbar")))))

        on-select-color-palette
        (mf/use-fn
         (fn [event]
           (let [node (dom/get-current-target event)]
             (r/set-resize-type! :top)
             (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
             (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :hide-palettes)
                                         (dw/remove-layout-flag :textpalette)
                                         (-> (dw/toggle-layout-flag :colorpalette)
                                             (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))
             (dom/blur! node))))

        on-select-text-palette
        (mf/use-fn
         (fn [event]
           (let [node (dom/get-current-target event)]
             (r/set-resize-type! :top)
             (dom/add-class! (dom/get-element-by-class "color-palette") "fade-out-down")
             (ts/schedule 300 #(st/emit! (dw/remove-layout-flag :hide-palettes)
                                         (dw/remove-layout-flag :colorpalette)
                                         (-> (dw/toggle-layout-flag :textpalette)
                                             (vary-meta assoc ::ev/origin "workspace-left-toolbar"))))
             (dom/blur! node))))

        any-palette? (or color-palette? text-palette?)
        size-classname
        (cond
          (<= size 64) (stl/css :small-palette)
          (<= size 72) (stl/css :mid-palette)
          (<= size 80) (stl/css :big-palette))]

    (mf/with-effect []
      (let [key1 (events/listen js/window "resize" on-resize)]
        #(events/unlistenByKey key1)))

    (mf/use-layout-effect
     #(let [dom     (mf/ref-val parent-ref)
            width (obj/get dom "clientWidth")]
        (swap! state* assoc :width width)))

    [:div {:class (stl/css :palette-wrapper)
           :style  (calculate-palette-padding rulers?)
           :data-testid "palette"}
     (when-not workspace-read-only?
       [:div {:ref parent-ref
              :class (dm/str size-classname " " (stl/css-case :palettes true
                                                              :wide any-palette?
                                                              :hidden-bts hide-palettes?))
              :style #js {"--height" (dm/str size "px")}}

        [:div {:class (stl/css :resize-area)
               :on-pointer-down on-pointer-down
               :on-lost-pointer-capture on-lost-pointer-capture
               :on-pointer-move on-pointer-move}]
        [:ul {:class (dm/str size-classname " " (stl/css-case :palette-btn-list true
                                                              :hidden-bts hide-palettes?))}
         [:li {:class (stl/css :palette-item)}
          [:button {:title (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
                    :aria-label (tr "workspace.toolbar.color-palette" (sc/get-tooltip :toggle-colorpalette))
                    :class (stl/css-case :palette-btn true
                                         :selected color-palette?)
                    :on-click on-select-color-palette}
           i/drop-icon]]

         [:li {:class (stl/css :palette-item)}
          [:button {:title (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
                    :aria-label (tr "workspace.toolbar.text-palette" (sc/get-tooltip :toggle-textpalette))
                    :class (stl/css-case :palette-btn true
                                         :selected text-palette?)
                    :on-click on-select-text-palette}
           i/text-palette]]]


        (if any-palette?
          [:*
           [:button {:class (stl/css :palette-actions)
                     :on-click #(swap! state* update :show-menu not)}
            i/menu]
           [:div {:class (stl/css :palette)
                  :ref container}
            (when text-palette?
              [:*
               [:& text-palette-ctx-menu {:show-menu?  show-menu?
                                          :close-menu on-close-menu
                                          :on-select-palette on-select-text-palette-menu
                                          :selected selected-text}]
               [:& text-palette {:size size
                                 :selected selected-text
                                 :width vport-width}]])
            (when color-palette?
              [:*
               [:> color-palette-ctx-menu* {:show show-menu?
                                            :on-close on-close-menu
                                            :on-select on-select-palette
                                            :selected @selected}]
               [:> color-palette* {:size size
                                   :selected @selected
                                   :width vport-width}]])]]
          [:div {:class (stl/css :handler)
                 :on-click toggle-palettes
                 :data-testid "toggle-palettes-visibility"}
           [:div {:class (stl/css :handler-btn)}]])])]))

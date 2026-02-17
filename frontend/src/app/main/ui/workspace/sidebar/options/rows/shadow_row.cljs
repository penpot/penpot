;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.shadow-row
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.reorder-handler :refer [reorder-handler*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.sidebar.options.common :refer [advanced-options*]]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc shadow-row*
  [{:keys [index shadow is-open
           on-reorder
           on-toggle-open
           on-detach-color
           on-update
           on-remove
           on-toggle-visibility]}]
  (let [shadow-style       (:style shadow)
        shadow-id          (:id shadow)

        hidden?            (:hidden shadow)

        on-drop
        (mf/use-fn
         (mf/deps on-reorder index)
         (fn [relative-pos data]
           (let [from-pos             (:index data)
                 to-space-between-pos (if (= relative-pos :bot) (inc index) index)]
             (on-reorder from-pos to-space-between-pos))))

        [dprops dref]
        (h/use-sortable
         :data-type "penpot/shadow-entry"
         :on-drop on-drop
         :detect-center? false
         :data {:index index})

        on-remove
        (mf/use-fn (mf/deps index) #(on-remove index))

        trigger-bounding-box-cloaking
        (mf/use-fn
         (mf/deps shadow-id)
         (fn []
           (when shadow-id
             (st/emit! (dw/trigger-bounding-box-cloaking [shadow-id])))))

        on-update-offset-x
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn [value]
           (trigger-bounding-box-cloaking)
           (on-update index :offset-x value)))

        on-update-offset-y
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn [value]
           (trigger-bounding-box-cloaking)
           (on-update index :offset-y value)))

        on-update-spread
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn [value]
           (trigger-bounding-box-cloaking)
           (on-update index :spread value)))

        on-update-blur
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn [value]
           (trigger-bounding-box-cloaking)
           (on-update index :blur value)))

        on-update-color
        (mf/use-fn
         (mf/deps index on-update trigger-bounding-box-cloaking)
         (fn [color]
           (trigger-bounding-box-cloaking)
           (on-update index :color color)))

        on-detach-color
        (mf/use-fn (mf/deps index) #(on-detach-color index))

        on-style-change
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn [value]
           (trigger-bounding-box-cloaking)
           (on-update index :style (keyword value))))

        on-toggle-visibility
        (mf/use-fn
         (mf/deps index trigger-bounding-box-cloaking)
         (fn []
           (trigger-bounding-box-cloaking)
           (on-toggle-visibility index)))

        on-toggle-open
        (mf/use-fn
         (mf/deps shadow-id on-toggle-open)
         #(on-toggle-open shadow-id))

        type-options
        (mf/with-memo []
          [{:value "drop-shadow" :label (tr "workspace.options.shadow-options.drop-shadow")}
           {:value "inner-shadow" :label (tr "workspace.options.shadow-options.inner-shadow")}])

        on-open-row
        (mf/use-fn #(st/emit! (dwu/start-undo-transaction :color-row)))

        on-close-row
        (mf/use-fn #(st/emit! (dwu/commit-undo-transaction :color-row)))]

    [:div {:class (stl/css-case :global/shadow-option true
                                :shadow-element true
                                :dnd-over-top (= (:over dprops) :top)
                                :dnd-over-bot (= (:over dprops) :bot))}
     (when (some? on-reorder)
       [:> reorder-handler* {:ref dref}])

     [:*
      [:div {:class (stl/css :shadow-basic)}
       [:div {:class (stl/css :shadow-basic-info)}
        [:> icon-button* {:variant "secondary"
                          :icon i/menu
                          :class (stl/css-case :shadow-basic-button true
                                               :selected is-open)
                          :aria-label "open more options"
                          :disabled hidden?
                          :on-click on-toggle-open}]
        [:& select {:class (stl/css :shadow-basic-select)
                    :default-value (d/name shadow-style)
                    :options type-options
                    :disabled hidden?
                    :on-change on-style-change}]]

       [:div {:class (stl/css :shadow-basic-actions)}
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.shadow-options.toggle-shadow")
                          :on-click on-toggle-visibility
                          :icon (if hidden? "hide" "shown")}]
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.shadow-options.remove-shadow")
                          :on-click on-remove
                          :icon i/remove}]]]

      (when is-open
        [:> advanced-options* {:class (stl/css :shadow-advanced)
                               :is-visible is-open
                               :on-close on-toggle-open}

         [:div {:class (stl/css :shadow-advanced-row)}
          [:div {:class (stl/css :shadow-advanced-offset-x)
                 :title (tr "workspace.options.shadow-options.offsetx")}
           [:span {:class (stl/css :shadow-advanced-label)}
            "X"]
           [:> numeric-input* {:no-validate true
                               :placeholder "--"
                               :on-change on-update-offset-x
                               :value (:offset-x shadow)}]]

          [:div {:class (stl/css :shadow-advanced-blur)
                 :title (tr "workspace.options.shadow-options.blur")}
           [:span {:class (stl/css :shadow-advanced-label)}
            (tr "workspace.options.shadow-options.blur")]
           [:> numeric-input* {:no-validate true
                               :placeholder "--"
                               :on-change on-update-blur
                               :min 0
                               :value (:blur shadow)}]]

          [:div {:class (stl/css :shadow-advanced-spread)
                 :title (tr "workspace.options.shadow-options.spread")}
           [:span {:class (stl/css :shadow-advanced-label)}
            (tr "workspace.options.shadow-options.spread")]
           [:> numeric-input* {:no-validate true
                               :placeholder "--"
                               :on-change on-update-spread
                               :value (:spread shadow)}]]]

         [:div {:class (stl/css :shadow-advanced-row)}
          [:div {:class (stl/css :shadow-advanced-offset-y)
                 :title (tr "workspace.options.shadow-options.offsety")}
           [:span {:class (stl/css :shadow-advanced-label)}
            "Y"]
           [:> numeric-input* {:no-validate true
                               :placeholder "--"
                               :on-change on-update-offset-y
                               :value (:offset-y shadow)}]]

          [:> color-row* {:class (stl/css :shadow-advanced-color)
                          :color (:color shadow)
                          :title (tr "workspace.options.shadow-options.color")
                          :disable-gradient true
                          :disable-image true
                          :origin :shadow
                          :on-change on-update-color
                          :on-detach on-detach-color
                          :on-open on-open-row
                          :on-close on-close-row}]]])]]))

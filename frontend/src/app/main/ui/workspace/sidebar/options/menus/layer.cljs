;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layer
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.features :as features]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.render-wasm.api :as wasm.api]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layer-attrs
  [:opacity :blend-mode :blocked :hidden])

(defn- opacity->string
  [opacity]
  (if (not= opacity :multiple)
    (dm/str (-> opacity
                (d/coalesce 1)
                (* 100)))
    :multiple))

(defn- on-change
  [ids prop value]
  (st/emit! (dwsh/update-shapes ids #(assoc % prop value))))

(defn- check-layer-menu-props
  [old-props new-props]
  (let [old-values (unchecked-get old-props "values")
        new-values (unchecked-get new-props "values")]
    (and (identical? (unchecked-get old-props "class")
                     (unchecked-get new-props "class"))
         (identical? (unchecked-get old-props "ids")
                     (unchecked-get new-props "ids"))
         (identical? (get old-values :opacity)
                     (get new-values :opacity))
         (identical? (get old-values :blend-mode)
                     (get new-values :blend-mode))
         (identical? (get old-values :blocked)
                     (get new-values :blocked))
         (identical? (get old-values :hidden)
                     (get new-values :hidden)))))

(mf/defc layer-menu*
  {::mf/wrap [#(mf/memo' % check-layer-menu-props)]}
  [{:keys [ids values]}]
  (let [hidden?             (get values :hidden)
        blocked?            (get values :blocked)

        current-blend-mode  (or (get values :blend-mode) :normal)
        current-opacity     (opacity->string (:opacity values))

        state*              (mf/use-state
                             #(do {:selected-blend-mode current-blend-mode
                                   :option-highlighted? false
                                   :preview-complete? true}))

        state               (deref state*)
        selected-blend-mode (get state :selected-blend-mode)
        option-highlighted? (get state :option-highlighted?)
        preview-complete?   (get state :preview-complete?)

        wasm-renderer-enabled?
        (features/use-feature "render-wasm/v1")

        handle-change-blend-mode
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (swap! state* assoc
                  :selected-blend-mode value
                  :option-highlighted? false
                  :preview-complete? true)
           (st/emit! (dw/unset-preview-blend-mode ids))
           (on-change ids :blend-mode value)))

        handle-blend-mode-enter
        (mf/use-fn
         (mf/deps ids current-blend-mode)
         (fn [value]
           (swap! state* assoc
                  :preview-complete? false
                  :option-highlighted? true)

           (when wasm-renderer-enabled?
             (doseq [shape ids]
               (wasm.api/use-shape (:id shape))
               (wasm.api/set-shape-blend-mode value)))

           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (st/emit! (dw/set-preview-blend-mode ids value))))

        handle-blend-mode-leave
        (mf/use-fn
         (mf/deps ids)
         (fn [_value]
           (swap! state* assoc :preview-complete? true)
           (st/emit! (dw/unset-preview-blend-mode ids))))

        handle-opacity-change
        (mf/use-fn
         (mf/deps ids)
         (fn [value]
           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (let [value (/ value 100)]
             (on-change ids :opacity value))))

        handle-set-hidden
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (on-change ids :hidden true)))

        handle-set-visible
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (on-change ids :hidden false)))

        handle-set-blocked
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (on-change ids :blocked true)))

        handle-set-unblocked
        (mf/use-fn
         (mf/deps ids)
         (fn [_]
           (st/emit! (dw/trigger-bounding-box-cloaking ids))
           (on-change ids :blocked false)))

        options
        (mf/with-memo [current-blend-mode]
          (d/concat-vec
           (when (= :multiple current-blend-mode)
             [{:value :multiple :label "--"}])
           [{:value :normal :label (tr "workspace.options.layer-options.blend-mode.normal")}
            {:value :darken :label (tr "workspace.options.layer-options.blend-mode.darken")}
            {:value :multiply :label (tr "workspace.options.layer-options.blend-mode.multiply")}
            {:value :color-burn :label (tr "workspace.options.layer-options.blend-mode.color-burn")}
            {:value :lighten :label (tr "workspace.options.layer-options.blend-mode.lighten")}
            {:value :screen :label (tr "workspace.options.layer-options.blend-mode.screen")}
            {:value :color-dodge :label (tr "workspace.options.layer-options.blend-mode.color-dodge")}
            {:value :overlay :label (tr "workspace.options.layer-options.blend-mode.overlay")}
            {:value :soft-light :label (tr "workspace.options.layer-options.blend-mode.soft-light")}
            {:value :hard-light :label (tr "workspace.options.layer-options.blend-mode.hard-light")}
            {:value :difference :label (tr "workspace.options.layer-options.blend-mode.difference")}
            {:value :exclusion :label (tr "workspace.options.layer-options.blend-mode.exclusion")}
            {:value :hue :label (tr "workspace.options.layer-options.blend-mode.hue")}
            {:value :saturation :label (tr "workspace.options.layer-options.blend-mode.saturation")}
            {:value :color :label (tr "workspace.options.layer-options.blend-mode.color")}
            {:value :luminosity :label (tr "workspace.options.layer-options.blend-mode.luminosity")}]))]

    (mf/with-effect [current-blend-mode
                     option-highlighted?
                     preview-complete?]
      (when (or (not option-highlighted?)
                (and option-highlighted?
                     preview-complete?))
        (swap! state* assoc :selected-blend-mode current-blend-mode)))

    [:div {:class (stl/css-case :element-set-content true
                                :hidden hidden?)}
     [:div {:class (stl/css :select)}
      [:& select
       {:default-value selected-blend-mode
        :options options
        :on-change handle-change-blend-mode
        :is-open? option-highlighted?
        :class (stl/css-case :hidden-select hidden?)
        :on-pointer-enter-option handle-blend-mode-enter
        :on-pointer-leave-option handle-blend-mode-leave}]]
     [:div {:class (stl/css :input)
            :title (tr "workspace.options.opacity")}
      [:span {:class (stl/css :icon)} "%"]
      [:> numeric-input*
       {:value current-opacity
        :placeholder "--"
        :on-change handle-opacity-change
        :min 0
        :max 100
        :className (stl/css :numeric-input)}]]


     [:div {:class (stl/css :actions)}
      (cond
        (or (= :multiple hidden?) (not hidden?))
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.layer-options.toggle-layer")
                          :on-click handle-set-hidden
                          :icon i/shown}]

        :else
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.options.layer-options.toggle-layer")
                          :on-click handle-set-visible
                          :icon i/hide}])

      (cond
        (or (= :multiple blocked?) (not blocked?))
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.shape.menu.lock")
                          :on-click handle-set-blocked
                          :icon i/unlock}]

        :else
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "workspace.shape.menu.unlock")
                          :on-click handle-set-unblocked
                          :icon i/lock}])]]))

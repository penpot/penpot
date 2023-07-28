;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layer
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.icons :as i]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layer-attrs
  [:opacity :blend-mode :blocked :hidden])

(defn opacity->string
  [opacity]
  (if (= opacity :multiple)
    ""
    (dm/str (-> opacity
                (d/coalesce 1)
                (* 100)))))

(mf/defc layer-menu
  {::mf/wrap-props false}
  [props]
  (let [ids                (unchecked-get props "ids")
        type               (unchecked-get props "type")
        values             (unchecked-get props "values")

        current-blend-mode (or (:blend-mode values) :normal)
        current-opacity    (:opacity values)

        state*             (mf/use-state
                            {:selected-blend-mode current-blend-mode
                             :option-highlighted? false
                             :preview-complete? true})

        state               (deref state*)
        selected-blend-mode (get state :selected-blend-mode)
        option-highlighted? (get state :option-highlighted?)
        preview-complete?   (get state :preview-complete?)

        on-change
        (mf/use-fn
         (mf/deps ids)
         (fn [prop value]
           (st/emit! (dch/update-shapes ids #(assoc % prop value)))))

        handle-change-blend-mode
        (mf/use-fn
         (mf/deps on-change)
         (fn [value]
           (swap! state* assoc
             :selected-blend-mode value
             :option-highlighted? false
             :preview-complete? true)
           (st/emit! (dw/unset-preview-blend-mode ids))
           (on-change :blend-mode value)))

        handle-blend-mode-enter
        (mf/use-fn
         (mf/deps on-change current-blend-mode)
         (fn [value]
           (swap! state* assoc
             :preview-complete? false
             :option-highlighted? true)
           (st/emit! (dw/set-preview-blend-mode ids value))))

        handle-blend-mode-leave
        (mf/use-fn
         (mf/deps on-change selected-blend-mode)
         (fn [_value]
           (swap! state* assoc :preview-complete? true)
           (st/emit! (dw/unset-preview-blend-mode ids))))

        handle-opacity-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [value]
           (let [value (/ value 100)]
             (on-change :opacity value))))

        handle-set-hidden
        (mf/use-fn
         (mf/deps on-change)
         (fn [_]
           (on-change :hidden true)))

        handle-set-visible
        (mf/use-fn
         (mf/deps on-change)
         (fn [_]
           (on-change :hidden false)))

        handle-set-blocked
        (mf/use-fn
         (mf/deps on-change)
         (fn [_]
           (on-change :blocked true)))

        handle-set-unblocked
        (mf/use-fn
         (mf/deps on-change)
         (fn [_]
           (on-change :blocked false)))

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

    [:div.element-set
     [:div.element-set-title
      [:span
       (case type
         :multiple (tr "workspace.options.layer-options.title.multiple")
         :group (tr "workspace.options.layer-options.title.group")
         (tr "workspace.options.layer-options.title"))]]

     [:div.element-set-content
      [:div.row-flex
       [:& select
        {:class "flex-grow no-check"
         :default-value selected-blend-mode
         :options options
         :on-change handle-change-blend-mode
         :is-open? option-highlighted?
         :on-pointer-enter-option handle-blend-mode-enter
         :on-pointer-leave-option handle-blend-mode-leave}]

       [:div.input-element {:title (tr "workspace.options.opacity")
                            :class "percentail"}
        [:> numeric-input*
         {:value (opacity->string current-opacity)
          :placeholder (tr "settings.multiple")
          :on-change handle-opacity-change
          :min 0
          :max 100}]]

       [:div.element-set-actions.layer-actions
        (cond
          (or (= :multiple (:hidden values)) (not (:hidden values)))
          [:div.element-set-actions-button {:on-click handle-set-hidden} i/eye]

          :else
          [:div.element-set-actions-button {:on-click handle-set-visible} i/eye-closed])

        (cond
          (or (= :multiple (:blocked values)) (not (:blocked values)))
          [:div.element-set-actions-button {:on-click handle-set-blocked} i/unlock]

          :else
          [:div.element-set-actions-button {:on-click handle-set-unblocked} i/lock])]]]]))

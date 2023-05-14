;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.layer
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.changes :as dch]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def layer-attrs [:opacity :blend-mode :blocked :hidden])

(defn opacity->string [opacity]
  (if (= opacity :multiple)
    ""
    (str (-> opacity
             (d/coalesce 1)
             (* 100)))))

(defn select-all [event]
  (dom/select-text! (dom/get-target event)))

(mf/defc layer-menu [{:keys [ids type values]}]
  (let [selected-blend-mode     (mf/use-state (or (d/name (:blend-mode values)) "normal"))
        is-option-highlighted?  (mf/use-state false)
        is-preview-complete?    (mf/use-state true)
    
        change!
        (mf/use-callback
         (mf/deps ids)
         (fn [prop value]
           (st/emit! (dch/update-shapes ids #(assoc % prop value)))))

        handle-change-blend-mode
        (mf/use-callback
         (mf/deps change!)
         (fn [value]
          (when (not= "multiple" value)
             (reset! selected-blend-mode value)
             (reset! is-option-highlighted? false)
             (reset! is-preview-complete? true)
             (change! :blend-mode value))))

        handle-option-enter
        (mf/use-callback
         (mf/deps change!)
         (fn [value]
          (when (not= :multiple (:blend-mode values))
            (reset! is-preview-complete? false)
            (reset! is-option-highlighted? true)
            (change! :blend-mode value))))

        handle-option-leave
        (mf/use-callback
         (mf/deps change!)
         (fn [value]
          (when (not= :multiple (:blend-mode values))
            (reset! is-preview-complete? true)
            (change! :blend-mode @selected-blend-mode))))

        handle-opacity-change
        (mf/use-callback
         (mf/deps change!)
         (fn [value]
           (let [value (-> value (/ 100))]
             (change! :opacity value))))

        handle-set-hidden
        (mf/use-callback
         (mf/deps change!)
         (fn [_]
           (change! :hidden true)))

        handle-set-visible
        (mf/use-callback
         (mf/deps change!)
         (fn [_]
           (change! :hidden false)))

        handle-set-blocked
        (mf/use-callback
         (mf/deps change!)
         (fn [_]
           (change! :blocked true)))

        handle-set-unblocked
        (mf/use-callback
         (mf/deps change!)
         (fn [_]
           (change! :blocked false)))]
    
    (mf/use-effect
        (mf/deps (:blend-mode values))
          #(when (or (not @is-option-highlighted?) (and @is-option-highlighted? @is-preview-complete?))
              (reset! selected-blend-mode (or (d/name (:blend-mode values)) "normal"))))

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
       {:class "flex-grow"
        :default-value @selected-blend-mode
        :options (concat (when (= :multiple (:blend-mode values))
                                [{:value "multiple" :label "--"}])
                   [{:value "normal" :label (tr "workspace.options.layer-options.blend-mode.normal")}
                    {:value "darken" :label (tr "workspace.options.layer-options.blend-mode.darken")}
                    {:value "multiply" :label (tr "workspace.options.layer-options.blend-mode.multiply")}
                    {:value "color-burn" :label (tr "workspace.options.layer-options.blend-mode.color-burn")}
                    {:value "lighten" :label (tr "workspace.options.layer-options.blend-mode.lighten")}
                    {:value "screen" :label (tr "workspace.options.layer-options.blend-mode.screen")}
                    {:value "color-dodge" :label (tr "workspace.options.layer-options.blend-mode.color-dodge")}
                    {:value "overlay" :label (tr "workspace.options.layer-options.blend-mode.overlay")}
                    {:value "soft-light" :label (tr "workspace.options.layer-options.blend-mode.soft-light")}
                    {:value "hard-light" :label (tr "workspace.options.layer-options.blend-mode.hard-light")}
                    {:value "difference" :label (tr "workspace.options.layer-options.blend-mode.difference")}
                    {:value "exclusion" :label (tr "workspace.options.layer-options.blend-mode.exclusion")}
                    {:value "hue" :label (tr "workspace.options.layer-options.blend-mode.hue")}
                    {:value "saturation" :label (tr "workspace.options.layer-options.blend-mode.saturation")}
                    {:value "color" :label (tr "workspace.options.layer-options.blend-mode.color")}
                    {:value "luminosity" :label (tr "workspace.options.layer-options.blend-mode.luminosity")}])
        :on-change handle-change-blend-mode
        :is-open? @is-option-highlighted?
        :on-pointer-enter-option handle-option-enter
        :on-pointer-leave-option handle-option-leave}]

       [:div.input-element {:title (tr "workspace.options.opacity") :class "percentail"}
        [:> numeric-input {:value (-> values :opacity opacity->string)
                           :placeholder (tr "settings.multiple")
                           :on-focus select-all
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

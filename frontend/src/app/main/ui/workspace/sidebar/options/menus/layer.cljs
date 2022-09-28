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
  (let [change!
        (mf/use-callback
         (mf/deps ids)
         (fn [prop value]
           (st/emit! (dch/update-shapes ids #(assoc % prop value)))))

        handle-change-blend-mode
        (mf/use-callback
         (mf/deps change!)
         (fn [event]
           (let [value (-> (dom/get-target-val event) (keyword))]
             (change! :blend-mode value))))

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

    [:div.element-set
     [:div.element-set-title
      [:span
       (case type
         :multiple (tr "workspace.options.layer-options.title.multiple")
         :group (tr "workspace.options.layer-options.title.group")
         (tr "workspace.options.layer-options.title"))]]

     [:div.element-set-content
      [:div.row-flex
       [:select.input-select {:on-change handle-change-blend-mode
                              :value (d/name (:blend-mode values) "normal")}

        (when (= :multiple (:blend-mode values))
            [:option {:value "multiple"} "--"])

        [:option {:value "normal"} (tr "workspace.options.layer-options.blend-mode.normal")]

        [:option {:value "darken"} (tr "workspace.options.layer-options.blend-mode.darken")]
        [:option {:value "multiply"} (tr "workspace.options.layer-options.blend-mode.multiply")]
        [:option {:value "color-burn"} (tr "workspace.options.layer-options.blend-mode.color-burn")]

        [:option {:value "lighten"} (tr "workspace.options.layer-options.blend-mode.lighten")]
        [:option {:value "screen"} (tr "workspace.options.layer-options.blend-mode.screen")]
        [:option {:value "color-dodge"} (tr "workspace.options.layer-options.blend-mode.color-dodge")]

        [:option {:value "overlay"} (tr "workspace.options.layer-options.blend-mode.overlay")]
        [:option {:value "soft-light"} (tr "workspace.options.layer-options.blend-mode.soft-light")]
        [:option {:value "hard-light"} (tr "workspace.options.layer-options.blend-mode.hard-light")]

        [:option {:value "difference"} (tr "workspace.options.layer-options.blend-mode.difference")]
        [:option {:value "exclusion"} (tr "workspace.options.layer-options.blend-mode.exclusion")]

        [:option {:value "hue"} (tr "workspace.options.layer-options.blend-mode.hue")]
        [:option {:value "saturation"} (tr "workspace.options.layer-options.blend-mode.saturation")]
        [:option {:value "color"} (tr "workspace.options.layer-options.blend-mode.color")]
        [:option {:value "luminosity"} (tr "workspace.options.layer-options.blend-mode.luminosity")]]

       [:div.input-element {:title (tr "workspace.options.opacity") :class "percentail"}
        [:> numeric-input {:value (-> values :opacity opacity->string)
                           :placeholder (tr "settings.multiple")
                           :on-click select-all
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

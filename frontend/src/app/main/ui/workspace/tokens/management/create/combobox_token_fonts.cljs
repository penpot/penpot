;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.combobox-token-fonts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.fonts :as fonts]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.sidebar.options.menus.typography :refer [font-selector*]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [clojure.core :as c]
   [rumext.v2 :as mf]))


(defn- resolve-value
  [tokens prev-token value]
  (let [token
        {:value (cto/split-font-family value)
         :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"}

        tokens
        (-> tokens
            ;; Remove previous token when renaming a token
            (dissoc (:name prev-token))
            (update (:name token) #(ctob/make-token (merge % prev-token token))))]

    (->> tokens
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

(mf/defc font-picker-combobox*
  [{:keys [token tokens name] :rest props}]
  (let [form       (mf/use-ctx fc/context)
        input-name name

        resolved-input-name
        (mf/with-memo [input-name]
          (keyword (str "resolved-" (c/name input-name))))

        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        font (fonts/find-font-family value)

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (:value token)]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        font-selector-open* (mf/use-state false)
        font-selector-open? (deref font-selector-open*)

        on-click-dropdown-button
        (mf/use-fn
         (mf/deps font-selector-open?)
         (fn [e]
           (dom/prevent-default e)
           (reset! font-selector-open* (not font-selector-open?))))

        font-selector-button
        (mf/html
         [:> icon-button*
          {:on-click on-click-dropdown-button
           :aria-label (tr "workspace.tokens.token-font-family-select")
           :icon i/arrow-down
           :variant "action"
           :type "button"}])

        on-close-font-selector
        (mf/use-fn
         (fn []
           (reset! font-selector-open* false)))

        on-select-font
        (mf/use-fn
         (mf/deps font)
         (fn [{:keys [family] :as font}]
           (when (not= value family)
             (fm/on-input-change form input-name family true)
             (rx/push! resolve-stream family))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value false)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props   {:on-change on-change
                                  ;; TODO: Review this value vs default-value
                                  :value (or value "")
                                  :hint-message (:message hint)
                                  :slot-end font-selector-button
                                  :hint-type (:type hint)})

        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (if error
                                    (do
                                      (swap! form assoc-in [:errors input-name] {:message error})
                                      (swap! form assoc-in [:errors resolved-input-name] {:message error})
                                      (swap! form update :data dissoc resolved-input-name)
                                      (reset! hint* {:message error :type "error"}))
                                    (let [message (tr "workspace.tokens.resolved-value" (cto/join-font-family value))]
                                      (swap! form update :errors dissoc input-name resolved-input-name)
                                      (swap! form update :data assoc resolved-input-name value)
                                      (reset! hint* {:message message :type "hint"}))))))]

        (fn []
          (rx/dispose! subs))))

    [:*
     [:> input* props]
     (when font-selector-open?
       [:div {:class (stl/css :font-select-wrapper)}
        [:> font-selector* {:current-font font
                            :on-select on-select-font
                            :on-close on-close-font-selector
                            :full-size true}]])]))
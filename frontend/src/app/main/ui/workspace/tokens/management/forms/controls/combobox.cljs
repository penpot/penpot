;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.combobox
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.tokens.management.forms.controls.combobox-navigation :refer [use-navigation]]
   [app.main.ui.workspace.tokens.management.forms.controls.floating-dropdown :refer [use-floating-dropdown]]
   [app.main.ui.workspace.tokens.management.forms.controls.token-parsing :as tp]
   [app.main.ui.workspace.tokens.management.forms.controls.utils :as csu]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- resolve-value
  [tokens prev-token token-name value]
  (let [valid-token-name?
        (and (string? token-name)
             (re-matches  cto/token-name-validation-regex token-name))

        token
        {:value value
         :name (if (or (not valid-token-name?) (str/blank? token-name))
                 "__PENPOT__TOKEN__NAME__PLACEHOLDER__"
                 token-name)}
        tokens
        (-> tokens
            ;; Remove previous token when renaming a token
            (dissoc (:name prev-token))
            (update (:name token) #(ctob/make-token (merge % prev-token token))))]

    (->> (if (contains? cf/flags :tokenscript)
           (rx/of (ts/resolve-tokens tokens))
           (sd/resolve-tokens-interactive tokens))
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))
                  resolved-value (if (contains? cf/flags :tokenscript)
                                   (ts/tokenscript-symbols->penpot-unit resolved-value)
                                   resolved-value)]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

(mf/defc combobox*
  [{:keys [name tokens token token-type empty-to-end ref] :rest props}]

  (let [form              (mf/use-ctx fc/context)

        token-name        (get-in @form [:data :name] nil)
        touched?
        (and (contains? (:data @form) name)
             (get-in @form [:touched name]))

        error
        (get-in @form [:errors name])

        value
        (get-in @form [:data name] "")

        is-open*          (mf/use-state false)
        is-open           (deref is-open*)

        listbox-id        (mf/use-id)
        filter-term*      (mf/use-state "")
        filter-term       (deref filter-term*)

        options-ref       (mf/use-ref nil)
        dropdown-ref      (mf/use-ref nil)
        internal-ref      (mf/use-ref nil)
        nodes-ref         (mf/use-ref nil)
        wrapper-ref       (mf/use-ref nil)
        icon-button-ref   (mf/use-ref nil)
        ref               (or ref internal-ref)

        raw-tokens-by-type (mf/use-ctx muc/active-tokens-by-type)

        filtered-tokens-by-type
        (mf/with-memo [raw-tokens-by-type token-type]
          (csu/filter-tokens-for-input raw-tokens-by-type token-type))

        visible-options
        (mf/with-memo [filtered-tokens-by-type token]
          (if token
            (tp/remove-self-token filtered-tokens-by-type token)
            filtered-tokens-by-type))

        dropdown-options
        (mf/with-memo [visible-options filter-term]
          (csu/get-token-dropdown-options visible-options (str "{" filter-term)))

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state))))

        toggle-dropdown
        (mf/use-fn
         (mf/deps is-open)
         (fn [event]
           (dom/prevent-default event)
           (swap! is-open* not)
           (let [input-node (mf/ref-val ref)]
             (dom/focus! input-node))))

        resolve-stream
        (mf/with-memo [token]
          (if (contains? token :value)
            (rx/behavior-subject (:value token))
            (rx/subject)))

        on-option-enter
        (mf/use-fn
         (mf/deps value resolve-stream name)
         (fn [id]
           (let [input-node (mf/ref-val ref)
                 final-val (tp/select-option-by-id id options-ref input-node value)]
             (when final-val
               (fm/on-input-change form name final-val true)
               (rx/push! resolve-stream final-val))
             (reset! filter-term* "")
             (reset! is-open* false))))

        {:keys [focused-id  on-key-down]}
        (use-navigation
         {:is-open is-open
          :nodes-ref nodes-ref
          :options dropdown-options
          :toggle-dropdown toggle-dropdown
          :is-open* is-open*
          :on-enter on-option-enter})

        on-change
        (mf/use-fn
         (mf/deps resolve-stream name form)
         (fn [event]
           (let [node   (dom/get-target event)
                 value  (dom/get-input-value node)
                 token  (tp/active-token value node)]

             (fm/on-input-change form name value)
             (rx/push! resolve-stream value)

             (if token
               (do
                 (reset! is-open* true)
                 (reset! filter-term* (:partial token)))
               (do
                 (reset! is-open* false)
                 (reset! filter-term* ""))))))

        on-option-click
        (mf/use-fn
         (mf/deps value resolve-stream ref name)
         (fn [event]
           (let [input-node (mf/ref-val ref)
                 node       (dom/get-current-target event)
                 id         (dom/get-data node "id")
                 final-val  (tp/select-option-by-id id options-ref input-node value)]

             (reset! filter-term* "")
             (dom/focus! input-node)

             (when final-val
               (reset! is-open* false)
               (fm/on-input-change form name final-val true)
               (rx/push! resolve-stream final-val)

               (let [new-cursor (+ (str/index-of final-val "}") 1)]
                 (set! (.-selectionStart input-node) new-cursor)
                 (set! (.-selectionEnd input-node) new-cursor))))))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        props
        (mf/spread-props props {:on-change on-change
                                :value value
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :on-key-down on-key-down
                                :hint-type (:type hint)
                                :ref ref
                                :role "combobox"
                                :aria-activedescendant focused-id
                                :aria-controls listbox-id
                                :aria-expanded is-open
                                :slot-end
                                (when (some? @filtered-tokens-by-type)
                                  (mf/html
                                   [:> icon-button*
                                    {:variant "action"
                                     :icon i/arrow-down
                                     :ref icon-button-ref
                                     :tooltip-class (stl/css :button-tooltip)
                                     :class (stl/css :invisible-button)
                                     :tab-index "-1"
                                     :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                                     :on-mouse-down dom/prevent-default
                                     :on-click toggle-dropdown}]))})
        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)


        {:keys [style ready?]} (use-floating-dropdown is-open wrapper-ref dropdown-ref)]

    (mf/with-effect [resolve-stream tokens token name token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (let [touched? (get-in @form [:touched name])]
                                    (when touched?
                                      (if error
                                        (do
                                          (swap! form assoc-in [:extra-errors name] {:message error})
                                          (reset! hint* {:message error :type "error"}))
                                        (let [message (tr "workspace.tokens.resolved-value" value)]
                                          (swap! form update :extra-errors dissoc name)
                                          (reset! hint* {:message message :type "hint"}))))))))]
        (fn []
          (rx/dispose! subs))))

    (mf/with-effect [dropdown-options]
      (mf/set-ref-val! options-ref dropdown-options))

    (mf/with-effect [is-open* ref wrapper-ref]
      (when is-open
        (let [handler (fn [event]
                        (let [wrapper-node  (mf/ref-val wrapper-ref)
                              dropdown-node (mf/ref-val dropdown-ref)
                              target        (dom/get-target event)]
                          (when (and wrapper-node dropdown-node
                                     (not (dom/child? target wrapper-node))
                                     (not (dom/child? target dropdown-node)))
                            (reset! is-open* false))))]

          (.addEventListener js/document "mousedown" handler)

          (fn []
            (.removeEventListener js/document "mousedown" handler)))))


    [:div {:ref wrapper-ref}
     [:> ds/input* props]
     (when ^boolean is-open
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         (mf/portal
          (mf/html
           [:> options-dropdown* {:on-click on-option-click
                                  :class (stl/css :dropdown)
                                  :style {:visibility (if ready? "visible" "hidden")
                                          :left (:left style)
                                          :top (or (:top style) "unset")
                                          :bottom (or (:bottom style) "unset")
                                          :width (:width style)}
                                  :id listbox-id
                                  :options options
                                  :focused focused-id
                                  :selected nil
                                  :align :right
                                  :empty-to-end empty-to-end
                                  :wrapper-ref dropdown-ref
                                  :ref set-option-ref}])
          (dom/get-body))))]))
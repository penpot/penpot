;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.combobox
  (:require-macros [app.main.style :as stl])
  (:require


   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cft]
   [app.common.types.token :as cto]
   [app.common.types.token :as tk]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
   [app.main.ui.forms :as fc]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))


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

    (->> tokens
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

(defn- token->dropdown-option
  [token]
  {:id (str (get token :id))
   :type :token
   :resolved-value (get token :value)
   :name (get token :name)})

(defn- generate-dropdown-options
  [tokens no-sets]
  (if (empty? tokens)
    [{:type :empty
      :label (if no-sets
               (tr "ds.inputs.numeric-input.no-applicable-tokens")
               (tr "ds.inputs.numeric-input.no-matches"))}]
    (->> tokens
         (map (fn [[type items]]
                (cons {:group true
                       :type  :group
                       :id (dm/str "group-" (name type))
                       :name  (name type)}
                      (map token->dropdown-option items))))
         (interpose [{:separator true
                      :id "separator"
                      :type :separator}])
         (apply concat)
         (vec)
         (not-empty))))

(defn- extract-partial-brace-text
  [s]
  (when-let [start (str/last-index-of s "{")]
    (subs s (inc start))))

(defn- filter-token-groups-by-name
  [tokens filter-text]
  (let [lc-filter (str/lower filter-text)]
    (into {}
          (keep (fn [[group tokens]]
                  (let [filtered (filter #(str/includes? (str/lower (:name %)) lc-filter) tokens)]
                    (when (seq filtered)
                      [group filtered]))))
          tokens)))


(defn- sort-groups-and-tokens
  "Sorts both the groups and the tokens inside them alphabetically.

   Input:
   A map where:
   - keys are groups (keywords or strings, e.g. :dimensions, :colors)
   - values are vectors of token maps, each containing at least a :name key

   Example input:
   {:dimensions [{:name \"tres\"} {:name \"quini\"}]
    :colors    [{:name \"azul\"} {:name \"rojo\"}]}

   Output:
   A sorted map where:
   - groups are ordered alphabetically by key
   - tokens inside each group are sorted alphabetically by :name

   Example output:
   {:colors    [{:name \"azul\"} {:name \"rojo\"}]
    :dimensions [{:name \"quini\"} {:name \"tres\"}]}"

  [groups->tokens]
  (into (sorted-map) ;; ensure groups are ordered alphabetically by their key
        (for [[group tokens] groups->tokens]
          [group (sort-by :name tokens)])))



(mf/defc combobox*
  [{:keys [name tokens token token-type empty-to-end align ref] :rest props}]

  (let [form       (mf/use-ctx fc/context)

        input-name name
        token-name (get-in @form [:data :name] nil)

        is-open*        (mf/use-state false)
        is-open         (deref is-open*)
        listbox-id      (mf/use-id)
        focused-id*     (mf/use-state nil)
        focused-id      (deref focused-id*)

        raw-tokens-by-type (mf/use-ctx muc/active-tokens-by-type)

        filtered-tokens-by-type (mf/with-memo [raw-tokens-by-type token-type]
                                  (delay
                                    (-> (deref raw-tokens-by-type)
                                        (select-keys (get tk/tokens-by-input token-type))
                                        (not-empty))))
        _ (prn @filtered-tokens-by-type)

        ;; OPTIONS -> duplicated from numeric input
        filter-id*      (mf/use-state "")
        filter-id       (deref filter-id*)

        dropdown-options
        (mf/with-memo [filtered-tokens-by-type filter-id]
          (delay
            (let [tokens  (if (delay? filtered-tokens-by-type) @filtered-tokens-by-type filtered-tokens-by-type)

                  sorted-tokens (sort-groups-and-tokens tokens)
                  partial (extract-partial-brace-text filter-id)
                  options (if (seq partial)
                            (filter-token-groups-by-name sorted-tokens partial)
                            sorted-tokens)
                  no-sets? (nil? sorted-tokens)]
              (generate-dropdown-options options no-sets?))))
        _ (.log js/console (clj->js @dropdown-options))


        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        resolve-stream
        (mf/with-memo [token]
          (if (contains? token :value)
            (rx/behavior-subject (:value token))
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        nodes-ref            (mf/use-ref nil)

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state)
             (fn []
               (let [state (mf/ref-val nodes-ref)
                     state (d/nilv state #js {})
                     id    (dom/get-data node "id")
                     state (obj/unset! state id)]
                 (mf/set-ref-val! nodes-ref state))))))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        on-option-click
        (mf/use-fn
         (mf/deps)
         (fn [event]
           (let [_ (prn "deberia añadir el contenido a X")]

             (reset! filter-id* ""))))

        on-option-enter
        (mf/use-fn
         (mf/deps)
         (fn [_]
           (let [_ (prn "deberia añadir el contenido a X pero con enter")]

             (reset! filter-id* ""))))
        internal-ref         (mf/use-ref nil)
        ref                  (or ref internal-ref)
        open-dropdown-ref    (mf/use-ref nil)

        open-dropdown
        (mf/use-fn
         (mf/deps  ref)
         (fn [event]
           (dom/prevent-default event)
           (swap! is-open* not)
           (dom/focus! (mf/ref-val ref))))


        props
        (mf/spread-props  props  {:on-change on-change
                                  :default-value value
                                  :variant "comfortable"
                                  :hint-message (:message hint)
                                  :slot-end (when (some? tokens)
                                              (mf/html [:> icon-button* {:variant "ghost"
                                                                         :icon i/arrow-down
                                                                         :tooltip-class (stl/css :button-tooltip)
                                                                         :class (stl/css :invisible-button)
                                                                         :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                                                                         :ref open-dropdown-ref
                                                                         :on-click open-dropdown}]))
                                  :hint-type (:type hint)})
        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name token-name]

      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (let [touched? (get-in @form [:touched input-name])]
                                    (when touched?
                                      (if error
                                        (do
                                          (swap! form assoc-in [:extra-errors input-name] {:message error})
                                          (reset! hint* {:message error :type "error"}))
                                        (let [message (tr "workspace.tokens.resolved-value" value)]
                                          (swap! form update :extra-errors dissoc input-name)
                                          (reset! hint* {:message message :type "hint"}))))))))]

        (fn []
          (rx/dispose! subs))))

    [:div {}
     [:> ds/input* props]
     (when ^boolean is-open
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         [:> options-dropdown* {:on-click on-option-click
                                :id listbox-id
                                :options options
                                :focused focused-id
                                :selected nil
                                :align :right
                                :empty-to-end empty-to-end
                                :ref set-option-ref}])
       #_(mf/portal
          (mf/html
           (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
             [:> options-dropdown* {:on-click on-option-click
                                    :id listbox-id
                                    :options options
                                    :focused focused-id
                                    :selected nil
                                    :align :right
                                    :empty-to-end empty-to-end
                                    :ref set-option-ref}]))
          (dom/get-body)))]))
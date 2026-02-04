;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.combobox
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cft]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.colors :as cl]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.forms :as fc]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
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
   :resolved-value (get token :resolved-value)
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
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        tokens2          (if (object? tokens)
                           (mfu/bean tokens)
                           tokens)
        input-name name
        token-name (get-in @form [:data :name] nil)

        tokens3 (mf/use-ctx muc/active-tokens-by-type)

        tokens3 (mf/with-memo [tokens3 "boder-radius"]
                 (delay
                   (-> (deref tokens3)
                       (select-keys (get cto/tokens-by-input "boder-radius"))
                       (not-empty))))
        _ (prn "tokens" @tokens3)

        ;; OPTIONS -> duplicated from numeric input
        filter-id*      (mf/use-state "")
        filter-id       (deref filter-id*)

        dropdown-options
        (mf/with-memo [tokens3 filter-id]
          (delay
            (let [tokens  (if (delay? tokens3) @tokens3 tokens3)

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

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props  props  {:on-change on-change
                                  :default-value value
                                  :variant "comfortable"
                                  :hint-message (:message hint)
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

    [:> ds/input* props]))
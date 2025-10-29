;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.input-tokens-value
  (:require-macros [app.main.style :as stl])
  (:require

   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon-list] :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def token-type->reference-types
  {:color #{:color}
   :dimensions #{:dimensions}
   :spacing #{:spacing :dimensions}
   :border-radius #{:border-radius :dimensions :sizing}
   :font-family #{:font-family}
   :font-size #{:font-size :sizing :dimension}
   :opacity #{:opacity :number}
   :rotation #{:rotation :number}
   :stroke-width #{:stroke-width :dimension :sizing}
   :sizing #{:sizing :dimensions}
   :number #{:number}
   :letter-spacing #{:letter-spacing}
   :typography #{:typography}
   :text-case #{:text-case}
   :text-decoration #{:text-decoration}
   :line-height #{:number}})

;; TODO; duplucated code with numeric-input.cljs, consider refactoring
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
  
(defn- extract-partial-brace-text
  [string]
  (when-let [start (str/last-index-of string "{")]
    (subs string (inc start))))

(defn- filter-token-groups-by-name
  [tokens filter-text]
  (let [lc-filter (str/lower filter-text)]
    (into {}
          (keep (fn [[group tokens]]
                  (let [filtered (filter #(str/includes? (str/lower (:name %)) lc-filter) tokens)]
                    (when (seq filtered)
                      [group filtered]))))
          tokens)))

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
(defn get-option
  [options id]
  (let [options (if (delay? options) @options options)]
    (or (d/seek #(= id (get % :id)) options)
        (nth options 0))))

(def ^:private schema::input-token
  [:map
   [:label {:optional true} [:maybe :string]]
   [:aria-label {:optional true} [:maybe :string]]
   [:placeholder {:optional true} :string]
   [:value {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:error {:optional true} :boolean]
   [:slot-start {:optional true} [:maybe some?]]
   [:icon {:optional true}
    [:maybe [:and :string [:fn #(contains? icon-list %)]]]]
   [:token-resolve-result {:optional true} :any]])

(mf/defc token-value-hint*
  [{:keys [result]}]
  (let [{:keys [errors warnings resolved-value]} result
        empty-message? (nil? result)

        message (cond
                  empty-message? (tr "workspace.tokens.resolved-value" "-")
                  warnings (->> (wtw/humanize-warnings warnings)
                                (str/join "\n"))
                  errors (->> (wte/humanize-errors errors)
                              (str/join "\n"))
                  :else (tr "workspace.tokens.resolved-value" (dwtf/format-token-value (or resolved-value result))))
        type (cond
               empty-message? "hint"
               errors "error"
               warnings "warning"
               :else "hint")]
    [:> hint-message*
     {:id "token-value-hint"
      :message message
      :class (stl/css-case :resolved-value (not (or empty-message? (seq warnings) (seq errors))))
      :type type}]))

(mf/defc input-token*
  {::mf/forward-ref true
   ::mf/schema schema::input-token}
  [{:keys [class label token-resolve-result tokens empty-to-end type on-external-update-value] :rest props} ref]
  (let [error (not (nil? (:errors token-resolve-result)))
        id (mf/use-id)

        is-open*        (mf/use-state false)
        is-open         (deref is-open*)

        filter-term*      (mf/use-state "")
        filter-term       (deref filter-term*)

        listbox-id      (mf/use-id)

        focused-id*     (mf/use-state nil)
        focused-id      (deref focused-id*)

        selected-id* (mf/use-state (fn []))
        selected-id   (deref selected-id*)

        empty-to-end    (d/nilv empty-to-end false)

        internal-ref         (mf/use-ref nil)
        ref                  (or ref internal-ref)
        nodes-ref            (mf/use-ref nil)
        open-dropdown-ref    (mf/use-ref nil)
        options-ref          (mf/use-ref nil)
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

        dropdown-options
        (mf/with-memo [tokens filter-term type]
          (delay
            (let [tokens  (if (delay? tokens) @tokens tokens)
                  allowed (get token-type->reference-types (keyword type) #{})
                  tokens (select-keys tokens allowed)
                  sorted-tokens (sort-groups-and-tokens tokens)
                  partial (extract-partial-brace-text filter-term)

                  options (if (seq partial)
                            (filter-token-groups-by-name sorted-tokens partial)
                            sorted-tokens)
                  no-sets? (nil? sorted-tokens)]
              (generate-dropdown-options options no-sets?))))

        update-input
        (mf/use-fn
         (mf/deps ref)
         (fn [new-value]
           (when-let [node (mf/ref-val ref)]
             (dom/set-value! node new-value)
             (reset! is-open* false))))

        on-option-click
        (mf/use-fn
         (mf/deps options-ref update-input)
         (fn [event]
           (let [node    (dom/get-current-target event)
                 id      (dom/get-data node "id")
                 options (mf/ref-val options-ref)
                 options (if (delay? options) @options options)
                 option  (get-option options id)
                 name    (get option :name)
                 new-value (str "{" name "}")]
             (on-external-update-value new-value)
             (reset! is-open* false))))

        open-dropdown
        (mf/use-fn
         (fn [_]
           (swap! is-open* not)))

        props (mf/spread-props props {:id id
                                      :type "text"
                                      :class (stl/css :input)
                                      :variant "comfortable"
                                      :hint-type (when error "error")
                                      :slot-end (when (some? tokens)
                                                  (mf/html [:> icon-button* {:variant "action"
                                                                             :icon i/tokens
                                                                             :class (stl/css :invisible-button)
                                                                             :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                                                                             :ref open-dropdown-ref
                                                                             :on-click open-dropdown}]))
                                      :ref ref})]

    (mf/with-effect [dropdown-options]
      (mf/set-ref-val! options-ref dropdown-options))
    [:*
     [:div {:class [class (stl/css-case :wrapper true
                                        :input-error error)]}
      (when label
        [:> label* {:for id} label])
      [:> input-field* props]]
     (when token-resolve-result
       [:> token-value-hint* {:result token-resolve-result}])
     (when ^boolean is-open
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         [:> options-dropdown* {:on-click on-option-click
                                :id listbox-id
                                :options options
                                :selected selected-id
                                :focused focused-id
                                :align :left
                                :empty-to-end empty-to-end
                                :ref set-option-ref}]))]))

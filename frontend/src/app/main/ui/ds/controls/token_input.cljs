;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.token-input
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.object :as obj]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

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


  (mf/defc token-input*
    [{:keys [label id hint-message is-optional hint-type type tokens empty-to-end] :rest props}]
    (let [tokens          (if (object? tokens)
                            (mfu/bean tokens)
                            tokens)
          id (or id (mf/use-id))
          has-label (not (str/blank? label))
          type (d/nilv type "text")
          has-hint (and (some? hint-message) (not (str/blank? hint-message)))
          is-optional (d/nilv is-optional false)
          is-open*        (mf/use-state false)
          is-open         (deref is-open*)
          filter-term*      (mf/use-state "")
          filter-term       (deref filter-term*)

          dropdown-options
          (mf/with-memo [tokens filter-term]
            (delay
              (let [tokens  (if (delay? tokens) @tokens tokens)

                    sorted-tokens (sort-groups-and-tokens tokens)
                    partial (extract-partial-brace-text filter-term)
                    options (if (seq partial)
                              (filter-token-groups-by-name sorted-tokens partial)
                              sorted-tokens)
                    no-sets? (nil? sorted-tokens)]
                (generate-dropdown-options options no-sets?))))
                  on-option-click
          (mf/use-fn
           (mf/deps )
           (fn [event]
             ))
                  focused-id*     (mf/use-state nil)
          focused-id      (deref focused-id*)
                  selected-id*
          (mf/use-state (fn []
                          ))
                  empty-to-end    (d/nilv empty-to-end false)
                  selected-id
          (deref selected-id*)
                  listbox-id      (mf/use-id)
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
          props (mf/spread-props props {:type type
                                        :id id
                                        :has-hint has-hint
                                        :hint-type hint-type})]
      [:div {:class (stl/css-case :wrapper true)}

       (when has-label
         [:> label* {:for id :is-optional is-optional} label])
       [:> input-field* props]
       (when ^boolean is-open
         (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
           [:> options-dropdown* {:on-click on-option-click
                                  :id listbox-id
                                  :options options
                                  :selected selected-id
                                  :focused focused-id
                                  :align :left
                                  :empty-to-end empty-to-end
                                  :ref set-option-ref}]))
       (when has-hint
         [:> hint-message* {:id id
                            :message hint-message
                            :type hint-type}])]))

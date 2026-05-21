(ns app.main.ui.workspace.tokens.management.forms.controls.utils
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.token :as cto]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(defn- token->dropdown-option
  [token]
  {:id (str (get token :id))
   :type :token
   :value (get token :value)
   :resolved-value (get token :resolved-value)
   :name (get token :name)})

(defn- generate-dropdown-options
  [tokens no-sets]
  (let [non-empty-groups
        (->> tokens
             (filter (fn [[_ items]] (seq items))))]
    (if (empty? non-empty-groups)
      [{:type :empty
        :label (if no-sets
                 (tr "ds.inputs.numeric-input.no-applicable-tokens")
                 (tr "ds.inputs.numeric-input.no-matches"))}]
      (->> non-empty-groups
           (keep (fn [[type items]]
                   (when (seq? items)
                     (cons {:group true
                            :type  :group
                            :id (dm/str "group-" (name type))
                            :name  (name type)}
                           (map token->dropdown-option items)))))
           (interpose [{:separator true
                        :id "separator"
                        :type :separator}])
           (apply concat)
           (vec)
           (not-empty)))))

(defn- extract-partial-brace-text
  "Returns the substring after the last '{' in s. If the resulting
  substring ends with '}', that trailing brace is removed.
  Returns nil if no '{' is found or s is nil."
  [s]
  (when-let [start (str/last-index-of s "{")]
    (let [partial (subs s (inc start))]
      (if (and (seq partial)
               (> (count partial) 0)
               (= "}" (subs partial (dec (count partial)))))
        (subs partial 0 (dec (count partial)))
        partial))))

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
  "Sorts the tokens inside the groups alphabetically.

   Input:
   A map where:
   - keys are groups (keywords or strings, e.g. :dimensions, :colors)
   - values are vectors of token maps, each containing at least a :name key

   Example input:
   {:dimensions [{:name \"tres\"} {:name \"quini\"}]
    :colors    [{:name \"azul\"} {:name \"rojo\"}]}

   Output:
   A map which:
   - tokens inside each group are sorted alphabetically by :name

   Example output:
   {:dimensions [{:name \"quini\"} {:name \"tres\"}]
    :colors    [{:name \"azul\"} {:name \"rojo\"}]}"

  [groups->tokens]
  (reduce (fn [acc [group tokens]]
            (assoc acc group (sort-by :name tokens)))
          {}
          groups->tokens))

(defn get-token-dropdown-options
  [tokens filter-term]
  (delay
    (let [tokens  (if (delay? tokens) @tokens tokens)

          sorted-tokens (sort-groups-and-tokens tokens)
          partial (extract-partial-brace-text filter-term)
          options (if (seq partial)
                    (filter-token-groups-by-name sorted-tokens partial)
                    sorted-tokens)
          no-sets? (empty? sorted-tokens)]
      (generate-dropdown-options options no-sets?))))

(defn filter-tokens-for-input
  [raw-tokens input-type]
  (delay
    (let [raw-tokens (deref raw-tokens)
          key-order  (case input-type
                       :color-selection
                       (concat
                        (get cto/tokens-by-input :fill)
                        (get cto/tokens-by-input :stroke-color))

                       (get cto/tokens-by-input input-type))]
      (-> (reduce (fn [acc k]
                    (if (contains? raw-tokens k)
                      (assoc acc k (get raw-tokens k))
                      acc))
                  (array-map)
                  key-order)
          (not-empty)))))

(defn focusable-options [options]
  (filter #(= (:type %) :token) options))
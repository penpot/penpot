(ns app.main.ui.workspace.tokens.management.forms.controls.utils
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cfo]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.refs :as refs]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn without-token
  "`sets` (`[[set-id tokens] ..]`) with the token `token-name` left out of
  every set. A set without it stays the same object, so tokenscript skips
  it as unchanged."
  [sets token-name]
  (mapv (fn [[set-id tokens :as entry]]
          (if (contains? tokens token-name)
            [set-id (dissoc tokens token-name)]
            entry))
        sets))

(defn use-edit-token-sets
  "Token sets a token being edited in the selected set resolves against
  with tokenscript, unmerged (see `cfo/get-token-sets-for-edit`).
  `excluded-name`, the name of the token being edited, is left out, so a
  renamed draft doesn't leave the old name resolving and references to
  the old name break as they will once it is saved. The sets stay the
  same objects while the library does not change, so each keystroke
  re-resolves only the draft."
  [excluded-name]
  (let [tokens-lib (mf/deref refs/tokens-lib)
        set-id     (mf/deref refs/selected-token-set-id)]
    (mf/with-memo [tokens-lib set-id excluded-name]
      (when tokens-lib
        (cond-> (cfo/get-token-sets-for-edit tokens-lib
                                             (or set-id (some-> (ctob/get-sets tokens-lib) first ctob/get-id)))
          excluded-name (without-token excluded-name))))))

(defn resolved->result
  "The `{:value ..}` or `{:error ..}` a form control shows for the
  resolved draft, an unknown error when it has neither a value nor
  errors."
  [{:keys [errors resolved-value]} value]
  (if resolved-value
    {:value resolved-value}
    {:error (if errors
              (first errors)
              (wte/error-with-value :error/unknown value))}))

(defn draft-token
  "The token being edited as the form has it: `prev-token` with `value`,
  named `token-name`, or a placeholder name while that is not a valid
  token name."
  [prev-token token-name value]
  (let [valid-name? (and (string? token-name)
                         (not (str/blank? token-name))
                         (re-matches cto/token-name-validation-regex token-name))]
    (assoc prev-token
           :name (if valid-name? token-name "__PENPOT__TOKEN__NAME__PLACEHOLDER__")
           :value value)))

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
  (into {}
        (keep (fn [[group tokens]]
                (let [filtered (filter #(str/includes? (:name %) filter-text) tokens)]
                  (when (seq filtered)
                    [group filtered]))))
        tokens))

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

(defn group-name-conflict-error?
  [error token-name]
  (let [translated-string (tr "errors.tokens.name-collision" token-name)]
    (= error translated-string)))

(defn- referenced-token-name
  "Name of the token `reference` points to, when `reference` is exactly
  one `{name}` reference."
  [reference]
  (when (string? reference)
    (let [reference (str/trim reference)
          names     (cto/find-token-value-references reference)
          name      (first names)]
      (when (and (= 1 (count names))
                 (= reference (str "{" name "}")))
        name))))

(defn referenced-token-value
  "Composite value of the `token-type` token that `reference` points to,
  following chained references. Nil when the chain does not end in a
  `token-type` token with a composite value."
  [tokens reference token-type]
  (loop [reference reference
         visited   #{}]
    (when-let [name (referenced-token-name reference)]
      (let [{:keys [type value]} (get tokens name)]
        (when (and (= token-type type)
                   (not (contains? visited name)))
          (if (string? value)
            (recur value (conj visited name))
            value))))))

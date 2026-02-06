(ns app.main.ui.workspace.tokens.management.forms.validators
  (:require
   [app.common.data :as d]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.errors :as wte]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

;; Value Validation -------------------------------------------------------------


(defn check-empty-value [token]
  (let [token-value (:value token)]
    (when (empty? (str/trim token-value))
      (wte/get-error-code :error.token/empty-input))))

(defn check-self-reference [token-name token-value]
  (when (cto/token-value-self-reference? token-name token-value)
    (wte/get-error-code :error.token/direct-self-reference)))

(defn validate-resolve-token
  [token prev-token tokens]
  (let [token (cond-> token
                ;; When creating a new token we dont have a name yet or invalid name,
                ;; but we still want to resolve the value to show in the form.
                ;; So we use a temporary token name that hopefully doesn't clash with any of the users token names
                (not (sm/valid? cto/schema:token-name (:name token)))
                (assoc :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"))
        tokens' (cond-> tokens
                  ;; Remove previous token when renaming a token
                  (not= (:name token) (:name prev-token))
                  (dissoc (:name prev-token))

                  :always
                  (update (:name token) #(ctob/make-token (merge % prev-token token))))]
    (->> tokens'
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (cond
                resolved-value (rx/of resolved-token)
                :else (rx/throw {:errors (or (seq errors)
                                             [(wte/get-error-code :error/unknown-error)])}))))))))

(defn- validate-token-with [token validators]
  (if-let [error (some (fn [validate] (validate token)) validators)]
    (rx/throw {:errors [error]})
    (rx/of token)))

(def ^:private default-validators
  [check-empty-value check-self-reference])

(defn default-validate-token
  "Validates a token by confirming a list of `validator` predicates and
  resolving the token using `tokens` with StyleDictionary.  Returns rx
  stream of either a valid resolved token or an errors map.

  Props:
  token-name, token-value, token-description: Values from the form inputs
  prev-token: The existing token currently being edited
  tokens: tokens map keyed by token-name
          Used to look up the editing token & resolving step.

  validators: A list of predicates that will be used to do simple validation on the unresolved token map.
              The validators get the token map as input and should either return:
                - An errors map .e.g: {:errors []}
                - nil (valid token predicate)
              Mostly used to do simple checks like invalidating empy token `:name`.
              Will default to `default-validators`."
  [{:keys [token-name token-value token-description prev-token tokens validators]
    :or {validators default-validators}}]
  (let [token (-> {:name token-name
                   :value token-value
                   :description token-description}
                  (d/without-nils))]
    (->> (rx/of token)
         ;; Simple validation of the editing token
         (rx/mapcat #(validate-token-with % validators))
         ;; Resolving token via StyleDictionary
         (rx/mapcat #(validate-resolve-token % prev-token tokens)))))

(defn check-coll-self-reference
  "Invalidate a collection of `token-vals` for a self-refernce against `token-name`.,"
  [token-name token-vals]
  (when (some #(cto/token-value-self-reference? token-name %) token-vals)
    (wte/get-error-code :error.token/direct-self-reference)))

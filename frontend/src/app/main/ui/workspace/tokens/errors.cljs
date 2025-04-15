(ns app.main.ui.workspace.tokens.errors
  (:require
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(def error-codes
  {:error.import/json-parse-error
   {:error/code :error.import/json-parse-error
    :error/fn #(tr "workspace.token.error-parse")}

   :error.import/invalid-json-data
   {:error/code :error.import/invalid-json-data
    :error/fn #(tr "workspace.token.invalid-json")}

   :error.import/invalid-token-name
   {:error/code :error.import/invalid-json-data
    :error/fn #(tr "workspace.token.invalid-json-token-name")
    :error/detail #(tr "workspace.token.invalid-json-token-name-detail" %)}

   :error.import/style-dictionary-reference-errors
   {:error/code :error.import/style-dictionary-reference-errors
    :error/fn #(str (tr "workspace.token.import-error") "\n\n" (first %))
    :error/detail #(str/join "\n\n" (rest %))}

   :error.import/style-dictionary-unknown-error
   {:error/code :error.import/style-dictionary-reference-errors
    :error/fn #(tr "workspace.token.import-error")}

   :error.token/direct-self-reference
   {:error/code :error.token/direct-self-reference
    :error/fn #(tr "workspace.token.self-reference")}

   :error.token/invalid-color
   {:error/code :error.token/invalid-color
    :error/fn #(str (tr "workspace.token.invalid-color" %))}

   :error.token/number-too-large
   {:error/code :error.token/number-too-large
    :error/fn #(str (tr "workspace.token.number-too-large" %))}

   :error.style-dictionary/missing-reference
   {:error/code :error.style-dictionary/missing-reference
    :error/fn #(str (tr "workspace.token.missing-references") (str/join " " %))}

   :error.style-dictionary/invalid-token-value
   {:error/code :error.style-dictionary/invalid-token-value
    :error/fn #(str (tr "workspace.token.invalid-value" %))}

   :error.style-dictionary/invalid-token-value-opacity
   {:error/code :error.style-dictionary/invalid-token-value-opacity
    :error/fn #(str/join "\n" [(str (tr "workspace.token.invalid-value" %) ".") (tr "workspace.token.opacity-range")])}

   :error.style-dictionary/invalid-token-value-stroke-width
   {:error/code :error.style-dictionary/invalid-token-value-stroke-width
    :error/fn #(str/join "\n" [(str (tr "workspace.token.invalid-value" %) ".") (tr "workspace.token.stroke-width-range")])}

   :error/unknown
   {:error/code :error/unknown
    :error/fn #(tr "labels.unknown-error")}})

(defn get-error-code [error-key]
  (get error-codes error-key (:error/unknown error-codes)))

(defn error-with-value [error-key error-value]
  (-> (get-error-code error-key)
      (assoc :error/value error-value)))

(defn error-ex-info [error-key error-value exception]
  (let [err (-> (error-with-value error-key error-value)
                (assoc :error/exception exception))]
    (ex-info (:error/code err) err)))

(defn has-error-code? [error-key errors]
  (some #(= (:error/code %) error-key) errors))

(defn humanize-errors [errors]
  (->> errors
       (map (fn [err]
              (cond
                (:error/fn err) ((:error/fn err) (:error/value err))
                (:error/message err) (:error/message err)
                :else err)))))

(defn detail-errors [errors]
  (->> errors
       (map (fn [err]
              (when (:error/detail err)
                ((:error/detail err) (:error/value err)))))
       (filter some?)
       (seq)))

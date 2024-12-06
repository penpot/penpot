(ns app.main.ui.workspace.tokens.errors
  (:require
   [cuerdas.core :as str]))

(def error-codes
  {:error.import/json-parse-error
   {:error/code :error.import/json-parse-error
    :error/message "Import Error: Could not parse json"}

   :error.import/invalid-json-data
   {:error/code :error.import/invalid-json-data
    :error/message "Import Error: Invalid token data in json."}

   :error.import/style-dictionary-reference-errors
   {:error/code :error.import/style-dictionary-reference-errors
    :error/fn #(str "Import Error:\n\n" (str/join "\n\n" %))}

   :error.import/style-dictionary-unknown-error
   {:error/code :error.import/style-dictionary-reference-errors
    :error/message "Import Error:"}

   :error.token/direct-self-reference
   {:error/code :error.token/direct-self-reference
    :error/message "Token has self reference"}

   :error.token/invalid-color
   {:error/code :error.token/invalid-color
    :error/fn #(str "Invalid color value: " %)}

   :error.style-dictionary/missing-reference
   {:error/code :error.style-dictionary/missing-reference
    :error/fn #(str "Missing token references: " (str/join " " %))}

   :error.style-dictionary/invalid-token-value
   {:error/code :error.style-dictionary/invalid-token-value
    :error/fn #(str "Invalid token value: " %)}

   :error/unknown
   {:error/code :error/unknown
    :error/message "Unknown error"}})

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

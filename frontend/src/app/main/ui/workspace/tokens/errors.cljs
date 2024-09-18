(ns app.main.ui.workspace.tokens.errors)

(def error-codes
  {:error.token/direct-self-reference
   {:error/fn #(str "Token has self reference in name: " %)}
   :error.token/invalid-color
   {:error/fn #(str "Invalid color value: " %)}
   :error.style-dictionary/missing-reference
   {:error/fn #(str "Could not resolve reference token with name: " %)}
   :error.style-dictionary/invalid-token-value
   {:error/message "Invalid token value"}
   :error/unknown
   {:error/message "Unknown error"}})

(defn humanize-errors [v errors]
  (->> errors
       (map (fn [err]
              (let [err' (get error-codes err err)]
                (cond
                  (:error/fn err') ((:error/fn err') v)
                  (:error/message err') (:error/message err')
                  :else err'))))))

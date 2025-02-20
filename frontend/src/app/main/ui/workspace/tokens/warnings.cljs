(ns app.main.ui.workspace.tokens.warnings
  (:require
   [cuerdas.core :as str]))

(def warning-codes
  {:warning.style-dictionary/invalid-referenced-token-value
   {:warning/code :warning.style-dictionary/invalid-referenced-token-value
    :warning/fn (fn [value] (str/join "\n" [(str "Resolved value " value ".") "Opacity must be between 0 and 100% or 0 and 1  (e.g. 50% or 0.5)"]))}

   :warning/unknown
   {:warning/code :warning/unknown
    :warning/message "Unknown warning"}})

(defn get-warning-code [warning-key]
  (get warning-codes warning-key (:warning/unknown warning-codes)))

(defn warning-with-value [warning-key warning-value]
  (-> (get-warning-code warning-key)
      (assoc :warning/value warning-value)))

(defn humanize-warnings [warnings]
  (->> warnings
       (map (fn [warn]
              (cond
                (:warning/fn warn) ((:warning/fn warn) (:warning/value warn))
                (:warning/message warn) (:warning/message warn)
                :else warn)))))
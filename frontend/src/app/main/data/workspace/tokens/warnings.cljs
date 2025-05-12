;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.warnings
  (:require
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(def warning-codes
  {:warning.style-dictionary/invalid-referenced-token-value-opacity
   {:warning/code :warning.style-dictionary/invalid-referenced-token-value-opacity
    :warning/fn (fn [value] (str/join "\n" [(str (tr "workspace.token.resolved-value" value) ".") (tr "workspace.token.opacity-range")]))}

   :warning.style-dictionary/invalid-referenced-token-value-stroke-width
   {:warning/code :warning.style-dictionary/invalid-referenced-token-value-stroke-width
    :warning/fn (fn [value] (str/join "\n" [(str (tr "workspace.token.resolved-value" value) ".") (tr "workspace.token.stroke-width-range")]))}

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
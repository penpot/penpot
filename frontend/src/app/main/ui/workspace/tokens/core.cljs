;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.core
  (:require
   [app.common.data :as d]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.util.dom :as dom]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]))

;; Helpers ---------------------------------------------------------------------

(defn resolve-token-value [{:keys [value resolved-value] :as _token}]
  (or
   resolved-value
   (d/parse-double value)))

(defn maybe-resolve-token-value [{:keys [value] :as token}]
  (when value (resolve-token-value token)))

(defn group-tokens-by-type
  "Groups tokens by their `:type` property."
  [tokens]
  (->> (vals tokens)
       (group-by :type)))

(defn tokens-name-map->select-options [{:keys [shape tokens attributes selected-attributes]}]
  (->> (wtt/token-names-map tokens)
       (map (fn [[_k {:keys [name] :as item}]]
              (cond-> (assoc item :label name)
                (wtt/token-applied? item shape (or selected-attributes attributes)) (assoc :selected? true))))))

;; JSON export functions -------------------------------------------------------

(defn encode-tokens
  [data]
  (-> data
      (clj->js)
      (js/JSON.stringify nil 2)))

(defn export-tokens-file [tokens-json]
  (let [file-name "tokens.json"
        file-content (encode-tokens tokens-json)
        blob (wapi/create-blob (clj->js file-content) "application/json")]
    (dom/trigger-download file-name blob)))

(defn transform-tokens-into-json-format [tokens]
  (let [global (reduce
                (fn [acc [_ {:keys [name value type]}]]
                  (assoc acc name {:$value value
                                   :$type (str/camel type)}))
                (sorted-map) tokens)]
    {:global global}))

(defn download-tokens-as-json []
  (let [all-tokens (deref refs/workspace-selected-token-set-tokens)
        transformed-tokens-json (transform-tokens-into-json-format all-tokens)]
    (export-tokens-file transformed-tokens-json)))

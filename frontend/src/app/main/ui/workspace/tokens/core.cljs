;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.core
  (:require
   [app.common.data :as d]
   [app.main.ui.workspace.tokens.token :as wtt]))

;; Helpers ---------------------------------------------------------------------

(defn resolve-token-value [{:keys [value resolved-value] :as _token}]
  (or
   resolved-value
   (d/parse-double value)))

(defn maybe-resolve-token-value [{:keys [value] :as token}]
  (when value (resolve-token-value token)))

(defn tokens->select-options [{:keys [shape tokens attributes selected-attributes]}]
  (map
   (fn [{:keys [name] :as token}]
     (cond-> (assoc token :label name)
       (wtt/token-applied? token shape (or selected-attributes attributes)) (assoc :selected? true)))
   tokens))

(defn tokens-name-map->select-options [{:keys [shape tokens attributes selected-attributes]}]
  (map
   (fn [[_k {:keys [name] :as token}]]
     (cond-> (assoc token :label name)
       (wtt/token-applied? token shape (or selected-attributes attributes)) (assoc :selected? true)))
   tokens))

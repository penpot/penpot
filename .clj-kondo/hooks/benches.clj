;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns hooks.benches
  (:require [clj-kondo.hooks-api :as api]))

(defn defscene
  "Analyzes scene options and its compiled builder body."
  [{:keys [node]}]
  (let [[_ _ opts argv & body] (:children node)]
    (if (and argv (api/vector-node? argv))
      {:node (api/list-node [(api/token-node (quote do)) opts
                             (api/list-node (into [(api/token-node (quote fn)) argv] body))])}
      {:node node})))

(defn defcase
  "Analyzes case options and its compiled operation body."
  [{:keys [node]}]
  (let [[_ _ opts argv & body] (:children node)]
    (if (and (seq body) argv (api/vector-node? argv))
      {:node (api/list-node [(api/token-node (quote do)) opts
                             (api/list-node (into [(api/token-node (quote fn)) argv] body))])}
      {:node node})))

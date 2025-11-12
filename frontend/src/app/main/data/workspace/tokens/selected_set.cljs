;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.selected-set
  "The user selected token set in the ui, stored by the `:name` of the set.
  Will default to the first set."
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.helpers :as dsh]))

(defn- get-selected-token-set-id [state]
  (or (get-in state [:workspace-tokens :selected-token-set-id])
      (some-> (dsh/lookup-file-data state)
              (get :tokens-lib)
              (ctob/get-sets)
              (first)
              (ctob/get-id))))

(defn get-selected-token-set [state]
  (when-let [set-id (get-selected-token-set-id state)]
    (some-> (dsh/lookup-file-data state)
            (get :tokens-lib)
            (ctob/get-set set-id))))

(defn get-token-in-selected-set [state token-id]
  (when-let [set-id (get-selected-token-set-id state)]
    (some-> (dsh/lookup-file-data state)
            (get :tokens-lib)
            (ctob/get-token set-id token-id))))

(defn get-all-tokens-in-selected-set [state]
  (when-let [set-id (get-selected-token-set-id state)]
    (some-> (dsh/lookup-file-data state)
            (get :tokens-lib)
            (ctob/get-tokens set-id))))

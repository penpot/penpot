(ns app.main.data.workspace.tokens.selected-set
  "The user selected token set in the ui, stored by the `:name` of the set.
  Will default to the first set."
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.common :as dwtc]
   [potok.v2.core :as ptk]))

(defn assoc-selected-token-set-name [state set-name]
  (assoc-in state [:workspace-local :selected-token-set-name] set-name))

(defn get-selected-token-set-name [state]
  (or (get-in state [:workspace-local :selected-token-set-name])
      (some-> (dwtc/get-workspace-tokens-lib state)
              (ctob/get-sets)
              (first)
              :name)))

(defn get-selected-token-set [state]
  (when-let [set-name (get-selected-token-set-name state)]
    (some-> (dwtc/get-workspace-tokens-lib state)
            (ctob/get-set set-name))))

(defn get-selected-token-set-token [state token-name]
  (some-> (get-selected-token-set state)
          (ctob/get-token token-name)))

(defn get-selected-token-set-tokens [state]
  (some-> (get-selected-token-set state)
          :tokens))

(defn set-selected-token-set-name
  [set-name]
  (ptk/reify ::set-selected-token-set-path-from-name
    ptk/UpdateEvent
    (update [_ state]
      (assoc-selected-token-set-name state set-name))))

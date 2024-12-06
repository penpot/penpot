(ns app.main.ui.workspace.tokens.token-set
  (:require
   [app.common.types.tokens-lib :as ctob]))

(defn get-workspace-tokens-lib [state]
  (get-in state [:workspace-data :tokens-lib]))

;; Themes ----------------------------------------------------------------------

(defn get-active-theme-ids [state]
  (get-in state [:workspace-data :token-active-themes] #{}))

(defn get-temp-theme-id [state]
  (get-in state [:workspace-data :token-theme-temporary-id]))

(defn update-theme-id
  [state]
  (let [active-themes (get-active-theme-ids state)
        temporary-theme-id (get-temp-theme-id state)]
    (cond
      (empty? active-themes) temporary-theme-id
      (= 1 (count active-themes)) (first active-themes)
      :else temporary-theme-id)))

(defn get-workspace-token-theme [id state]
  (get-in state [:workspace-data :token-themes-index id]))

(defn add-token-set-to-token-theme [token-set-id token-theme]
  (update token-theme :sets conj token-set-id))

 ;; Sets ------------------------------------------------------------------------

(defn get-active-theme-sets-tokens-names-map [state]
  (when-let [lib (get-workspace-tokens-lib state)]
    (ctob/get-active-themes-set-tokens lib)))

;; === Set selection

(defn get-selected-token-set-id [state]
  (or (get-in state [:workspace-local :selected-token-set-id])
      (some-> (get-workspace-tokens-lib state)
              (ctob/get-sets)
              (first)
              (ctob/get-set-path))))

(defn get-selected-token-set-node [state]
  (when-let [path (some-> (get-selected-token-set-id state)
                          (ctob/split-token-set-path))]
    (some-> (get-workspace-tokens-lib state)
            (ctob/get-in-set-tree path))))

(defn get-selected-token-set [state]
  (let [set-node (get-selected-token-set-node state)]
    (when (instance? ctob/TokenSet set-node)
      set-node)))

(defn get-selected-token-set-group [state]
  (let [set-node (get-selected-token-set-node state)]
    (when (and set-node (not (instance? ctob/TokenSet set-node)))
      set-node)))

(defn get-selected-token-set-tokens [state]
  (some-> (get-selected-token-set state)
          :tokens))

(defn token-group-selected? [state]
  (some? (get-selected-token-set-group state)))

(defn assoc-selected-token-set-id [state id]
  (assoc-in state [:workspace-local :selected-token-set-id] id))

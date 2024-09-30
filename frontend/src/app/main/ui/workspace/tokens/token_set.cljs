(ns app.main.ui.workspace.tokens.token-set
  (:require
   [app.common.data :refer [ordered-map]]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.token :as wtt]
   [clojure.set :as set]))

(defn get-workspace-tokens-lib [state]
  (get-in state [:workspace-data :tokens-lib]))

;; Themes ----------------------------------------------------------------------

(defn get-workspace-themes-index [state]
  (get-in state [:workspace-data :token-themes-index] {}))

(defn get-workspace-token-set-groups [state]
  (get-in state [:workspace-data :token-set-groups]))

(defn get-active-theme-ids [state]
  (get-in state [:workspace-data :token-active-themes] #{}))

(defn get-temp-theme-id [state]
  (get-in state [:workspace-data :token-theme-temporary-id]))

(defn get-active-theme-ids-or-fallback [state]
  (let [active-theme-ids (get-active-theme-ids state)
        temp-theme-id (get-temp-theme-id state)]
    (cond
      (seq active-theme-ids) active-theme-ids
      temp-theme-id #{temp-theme-id})))

(defn get-active-set-ids [state]
  (let [active-theme-ids (get-active-theme-ids-or-fallback state)
        themes-index (get-workspace-themes-index state)
        active-set-ids (reduce
                        (fn [acc cur]
                          (if-let [sets (get-in themes-index [cur :sets])]
                            (set/union acc sets)
                            acc))
                        #{} active-theme-ids)]
    active-set-ids))

(defn get-ordered-active-set-ids [state]
  (let [active-set-ids (get-active-set-ids state)
        token-set-groups (get-workspace-token-set-groups state)]
    (filter active-set-ids token-set-groups)))

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

(defn get-workspace-sets [state]
  (get-in state [:workspace-data :token-sets-index]))

(defn get-token-set [set-id state]
  (some-> (get-workspace-sets state)
          (get set-id)))

(defn get-workspace-token-set-tokens [set-id state]
  (-> (get-token-set set-id state)
      :tokens))


(defn get-active-theme-sets-tokens-names-map [state]
  (when-let [lib (get-workspace-tokens-lib state)]
    (ctob/get-active-themes-set-tokens lib)))

;; === Set selection

(defn get-selected-token-set-id [state]
  (or (get-in state [:workspace-local :selected-token-set-id])
      (some-> (get-workspace-tokens-lib state)
              (ctob/get-sets)
              (first)
              (:name))))

(defn get-selected-token-set [state]
  (when-let [id (get-selected-token-set-id state)]
    (some-> (get-workspace-tokens-lib state)
            (ctob/get-set id))))

(defn get-selected-token-set-tokens [state]
  (some-> (get-selected-token-set state)
          :tokens))

(defn assoc-selected-token-set-id [state id]
  (assoc-in state [:workspace-local :selected-token-set-id] id))

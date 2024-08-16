(ns app.main.ui.workspace.tokens.token-set)

;; Themes ----------------------------------------------------------------------

(defn get-theme-group [theme]
  (:group theme))

(defn get-workspace-themes [state]
  (get-in state [:workspace-data :token-themes] []))

(defn get-workspace-themes-index [state]
  (get-in state [:workspace-data :token-themes-index] {}))

(defn get-workspace-ordered-themes [state]
  (let [themes (get-workspace-themes state)
        themes-index (get-workspace-themes-index state)]
    (->> (map #(get themes-index %) themes)
         (group-by :group))))

(defn get-active-theme-ids [state]
  (get-in state [:workspace-data :token-active-themes]))

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

(defn toggle-token-set-to-token-theme [token-set-id token-theme]
  (update token-theme :sets #(if (get % token-set-id)
                               (disj % token-set-id)
                               (conj % token-set-id))))

(defn token-set-enabled-in-theme? [set-id theme]
  (some? (get-in theme [:sets set-id])))

 ;; Sets ------------------------------------------------------------------------

(defn get-workspace-tokens [state]
  (get-in state [:workspace-data :tokens]))

(defn get-workspace-sets [state]
  (get-in state [:workspace-data :token-sets-index]))

(defn get-token-set [set-id state]
  (some-> (get-workspace-sets state)
          (get set-id)))

(def default-token-set-name "Global")

(defn create-global-set [])

(defn add-token-to-token-set [token token-set]
  (update token-set :items conj (:id token)))

(defn get-selected-token-set-id [state]
  (or (get-in state [:workspace-local :selected-token-set-id])
      (get-in state [:workspace-data :token-set-groups 0])))

(defn get-selected-token-set [state]
  (when-let [id (get-selected-token-set-id state)]
    (get-token-set id state)))

(defn get-selected-token-set-tokens [state]
  (when-let [token-set (get-selected-token-set state)]
    (let [tokens (or (get-workspace-tokens state) {})]
      (select-keys tokens (:tokens token-set)))))

(defn get-token-set-tokens [{:keys [tokens] :as token-set} file]
  (map #(get-in file [:data :tokens %]) tokens))

(defn assoc-selected-token-set-id [state id]
  (assoc-in state [:workspace-local :selected-token-set-id] id))

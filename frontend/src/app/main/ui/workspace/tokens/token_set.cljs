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

(defn theme-selected? [theme]
  (= :enabled (:selected theme)))

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

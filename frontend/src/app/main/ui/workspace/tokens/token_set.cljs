(ns app.main.ui.workspace.tokens.token-set
  (:require
    [clojure.set :as set]))

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
  (get-in state [:workspace-data :token-active-themes] #{}))

(defn get-temp-theme-id [state]
  (get-in state [:workspace-data :token-theme-temporary-id]))

(defn get-active-set-ids [state]
  (let [active-theme-ids (get-active-theme-ids state)
        themes-index (get-workspace-themes-index state)
        active-set-ids (reduce
                        (fn [acc cur]
                          (if-let [sets (get-in themes-index [cur :sets])]
                            (set/union acc sets)
                            acc))
                        #{} active-theme-ids)]
    active-set-ids))

(defn theme-ids-with-group
  "Returns set of theme-ids that share the same `:group` property as the theme with `theme-id`.
  Will also return matching theme-ids without a `:group` property."
  [theme-id state]
  (let [themes (get-workspace-themes-index state)
        theme-group (get-in themes [theme-id :group])
        same-group-theme-ids (->> themes
                                  (eduction
                                   (map val)
                                   (filter #(= (:group %) theme-group))
                                   (map :id))
                                  (into #{}))]
    same-group-theme-ids))

(defn toggle-active-theme-id
  "Toggle a `theme-id` by checking `:token-active-themes`.
  De-activate all theme-ids that have the same group as `theme-id` when activating `theme-id`.
  Ensures that the temporary theme id is selected when the resulting set is empty."
  [theme-id state]
  (let [temp-theme-id-set (some->> (get-temp-theme-id state) (conj #{}))
        active-theme-ids (get-active-theme-ids state)
        add? (not (get active-theme-ids theme-id))
        ;; Deactivate themes with the same group when activating a theme
        same-group-ids (when add? (theme-ids-with-group theme-id state))
        theme-ids-without-same-group (set/difference active-theme-ids
                                                     same-group-ids
                                                     temp-theme-id-set)
        new-themes (if add?
                     (conj theme-ids-without-same-group theme-id)
                     (disj theme-ids-without-same-group theme-id))]
    (if (empty? new-themes)
      (or temp-theme-id-set #{})
      new-themes)))

(defn get-active-theme-ids-or-temp-theme-id
  [state]
  (let [active-theme-ids (get-active-theme-ids state)]
    (if (seq active-theme-ids)
      active-theme-ids
      (get-temp-theme-id state))))

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

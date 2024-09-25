(ns app.main.ui.workspace.tokens.token-set
  (:require
   [app.common.data :refer [ordered-map]]
   [app.common.data.macros :as dm]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.token :as wtt]
   [clojure.set :as set]))

(defn get-workspace-tokens-lib [state]
  (get-in state [:workspace-data :tokens-lib]))

;; Themes ----------------------------------------------------------------------

(defn get-workspace-themes [state]
  (get-in state [:workspace-data :token-themes] []))

(defn get-workspace-theme [id state]
  (get-in state [:workspace-data :token-themes-index id]))

(defn get-workspace-themes-index [state]
  (get-in state [:workspace-data :token-themes-index] {}))

(defn get-workspace-theme-groups [state]
  (reduce
   (fn [acc {:keys [group]}]
     (if group
       (conj acc group)
       acc))
   #{} (vals (get-workspace-themes-index state))))

(defn get-workspace-token-set-groups [state]
  (get-in state [:workspace-data :token-set-groups]))

(defn get-workspace-ordered-themes [state]
  (let [themes (get-workspace-themes state)
        themes-index (get-workspace-themes-index state)]
    (->> (map #(get themes-index %) themes)
         (group-by :group))))

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
  Deactivate all theme-ids that have the same group as `theme-id` when activating `theme-id`.
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

 ;; Sets ------------------------------------------------------------------------

(defn get-workspace-sets [state]
  (get-in state [:workspace-data :token-sets-index]))

(defn get-workspace-ordered-sets [state]
  ;; TODO Include groups
  (let [top-level-set-ids (get-in state [:workspace-data :token-set-groups])
        token-sets (get-workspace-sets state)]
    (->> (map (fn [id] [id (get token-sets id)]) top-level-set-ids)
         (into (ordered-map)))))

(defn get-workspace-ordered-sets-tokens [state]
  (let [sets (get-workspace-ordered-sets state)]
    (reduce
     (fn [acc [_ {:keys [tokens] :as sets}]]
       (reduce (fn [acc' token-id]
                 (if-let [token (wtt/get-workspace-token token-id state)]
                   (assoc acc' (wtt/token-identifier token) token)
                   acc'))
            acc tokens))
     {} sets)))

(defn get-token-set [set-id state]
  (some-> (get-workspace-sets state)
          (get set-id)))

(defn get-workspace-token-set-tokens [set-id state]
  (-> (get-token-set set-id state)
      :tokens))


(defn get-active-theme-sets-tokens-names-map [state]
  (let [active-set-ids (get-ordered-active-set-ids state)]
    (reduce
     (fn [names-map-acc set-id]
       (let [token-ids (get-workspace-token-set-tokens set-id state)]
         (reduce
          (fn [acc token-id]
            (if-let [token (wtt/get-workspace-token token-id state)]
              (assoc acc (wtt/token-identifier token) token)
              acc))
          names-map-acc token-ids)))
     (ordered-map) active-set-ids)))

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
          (ctob/get-tokens)))

(defn assoc-selected-token-set-id [state id]
  (assoc-in state [:workspace-local :selected-token-set-id] id))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.library-edit
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.tokens :as clt]
   [app.common.path-names :as cpn]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.util.i18n :refer [tr]]
   [app.util.storage :as storage]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(declare set-selected-token-set-id)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Getters
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: lookup rename

(defn get-tokens-lib
  [state]
  (-> (dsh/lookup-file-data state)
      (get :tokens-lib)))

(defn lookup-token-set
  ([state]
   (when-let [selected (dm/get-in state [:workspace-tokens :selected-token-set-id])]
     (lookup-token-set state selected)))
  ([state id]
   (some-> (get-tokens-lib state)
           (ctob/get-set id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO HYMA: Copied over from workspace.cljs
(defn update-shape
  [id attrs]
  (assert (uuid? id) "expected valid uuid for `id`")

  (let [attrs (cts/check-shape-attrs attrs)]
    (ptk/reify ::update-shape
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/of (dwsh/update-shapes [id] #(merge % attrs)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS TREE - Type folders
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Helper functions for localStorage persistence
(defn- get-unfolded-token-types-from-storage
  [file-id set-id]
  (get-in storage/user [:app.main.ui.workspace.tokens/unfolded-token-types file-id set-id] #{}))

(defn- save-unfolded-token-types-in-storage
  [file-id set-id types]
  (swap! storage/user update :app.main.ui.workspace.tokens/unfolded-token-types
         assoc-in [file-id set-id] (vec types)))

;; Helper functions for app state persistence
(defn- make-unfolded-token-types-state
  [file-id set-id types]
  {:file-id file-id
   :set-id set-id
   :types (set (or types #{}))})

(defn- get-unfolded-token-types-from-state
  [state]
  (let [value (get-in state [:workspace-tokens :unfolded-token-types])]
    (or (:types value) #{})))

(defn restore-unfolded-token-types
  "Loads unfolded token types from localStorage for the current file and set"
  []
  (ptk/reify ::restore-unfolded-token-types
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            set-id  (get-in state [:workspace-tokens :selected-token-set-id])
            stored  (get-unfolded-token-types-from-storage file-id set-id)]
        (assoc-in state
                  [:workspace-tokens :unfolded-token-types]
                  (make-unfolded-token-types-state file-id set-id stored))))))

(defn open-token-type
  ([types type]
   (conj (or types #{}) type))
  ([type]
   (ptk/reify ::open-token-type
     ptk/UpdateEvent
     (update [_ state]
       (let [file-id   (:current-file-id state)
             set-id    (get-in state [:workspace-tokens :selected-token-set-id])
             types     (get-unfolded-token-types-from-state state)
             new-types (open-token-type types type)
             new-state (assoc-in state
                                 [:workspace-tokens :unfolded-token-types]
                                 (make-unfolded-token-types-state file-id set-id new-types))]
         (save-unfolded-token-types-in-storage file-id set-id
                                               new-types)
         new-state)))))

(defn close-token-type
  ([types type]
   (disj (or types #{}) type))
  ([type]
   (ptk/reify ::close-token-type
     ptk/UpdateEvent
     (update [_ state]
       (let [file-id   (:current-file-id state)
             set-id    (get-in state [:workspace-tokens :selected-token-set-id])
             types     (get-unfolded-token-types-from-state state)
             new-types (close-token-type types type)
             new-state (assoc-in state
                                 [:workspace-tokens :unfolded-token-types]
                                 (make-unfolded-token-types-state file-id set-id new-types))]
         (save-unfolded-token-types-in-storage file-id set-id
                                               new-types)
         new-state)))))

(defn
  toggle-token-type
  [type]
  (ptk/reify ::toggle-token-type
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id   (:current-file-id state)
            set-id    (get-in state [:workspace-tokens :selected-token-set-id])
            types     (get-unfolded-token-types-from-state state)
            new-types (if (contains? types type)
                        (close-token-type types type)
                        (open-token-type types type))
            new-state (assoc-in state
                                [:workspace-tokens :unfolded-token-types]
                                (make-unfolded-token-types-state file-id set-id new-types))]
        (save-unfolded-token-types-in-storage file-id set-id
                                              new-types)
        new-state))))

(defn clear-tokens-types
  []
  (ptk/reify ::clear-tokens-types
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            set-id  (get-in state [:workspace-tokens :selected-token-set-id])]
        (save-unfolded-token-types-in-storage file-id set-id #{})
        (assoc-in state
                  [:workspace-tokens :unfolded-token-types]
                  (make-unfolded-token-types-state file-id set-id #{}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS TREE - Toggle tree nodes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- remove-path
  [path paths]
  (->> paths
       (remove #(= % path))
       vec))

(defn add-path
  [path paths]
  (vec (conj paths path)))

(defn clear-tokens-paths
  []
  (ptk/reify ::clear-tokens-paths
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-tokens :folded-token-paths] []))))

(defn toggle-token-path
  [path]
  (ptk/reify ::toggle-token-path
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-tokens :folded-token-paths]
                 (fn [paths]
                   (let [paths (or paths [])]
                     (if (some #(= % path) paths)
                       (remove-path path paths)
                       (add-path path paths))))))))

(defn toggle-nested-token-path
  [token-type new-name]
  (ptk/reify ::toggle-nested-token-path
    ptk/UpdateEvent
    (update [_ state]
      (let [type-str (name token-type)
            segments (str/split new-name ".")
            n-groups (dec (count segments))]
        (if (pos? n-groups)
          (update-in state [:workspace-tokens :folded-token-paths]
                     (fn [paths]
                       (reduce (fn [ps i]
                                 (remove-path (str type-str "." (str/join "." (take i segments))) ps))
                               (or paths [])
                               (range 1 (inc n-groups)))))
          state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-token-theme
  [token-theme]
  (let [new-token-theme token-theme]
    (ptk/reify ::create-token-theme
      ptk/WatchEvent
      (watch [it state _]
        (let [data       (dsh/lookup-file-data state)
              tokens-lib (get data :tokens-lib)]

          (if (and tokens-lib (ctob/get-theme tokens-lib (ctob/get-id token-theme)))
            (rx/of (ntf/show {:content (tr "errors.token-theme-already-exists")
                              :type :toast
                              :level :error
                              :timeout 9000}))
            (let [changes (-> (pcb/empty-changes it)
                              (pcb/with-library-data data)
                              (pcb/set-token-theme (ctob/get-id new-token-theme)
                                                   new-token-theme))]
              (rx/of (dch/commit-changes changes)))))))))

(defn update-token-theme
  [id token-theme]
  (ptk/reify ::update-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data             (dsh/lookup-file-data state)
            tokens-lib       (get data :tokens-lib)]
        (if (and (not= id (ctob/get-id token-theme))
                 (ctob/get-theme tokens-lib (ctob/get-id token-theme)))
          (rx/of (ntf/show {:content (tr "errors.token-theme-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token-theme (ctob/get-id token-theme) token-theme))]
            (rx/of (dch/commit-changes changes))))))))

(defn set-token-theme-active
  [id active?]
  (assert (uuid? id) "expected a uuid for `id`")
  (assert (boolean? active?) "expected a boolean for `active?`")
  (ptk/reify ::set-token-theme-active
    ptk/WatchEvent
    (watch [_ state _]
      (let [data        (dsh/lookup-file-data state)
            tokens-lib  (get-tokens-lib state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-set-active-token-theme tokens-lib id active?))]

        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

(defn toggle-token-theme-active
  [id]
  (ptk/reify ::toggle-token-theme-active
    ptk/WatchEvent
    (watch [it state _]
      (let [data (dsh/lookup-file-data state)
            tokens-lib (get-tokens-lib state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (clt/generate-toggle-token-theme tokens-lib id))]
        (rx/of
         (dch/commit-changes changes)
         (dwtp/propagate-workspace-tokens))))))

(defn delete-token-theme
  [id]
  (ptk/reify ::delete-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-theme id nil))]
        (rx/of
         (dch/commit-changes changes)
         (dwtp/propagate-workspace-tokens))))))

(defn create-token-set
  [token-set]
  (assert (ctob/token-set? token-set) "a token set is required") ;; TODO should check token-set-schema?
  (ptk/reify ::create-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-set (ctob/get-id token-set) token-set))]
        (rx/of (set-selected-token-set-id (ctob/get-id token-set))
               (dch/commit-changes changes))))))

(defn rename-token-set
  [token-set new-name]
  (assert (ctob/token-set? token-set) "a token set is required") ;; TODO should check token-set-schema after renaming?
  (assert (string? new-name) "a new name is required") ;; TODO should assert normalized-set-name?
  (ptk/reify ::update-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/rename-token-set (ctob/get-id token-set) new-name))]
        (rx/of (set-selected-token-set-id (ctob/get-id token-set))
               (dch/commit-changes changes))))))

(defn rename-token-set-group
  [set-group-path set-group-fname]
  (ptk/reify ::rename-token-set-group
    ptk/WatchEvent
    (watch [it _state _]
      (let [changes (-> (pcb/empty-changes it)
                        (pcb/rename-token-set-group set-group-path set-group-fname))]
        (rx/of
         (dch/commit-changes changes))))))

(defn duplicate-token-set
  [id]
  (ptk/reify ::duplicate-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data       (dsh/lookup-file-data state)
            tokens-lib (get data :tokens-lib)
            suffix     (tr "workspace.tokens.duplicate-suffix")]

        (when-let [token-set (ctob/duplicate-set id tokens-lib {:suffix suffix})]
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token-set (ctob/get-id token-set) token-set))]
            (rx/of (set-selected-token-set-id (ctob/get-id token-set))
                   (dch/commit-changes changes))))))))

(defn set-enabled-token-set
  [name enabled?]
  (assert (string? name) "expected a string for `name`")
  (assert (boolean? enabled?) "expected a boolean for `enabled?`")
  (ptk/reify ::set-enabled-token-set
    ptk/WatchEvent
    (watch [_ state _]
      (let [data    (dsh/lookup-file-data state)
            tlib    (get-tokens-lib state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-set-enabled-token-set tlib name enabled?))]

        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

(defn toggle-token-set
  [name]
  (assert (string? name) "expected a string for `name`")
  (ptk/reify ::toggle-token-set
    ptk/WatchEvent
    (watch [_ state _]
      (let [data    (dsh/lookup-file-data state)
            tlib    (get-tokens-lib state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-toggle-token-set tlib name))]

        (rx/of
         (dch/commit-changes changes)
         (dwtp/propagate-workspace-tokens))))))

(defn toggle-token-set-group
  [group-path]
  (ptk/reify ::toggle-token-set-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-toggle-token-set-group (get-tokens-lib state) group-path))]

        (rx/of
         (dch/commit-changes changes)
         (dwtp/propagate-workspace-tokens))))))

(defn import-tokens-lib
  [lib]
  (ptk/reify ::import-tokens-lib
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-tokens-lib lib))]
        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

(defn delete-token-set
  [id]
  (ptk/reify ::delete-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-set id nil))]
        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

(defn delete-token-set-group
  [path]
  (ptk/reify ::delete-token-set-group
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (clt/generate-delete-token-set-group (get-tokens-lib state) path))]
        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

(defn drop-error
  [{:keys [error to-path]}]
  (ptk/reify ::drop-error
    ptk/WatchEvent
    (watch [_ _ _]
      (let [content (case error
                      :path-exists (tr "errors.token-set-exists-on-drop" to-path)
                      :parent-to-child (tr "errors.drop-token-set-parent-to-child")
                      nil)]
        (when content
          (rx/of
           (ntf/show {:content content
                      :type :toast
                      :level :error
                      :timeout 9000})))))))

;; FIXME: add schema for params

(defn drop-token-set-group
  [drop-opts]
  (ptk/reify ::drop-token-set-group
    ptk/WatchEvent
    (watch [it state _]
      (try
        (when-let [changes (clt/generate-move-token-set-group (pcb/empty-changes it) (get-tokens-lib state) drop-opts)]
          (rx/of
           (dch/commit-changes changes)
           (dwtp/propagate-workspace-tokens)))
        (catch :default e
          (rx/of
           (drop-error (ex-data e))))))))

;; FIXME: add schema for params

(defn drop-token-set
  [params]
  (ptk/reify ::drop-token-set
    ptk/WatchEvent
    (watch [it state _]
      (try
        (let [tokens-lib (get-tokens-lib state)
              changes    (-> (pcb/empty-changes it)
                             (clt/generate-move-token-set tokens-lib params))]
          (rx/of (dch/commit-changes changes)
                 (dwtp/propagate-workspace-tokens)))
        (catch :default cause
          (rx/of (drop-error (ex-data cause))))))))

(defn- create-token-with-set
  "A special case when a first token is created and no set exists"
  [token]
  (ptk/reify ::create-token-and-set
    ptk/WatchEvent
    (watch [_ state _]
      (let [data
            (dsh/lookup-file-data state)

            set-name
            "Global"

            token-set
            (ctob/make-token-set :name set-name)

            hidden-theme
            (ctob/make-hidden-theme)

            hidden-theme-with-set
            (ctob/enable-set hidden-theme set-name)

            changes
            (-> (pcb/empty-changes)
                (pcb/with-library-data data)
                (pcb/set-token-set (ctob/get-id token-set) token-set)
                (pcb/set-token (ctob/get-id token-set) (:id token) token)
                (pcb/set-token-theme (ctob/get-id hidden-theme)
                                     hidden-theme-with-set)
                (pcb/set-active-token-themes #{ctob/hidden-theme-path}))]
        (rx/of (dch/commit-changes changes)
               (set-selected-token-set-id (ctob/get-id token-set)))))))

(defn create-token
  ([token] (create-token nil token))
  ([set-id token]
   (ptk/reify ::create-token
     ptk/WatchEvent
     (watch [it state _]
       (if-let [token-set (if set-id
                            (lookup-token-set state set-id)
                            (lookup-token-set state))]
         (let [data    (dsh/lookup-file-data state)
               token-type (:type token)
               changes (-> (pcb/empty-changes it)
                           (pcb/with-library-data data)
                           (pcb/set-token (ctob/get-id token-set)
                                          (:id token)
                                          token))]

           (rx/of (dch/commit-changes changes)
                  (ev/event (-> {::ev/name "create-token" :type token-type}
                                (merge (meta it))))))

         (rx/of (create-token-with-set token)))))))

(defn bulk-create-tokens
  [set-id token-ids type node new-node-name]
  (assert (uuid? set-id) "expected uuid for `set-id`")
  (assert (every? uuid? token-ids) "expected a collection of uuids for `token-ids`")
  (assert (keyword? type) "expected keyword for `type`")
  (assert (string? new-node-name) "expected string for `new-node-name`")

  (ptk/reify ::bulk-create-tokens
    ptk/WatchEvent
    (watch [it state _]
      (let [token-set (lookup-token-set state set-id)
            data    (dsh/lookup-file-data state)
            changes (reduce (fn [changes token-id]
                              (let [token     (-> (get-tokens-lib state)
                                                  (ctob/get-token (ctob/get-id token-set) token-id))
                                    new-name (->
                                              (cpn/split-path (:name token) :separator ".")
                                              (assoc (:depth node) new-node-name)
                                              (cpn/join-path :separator "." :with-spaces? false))
                                    token'    (->> (merge token {:name new-name
                                                                 :id (cthi/new-id! (:name new-name))})
                                                   (into {})
                                                   (ctob/make-token))]
                                (pcb/set-token changes (ctob/get-id token-set) (:id token') token')))
                            (-> (pcb/empty-changes it)
                                (pcb/with-library-data data))
                            token-ids)]
        (rx/of
         (dch/commit-changes changes)
         (ptk/data-event ::ev/event {::ev/name "bulk-create-tokens" :type type}))))))

(defn update-token
  ([id params] (update-token nil id params))
  ([set-id id params]
   (assert (uuid? id) "expected uuid for `id`")

   (ptk/reify ::update-token
     ptk/WatchEvent
     (watch [it state _]
       (let [token-set (if set-id
                         (lookup-token-set state set-id)
                         (lookup-token-set state))
             data      (dsh/lookup-file-data state)
             token     (-> (get-tokens-lib state)
                           (ctob/get-token (ctob/get-id token-set) id))
             token'    (->> (merge token params)
                            (into {})
                            (ctob/make-token))
             token-type (:type token)
             changes   (-> (pcb/empty-changes it)
                           (pcb/with-library-data data)
                           (pcb/set-token (ctob/get-id token-set)
                                          id
                                          token'))]
         (toggle-token-path (str (name token-type) "." (:name token)))
         (rx/of (dch/commit-changes changes)
                (ev/event (-> {::ev/name "edit-token" :type token-type}
                              (merge (meta it))))))))))

(defn bulk-update-tokens
  [set-id token-ids type old-path new-path & {:keys [undo-group]}]
  (dm/assert! (uuid? set-id))
  (dm/assert! (every? uuid? token-ids))
  (ptk/reify ::bulk-update-tokens
    ptk/WatchEvent
    (watch [it state _]
      (let [token-set (if set-id
                        (lookup-token-set state set-id)
                        (lookup-token-set state))
            data    (dsh/lookup-file-data state)
            changes (reduce (fn [changes token-id]
                              (let [token     (-> (get-tokens-lib state)
                                                  (ctob/get-token (ctob/get-id token-set) token-id))
                                    new-name (str/replace (:name token) old-path new-path)
                                    token'    (->> (merge token {:name new-name})
                                                   (into {})
                                                   (ctob/make-token))]
                                (pcb/set-token changes (ctob/get-id token-set) token-id token')))
                            (-> (pcb/empty-changes it)
                                (pcb/with-library-data data))

                            token-ids)

            changes (cond-> changes (some? undo-group) (assoc :undo-group undo-group))]
        (toggle-token-path (str (name type) "." old-path))
        (toggle-token-path (str (name type) "." new-path))
        (rx/of (dch/commit-changes changes)
               (ptk/data-event ::ev/event {::ev/name "bulk-update-tokens" :type type}))))))

(defn delete-token
  [set-id token-id]
  (dm/assert! (uuid? set-id))
  (dm/assert! (uuid? token-id))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            token-set (if set-id
                        (lookup-token-set state set-id)
                        (lookup-token-set state))
            token     (-> (get-tokens-lib state)
                          (ctob/get-token (ctob/get-id token-set) token-id))
            token-type (:type token)

            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token set-id token-id nil))]
        (rx/of (dch/commit-changes changes)
               (ev/event (-> {::ev/name "delete-token" :type token-type}
                             (merge (meta it)))))))))

(defn bulk-delete-tokens
  [set-id token-ids]
  (dm/assert! (uuid? set-id))
  (dm/assert! (every? uuid? token-ids))
  (ptk/reify ::bulk-delete-tokens
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (reduce (fn [changes token-id]
                              (pcb/set-token changes set-id token-id nil))
                            (-> (pcb/empty-changes it)
                                (pcb/with-library-data data))
                            token-ids)]
        (rx/of (dch/commit-changes changes)
               (ev/event {::ev/name "delete-token-node"}))))))

(defn duplicate-token
  [token-id]
  (dm/assert! (uuid? token-id))
  (ptk/reify ::duplicate-token
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [token-set (lookup-token-set state)]
        (when-let [tokens-lib (get-tokens-lib state)]
          (when-let [token (ctob/get-token tokens-lib
                                           (ctob/get-id token-set)
                                           token-id)]
            (let [tokens (vals (ctob/get-tokens tokens-lib (ctob/get-id token-set)))
                  unames (map :name tokens)     ;; TODO: add function duplicate-token in tokens-lib
                  ;; "copy" is intentionally not translated here. Token names are validated
                  ;; against a restricted set of allowed characters (currently English-compatible),
                  ;; so translating this suffix could introduce invalid characters and break
                  ;; token name validation.
                  suffix "copy"
                  copy-name (cfh/generate-unique-name (:name token) unames :suffix suffix)
                  new-token (-> token
                                (ctob/reid (uuid/next))
                                (ctob/rename copy-name))]
              (rx/of (create-token new-token)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKEN UI OPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defn assign-token-context-menu
  [{:keys [position] :as params}]

  (when params
    (assert (gpt/point? position) "expected a point instance for `position` param"))

  (ptk/reify ::show-token-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (if params
        (update state :workspace-tokens assoc :token-context-menu params)
        (update state :workspace-tokens dissoc :token-context-menu)))))

(defn assign-token-node-context-menu
  [{:keys [position] :as params}]

  (when params
    (assert (gpt/point? position) "expected a point instance for `position` param"))

  (ptk/reify ::show-token-node-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (if params
        (update state :workspace-tokens assoc :token-node-context-menu params)
        (update state :workspace-tokens dissoc :token-node-context-menu)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKEN-SET UI OPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assign-token-set-context-menu
  [{:keys [position] :as params}]
  (when params
    (assert (gpt/point? position) "expected valid point for `position` param"))

  (ptk/reify ::assign-token-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (if params
        (update state :workspace-tokens assoc :token-set-context-menu params)
        (update state :workspace-tokens dissoc :token-set-context-menu)))))

(defn set-selected-token-set-id
  [id]
  (ptk/reify ::set-selected-token-set-id
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            stored  (get-unfolded-token-types-from-storage file-id id)]
        (-> state
            (update :workspace-tokens assoc :selected-token-set-id id)
            (assoc-in [:workspace-tokens :unfolded-token-types]
                      (make-unfolded-token-types-state file-id id stored)))))))

(defn start-token-set-edition
  [edition-id]
  ;; Path string for edition of a group, UUID for edition of a set.
  (assert (or (string? edition-id) (uuid? edition-id)) "expected a string or uuid for `edition-id`")

  (ptk/reify ::start-token-set-edition
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens assoc :token-set-edition-id edition-id))))

(defn start-token-set-creation
  [path]
  (assert (vector? path) "expected a vector for `path`")

  (ptk/reify ::start-token-set-creation
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens assoc :token-set-new-path path))))

(defn clear-token-set-edition
  []
  (ptk/reify ::clear-token-set-edition
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens dissoc :token-set-edition-id))))

(defn clear-token-set-creation
  []
  (ptk/reify ::clear-token-set-creation
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens dissoc :token-set-new-path))))

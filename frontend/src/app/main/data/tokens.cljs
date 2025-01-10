;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.tokens
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.logic.tokens :as clt]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.selected-set :as dwts]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.update :as wtu]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO HYMA: Copied over from workspace.cljs
(defn update-shape
  [id attrs]
  (dm/assert!
   "expected valid parameters"
   (and (cts/check-shape-attrs! attrs)
        (uuid? id)))

  (ptk/reify ::update-shape
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of (dwsh/update-shapes [id] #(merge % attrs))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Getters
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-tokens-lib
  [state]
  (-> (dsh/lookup-file-data state)
      (get :tokens-lib)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-token-theme [token-theme]
  (let [new-token-theme token-theme]
    (ptk/reify ::create-token-theme
      ptk/WatchEvent
      (watch [it _ _]
        (let [changes (-> (pcb/empty-changes it)
                          (pcb/add-token-theme new-token-theme))]
          (rx/of
           (dch/commit-changes changes)))))))

(defn update-token-theme [[group name] token-theme]
  (ptk/reify ::update-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-token-theme (some-> tokens-lib (ctob/get-theme group name))
            changes (pcb/update-token-theme (pcb/empty-changes it) token-theme prev-token-theme)]
        (rx/of
         (dch/commit-changes changes))))))

(defn toggle-token-theme-active? [group name]
  (ptk/reify ::toggle-token-theme-active?
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-active-token-themes (some-> tokens-lib
                                             (ctob/get-active-theme-paths))
            active-token-themes (some-> tokens-lib
                                        (ctob/toggle-theme-active? group name)
                                        (ctob/get-active-theme-paths))
            active-token-themes' (if (= active-token-themes #{ctob/hidden-token-theme-path})
                                   active-token-themes
                                   (disj active-token-themes ctob/hidden-token-theme-path))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-active-token-themes active-token-themes' prev-active-token-themes))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn delete-token-theme [group name]
  (ptk/reify ::delete-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)

            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token-theme group name))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn create-token-set [token-set]
  (let [new-token-set (merge
                       {:name "Token Set"
                        :tokens []}
                       token-set)]
    (ptk/reify ::create-token-set
      ptk/WatchEvent
      (watch [it _ _]
        (let [changes (-> (pcb/empty-changes it)
                          (pcb/add-token-set new-token-set))]
          (rx/of
           (dwts/set-selected-token-set-name (:name new-token-set))
           (dch/commit-changes changes)))))))

(defn rename-token-set-group [set-group-path set-group-fname]
  (ptk/reify ::rename-token-set-group
    ptk/WatchEvent
    (watch [it _state _]
      (let [changes (-> (pcb/empty-changes it)
                        (pcb/rename-token-set-group set-group-path set-group-fname))]
        (rx/of
         (dch/commit-changes changes))))))

(defn update-token-set [set-name token-set]
  (ptk/reify ::update-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [prev-token-set (some-> (get-tokens-lib state)
                                   (ctob/get-set set-name))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-token-set token-set prev-token-set))]
        (rx/of
         (dwts/set-selected-token-set-name (:name token-set))
         (dch/commit-changes changes))))))

(defn toggle-token-set [{:keys [token-set-name]}]
  (ptk/reify ::toggle-token-set
    ptk/WatchEvent
    (watch [_ state _]
      (let [changes (clt/generate-toggle-token-set (pcb/empty-changes) (get-tokens-lib state) token-set-name)]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn toggle-token-set-group [group-path]
  (ptk/reify ::toggle-token-set-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [changes (clt/generate-toggle-token-set-group (pcb/empty-changes) (get-tokens-lib state) group-path)]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn import-tokens-lib [lib]
  (ptk/reify ::import-tokens-lib
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            update-token-set-change (some-> lib
                                            (ctob/get-sets)
                                            (first)
                                            (:name)
                                            (dwts/set-selected-token-set-name))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-tokens-lib lib))]
        (rx/of
         (dch/commit-changes changes)
         update-token-set-change
         (wtu/update-workspace-tokens))))))

(defn delete-token-set-path [group? path]
  (ptk/reify ::delete-token-set-path
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token-set-path group? path))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn drop-error [{:keys [error to-path]}]
  (ptk/reify ::drop-error
    ptk/WatchEvent
    (watch [_ _ _]
      (let [content (case error
                      :path-exists (tr "errors.drag-drop.set-exists" to-path)
                      :parent-to-child (tr "errors.drag-drop.parent-to-child")
                      nil)]
        (when content
          (rx/of
           (ntf/show {:content content
                      :type :toast
                      :level :error
                      :timeout 9000})))))))

(defn drop-token-set-group [drop-opts]
  (ptk/reify ::drop-token-set-group
    ptk/WatchEvent
    (watch [it state _]
      (try
        (when-let [changes (clt/generate-move-token-set-group (pcb/empty-changes it) (get-tokens-lib state) drop-opts)]
          (rx/of
           (dch/commit-changes changes)
           (wtu/update-workspace-tokens)))
        (catch js/Error e
          (rx/of
           (drop-error (ex-data e))))))))

(defn drop-token-set [drop-opts]
  (ptk/reify ::drop-token-set
    ptk/WatchEvent
    (watch [it state _]
      (try
        (when-let [changes (clt/generate-move-token-set (pcb/empty-changes it) (get-tokens-lib state) drop-opts)]
          (rx/of
           (dch/commit-changes changes)
           (some-> (get-in changes [:redo-changes 0 :to-path]) (dwts/set-selected-token-set-name))
           (wtu/update-workspace-tokens)))
        (catch js/Error e
          (rx/of
           (drop-error (ex-data e))))))))

(defn update-create-token
  [{:keys [token prev-token-name]}]
  (ptk/reify ::update-create-token
    ptk/WatchEvent
    (watch [_ state _]
      (let [token-set (dwts/get-selected-token-set state)
            token-set-name (or (:name token-set) "Global")
            changes (if (not token-set)
                      ;; No set created add a global set
                      (let [tokens-lib (get-tokens-lib state)
                            token-set (ctob/make-token-set :name token-set-name :tokens {(:name token) token})
                            hidden-theme (ctob/make-hidden-token-theme :sets [token-set-name])
                            active-theme-paths (some-> tokens-lib ctob/get-active-theme-paths)
                            add-to-hidden-theme? (= active-theme-paths #{ctob/hidden-token-theme-path})
                            base-changes (pcb/add-token-set (pcb/empty-changes) token-set)]
                        (cond
                          (not tokens-lib) (-> base-changes
                                               (pcb/add-token-theme hidden-theme)
                                               (pcb/update-active-token-themes #{ctob/hidden-token-theme-path} #{}))

                          add-to-hidden-theme? (let [prev-hidden-theme (ctob/get-theme tokens-lib ctob/hidden-token-theme-group ctob/hidden-token-theme-name)]
                                                 (-> base-changes
                                                     (pcb/update-token-theme (ctob/toggle-set prev-hidden-theme ctob/hidden-token-theme-path) prev-hidden-theme)))

                          :else base-changes))
                      ;; Either update or add token to existing set
                      (if-let [prev-token (ctob/get-token token-set (or prev-token-name (:name token)))]
                        (pcb/update-token (pcb/empty-changes) (:name token-set) token prev-token)
                        (do
                          (st/emit! (ptk/event ::ev/event {::ev/name "create-tokens"}))
                          (pcb/add-token (pcb/empty-changes) (:name token-set) token))))]
        (rx/of
         (dwts/set-selected-token-set-name token-set-name)
         (dch/commit-changes changes))))))

(defn delete-token
  [set-name token-name]
  (dm/assert! (string? set-name))
  (dm/assert! (string? token-name))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token set-name token-name))]
        (rx/of (dch/commit-changes changes))))))

(defn duplicate-token
  [token-name]
  (dm/assert! (string? token-name))
  (ptk/reify ::duplicate-token
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [token (some-> (dwts/get-selected-token-set-token state token-name)
                               (update :name #(str/concat % "-copy")))]
        (rx/of
         (update-create-token {:token token}))))))

(defn set-token-type-section-open
  [token-type open?]
  (ptk/reify ::set-token-type-section-open
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-tokens :open-status token-type] open?))))

;; === Token Context Menu

(defn show-token-context-menu
  [{:keys [position _token-name] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-token-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-context-menu] params))))

(def hide-token-context-menu
  (ptk/reify ::hide-token-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-context-menu] nil))))

;; === Token Set Context Menu

(defn show-token-set-context-menu
  [{:keys [position _token-set-name] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-token-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-set-context-menu] params))))

(def hide-token-set-context-menu
  (ptk/reify ::hide-token-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :token-set-context-menu] nil))))

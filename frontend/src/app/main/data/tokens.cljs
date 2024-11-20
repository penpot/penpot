;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.tokens
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.point :as gpt]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.token :as wtt]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [app.main.ui.workspace.tokens.update :as wtu]
   [beicon.v2.core :as rx]
   [clojure.data :as data]
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

(defn get-tokens-lib [state]
  (get-in state [:workspace-data :tokens-lib]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-or-apply-token
  "Remove any shape attributes from token if they exists.
  Othewise apply token attributes."
  [shape token]
  (let [[shape-leftover token-leftover _matching] (data/diff (:applied-tokens shape) token)]
    (merge {} shape-leftover token-leftover)))

(defn token-from-attributes [token attributes]
  (->> (map (fn [attr] [attr (wtt/token-identifier token)]) attributes)
       (into {})))

(defn unapply-token-id [shape attributes]
  (update shape :applied-tokens d/without-keys attributes))

(defn apply-token-to-attributes [{:keys [shape token attributes]}]
  (let [token (token-from-attributes token attributes)]
    (toggle-or-apply-token shape token)))

(defn apply-token-to-shape
  [{:keys [shape token attributes] :as _props}]
  (let [applied-tokens (apply-token-to-attributes {:shape shape
                                                   :token token
                                                   :attributes attributes})]
    (update shape :applied-tokens #(merge % applied-tokens))))

(defn maybe-apply-token-to-shape
  "When the passed `:token` is non-nil apply it to the `:applied-tokens` on a shape."
  [{:keys [shape token _attributes] :as props}]
  (if token
    (apply-token-to-shape props)
    shape))

(defn get-token-data-from-token-id
  [id]
  (let [workspace-data (deref refs/workspace-data)]
    (get (:tokens workspace-data) id)))

(defn set-selected-token-set-id
  [id]
  (ptk/reify ::set-selected-token-set-id
    ptk/UpdateEvent
    (update [_ state]
      (wtts/assoc-selected-token-set-id state id))))

(defn set-selected-token-set-id-from-name
  [token-set-name]
  (ptk/reify ::set-selected-token-set-id-from-name
    ptk/UpdateEvent
    (update [_ state]
      (->> (ctob/set-name->set-path-string token-set-name)
           (wtts/assoc-selected-token-set-id state)))))

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
      (let [data (get state :workspace-data)
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
           (set-selected-token-set-id-from-name (:name new-token-set))
           (dch/commit-changes changes)))))))

(defn update-token-set [set-name token-set]
  (ptk/reify ::update-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [prev-token-set (some-> (get-tokens-lib state)
                                   (ctob/get-set set-name))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-token-set token-set prev-token-set))]
        (rx/of
         (set-selected-token-set-id-from-name (:name token-set))
         (dch/commit-changes changes))))))

(defn toggle-token-set [{:keys [token-set-name]}]
  (ptk/reify ::toggle-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-theme (ctob/get-theme tokens-lib ctob/hidden-token-theme-group ctob/hidden-token-theme-name)
            active-token-set-names (ctob/get-active-themes-set-names tokens-lib)
            theme (-> (or (some-> prev-theme
                                  (ctob/set-sets active-token-set-names))
                          (ctob/make-hidden-token-theme :sets active-token-set-names))
                      (ctob/toggle-set token-set-name))
            prev-active-token-themes (ctob/get-active-theme-paths tokens-lib)
            changes (-> (pcb/empty-changes it)
                        (pcb/update-active-token-themes #{(ctob/token-theme-path ctob/hidden-token-theme-group ctob/hidden-token-theme-name)} prev-active-token-themes))
            changes' (if prev-theme
                       (pcb/update-token-theme changes theme prev-theme)
                       (pcb/add-token-theme changes theme))]
        (rx/of
         (dch/commit-changes changes')
         (wtu/update-workspace-tokens))))))

(defn import-tokens-lib [lib]
  (ptk/reify ::import-tokens-lib
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)
            update-token-set-change (some-> lib
                                            (ctob/get-sets)
                                            (first)
                                            (:name)
                                            (set-selected-token-set-id-from-name))
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-tokens-lib lib))]
        (rx/of
         (dch/commit-changes changes)
         update-token-set-change
         (wtu/update-workspace-tokens))))))

(defn delete-token-set-path [token-set-path]
  (ptk/reify ::delete-token-set-path
    ptk/WatchEvent
    (watch [it state _]
      (let [data (get state :workspace-data)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/delete-token-set-path token-set-path))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn move-token-set [source-set-name dest-set-name position]
  (ptk/reify ::move-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib (get-tokens-lib state)
            prev-before-set-name (ctob/get-neighbor-set-name tokens-lib source-set-name 1)
            [source-set-name' dest-set-name'] (if (= :top position)
                                                [source-set-name dest-set-name]
                                                [source-set-name (ctob/get-neighbor-set-name tokens-lib dest-set-name 1)])
            changes (-> (pcb/empty-changes it)
                        (pcb/move-token-set-before source-set-name' dest-set-name' prev-before-set-name))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn update-create-token
  [{:keys [token prev-token-name]}]
  (ptk/reify ::update-create-token
    ptk/WatchEvent
    (watch [_ state _]
      (let [token-set (wtts/get-selected-token-set state)
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
                        (pcb/add-token (pcb/empty-changes) (:name token-set) token)))]
        (rx/of
         (set-selected-token-set-id-from-name token-set-name)
         (dch/commit-changes changes))))))

(defn delete-token
  [set-name token-name]
  (dm/assert! (string? set-name))
  (dm/assert! (string? token-name))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (get state :workspace-data)
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
      (when-let [token (some-> (wtts/get-selected-token-set state)
                               (ctob/get-token token-name)
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

;; === Import Export Context Menu

(defn show-import-export-context-menu
  [{:keys [position] :as params}]
  (dm/assert! (gpt/point? position))
  (ptk/reify ::show-import-export-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :import-export-context-menu] params))))

(def hide-import-export-set-context-menu
  (ptk/reify ::hide-import-export-set-context-menu
    ptk/UpdateEvent
    (update [_ state]
      (assoc-in state [:workspace-local :import-export-set-context-menu] nil))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.tokens
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.logic.tokens :as clt]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.changes :as dch]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.ui.workspace.tokens.update :as wtu]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(declare set-selected-token-set-name)

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
   (when-let [selected (dm/get-in state [:workspace-tokens :selected-token-set-name])]
     (lookup-token-set state selected)))
  ([state name]
   (some-> (get-tokens-lib state)
           (ctob/get-set name))))

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
;; TOKENS Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-token-theme [token-theme]
  (let [new-token-theme token-theme]
    (ptk/reify ::create-token-theme
      ptk/WatchEvent
      (watch [it state _]
        (let [data    (dsh/lookup-file-data state)
              changes (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/set-token-theme (:group new-token-theme)
                                               (:name new-token-theme)
                                               new-token-theme))]
          (rx/of
           (dch/commit-changes changes)))))))

(defn update-token-theme [[group name] token-theme]
  (ptk/reify ::update-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [tokens-lib       (get-tokens-lib state)
            data             (dsh/lookup-file-data state)
            prev-token-theme (some-> tokens-lib (ctob/get-theme group name))
            changes          (-> (pcb/empty-changes it)
                                 (pcb/with-library-data data)
                                 (pcb/set-token-theme (:group prev-token-theme)
                                                      (:name prev-token-theme)
                                                      token-theme))]
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

(defn delete-token-theme [group theme-name]
  (ptk/reify ::delete-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-theme group theme-name nil))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn create-token-set
  [set-name token-set]
  (ptk/reify ::create-token-set
    ptk/UpdateEvent
    (update [_ state]
      ;; Clear possible local state
      (update state :workspace-tokens dissoc :token-set-new-path))

    ptk/WatchEvent
    (watch [it state _]
      (let [token-set' (-> token-set
                           (update :name #(if (empty? %)
                                            set-name
                                            (ctob/join-set-path [% set-name]))))
            data (dsh/lookup-file-data state)
            token-set-name (:name token-set')
            changes   (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/set-token-set token-set-name false token-set'))]
        (rx/of (set-selected-token-set-name token-set-name)
               (dch/commit-changes changes))))))

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
      (let [data (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-set set-name false token-set))]
        (rx/of
         (set-selected-token-set-name (:name token-set))
         (dch/commit-changes changes))))))

(defn toggle-token-set
  [name]
  (assert (string? name) "expected a string for `name`")
  (ptk/reify ::toggle-token-set
    ptk/WatchEvent
    (watch [_ state _]
      (let [data    (dsh/lookup-file-data state)
            tlib (get-tokens-lib state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-toggle-token-set tlib name))]

        (rx/of (dch/commit-changes changes)
               (wtu/update-workspace-tokens))))))

(defn toggle-token-set-group [group-path]
  (ptk/reify ::toggle-token-set-group
    ptk/WatchEvent
    (watch [_ state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes)
                        (pcb/with-library-data data)
                        (clt/generate-toggle-token-set-group (get-tokens-lib state) group-path))]
        (rx/of
         (dch/commit-changes changes)
         (wtu/update-workspace-tokens))))))

(defn import-tokens-lib [lib]
  (ptk/reify ::import-tokens-lib
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-tokens-lib lib))]
        (rx/of (dch/commit-changes changes)
               (wtu/update-workspace-tokens))))))

(defn delete-token-set-path
  [group? path]
  (ptk/reify ::delete-token-set-path
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token-set (ctob/join-set-path path) group? nil))]
        (rx/of (dch/commit-changes changes)
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
          (rx/of (dch/commit-changes changes)
                 (wtu/update-workspace-tokens)))
        (catch js/Error e
          (rx/of
           (drop-error (ex-data e))))))))

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
            (-> (ctob/make-token-set :name set-name)
                (ctob/add-token token))

            hidden-theme
            (ctob/make-hidden-token-theme)

            hidden-theme-with-set
            (ctob/enable-set hidden-theme set-name)

            changes
            (-> (pcb/empty-changes)
                (pcb/with-library-data data)
                (pcb/set-token-set set-name false token-set)
                (pcb/set-token-theme (:group hidden-theme)
                                     (:name hidden-theme)
                                     hidden-theme-with-set)
                (pcb/update-active-token-themes #{ctob/hidden-token-theme-path} #{}))]
        (rx/of (dch/commit-changes changes)
               (set-selected-token-set-name set-name))))))

(defn create-token
  [params]
  (let [token (ctob/make-token params)]
    (ptk/reify ::create-token
      ptk/WatchEvent
      (watch [it state _]
        (if-let [token-set (lookup-token-set state)]
          (let [data    (dsh/lookup-file-data state)
                changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token (:name token-set)
                                           (:name token)
                                           token))]

            (rx/of (dch/commit-changes changes)
                   (ptk/data-event ::ev/event {::ev/name "create-token"})))

          (rx/of (create-token-with-set token)))))))

(defn update-token
  [name params]
  (assert (string? name) "expected string for `name`")

  (ptk/reify ::update-token
    ptk/WatchEvent
    (watch [it state _]
      (let [token-set (lookup-token-set state)
            data      (dsh/lookup-file-data state)
            token     (ctob/get-token token-set name)
            token'    (->> (merge token params)
                           (into {})
                           (ctob/make-token))

            changes   (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/set-token (:name token-set)
                                         (:name token)
                                         token'))]

        (rx/of (dch/commit-changes changes))))))

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
                        (pcb/set-token set-name token-name nil))]
        (rx/of (dch/commit-changes changes))))))

(defn duplicate-token
  [token-name]
  (dm/assert! (string? token-name))
  (ptk/reify ::duplicate-token
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [token-set (lookup-token-set state)]
        (when-let [token (ctob/get-token token-set token-name)]
          (let [tokens (ctob/get-tokens token-set)
                unames (map :name tokens)

                suffix-fn
                (fn [copy-count]
                  (let [suffix (tr "workspace.token.duplicate-suffix")]
                    (str/concat "-"
                                suffix
                                (when (> copy-count 1)
                                  (str "-" copy-count)))))

                copy-name
                (cfh/generate-unique-name token-name unames :suffix-fn suffix-fn)]

            (rx/of (create-token (assoc token :name copy-name)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TOKEN UI OPS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn set-token-type-section-open
  [token-type open?]
  (ptk/reify ::set-token-type-section-open
    ptk/UpdateEvent
    (update [_ state]
      (update-in state [:workspace-tokens :open-status-by-type] assoc token-type open?))))

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

(defn set-selected-token-set-name
  [name]
  (ptk/reify ::set-selected-token-set-name
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens assoc :selected-token-set-name name))))

(defn start-token-set-edition
  [edition-id]
  (assert (string? edition-id) "expected a string for `edition-id`")

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

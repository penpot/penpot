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
   [beicon.v2.core :as rx]
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
  (assert (uuid? id) "expected valid uuid for `id`")

  (let [attrs (cts/check-shape-attrs attrs)]
    (ptk/reify ::update-shape
      ptk/WatchEvent
      (watch [_ _ _]
        (rx/of (dwsh/update-shapes [id] #(merge % attrs)))))))

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

          (if (and tokens-lib (ctob/get-theme tokens-lib (:group token-theme) (:name token-theme)))
            (rx/of (ntf/show {:content (tr "errors.token-theme-already-exists")
                              :type :toast
                              :level :error
                              :timeout 9000}))
            (let [changes (-> (pcb/empty-changes it)
                              (pcb/with-library-data data)
                              (pcb/set-token-theme (:group new-token-theme)
                                                   (:name new-token-theme)
                                                   new-token-theme))]
              (rx/of (dch/commit-changes changes)))))))))

(defn update-token-theme [[group name] token-theme]
  (ptk/reify ::update-token-theme
    ptk/WatchEvent
    (watch [it state _]
      (let [data             (dsh/lookup-file-data state)
            tokens-lib       (get data :tokens-lib)]
        (if (and (or (not= group (:group token-theme))
                     (not= name (:name token-theme)))
                 (ctob/get-theme tokens-lib
                                 (:group token-theme)
                                 (:name token-theme)))
          (rx/of (ntf/show {:content (tr "errors.token-theme-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token-theme group name token-theme))]
            (rx/of (dch/commit-changes changes))))))))

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
            active-token-themes' (if (= active-token-themes #{ctob/hidden-theme-path})
                                   active-token-themes
                                   (disj active-token-themes ctob/hidden-theme-path))
            changes (-> (pcb/empty-changes it)
                        (pcb/update-active-token-themes active-token-themes' prev-active-token-themes))]
        (rx/of
         (dch/commit-changes changes)
         (dwtp/propagate-workspace-tokens))))))

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
         (dwtp/propagate-workspace-tokens))))))

(defn create-token-set
  [set-name]
  (ptk/reify ::create-token-set
    ptk/UpdateEvent
    (update [_ state]
      ;; Clear possible local state
      (update state :workspace-tokens dissoc :token-set-new-path))

    ptk/WatchEvent
    (watch [it state _]
      (let [data       (dsh/lookup-file-data state)
            tokens-lib (get data :tokens-lib)
            set-name   (ctob/normalize-set-name set-name)]
        (if (and tokens-lib (ctob/get-set tokens-lib set-name))
          (rx/of (ntf/show {:content (tr "errors.token-set-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [token-set (ctob/make-token-set :name set-name)
                changes   (-> (pcb/empty-changes it)
                              (pcb/with-library-data data)
                              (pcb/set-token-set set-name false token-set))]
            (rx/of (set-selected-token-set-name set-name)
                   (dch/commit-changes changes))))))))

(defn rename-token-set-group [set-group-path set-group-fname]
  (ptk/reify ::rename-token-set-group
    ptk/WatchEvent
    (watch [it _state _]
      (let [changes (-> (pcb/empty-changes it)
                        (pcb/rename-token-set-group set-group-path set-group-fname))]
        (rx/of
         (dch/commit-changes changes))))))

(defn update-token-set
  [token-set name]
  (ptk/reify ::update-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data       (dsh/lookup-file-data state)
            name       (ctob/normalize-set-name name (ctob/get-name token-set))
            tokens-lib (get data :tokens-lib)]

        (if (ctob/get-set tokens-lib name)
          (rx/of (ntf/show {:content (tr "errors.token-set-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/rename-token-set (ctob/get-name token-set) name))]
            (rx/of (set-selected-token-set-name name)
                   (dch/commit-changes changes))))))))

(defn duplicate-token-set
  [id is-group]
  (ptk/reify ::duplicate-token-set
    ptk/WatchEvent
    (watch [it state _]
      (let [data       (dsh/lookup-file-data state)
            name       (ctob/normalize-set-name id)
            tokens-lib (get data :tokens-lib)
            suffix     (tr "workspace.tokens.duplicate-suffix")]

        (when-let [set (ctob/duplicate-set name tokens-lib {:suffix suffix})]
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token-set (ctob/get-name set) is-group set))]
            (rx/of (set-selected-token-set-name name)
                   (dch/commit-changes changes))))))))

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

        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

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
         (dwtp/propagate-workspace-tokens))))))

(defn import-tokens-lib [lib]
  (ptk/reify ::import-tokens-lib
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-tokens-lib lib))]
        (rx/of (dch/commit-changes changes)
               (dwtp/propagate-workspace-tokens))))))

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
               (dwtp/propagate-workspace-tokens))))))

(defn drop-error [{:keys [error to-path]}]
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

(defn drop-token-set-group [drop-opts]
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
            (-> (ctob/make-token-set :name set-name)
                (ctob/add-token token))

            hidden-theme
            (ctob/make-hidden-theme)

            hidden-theme-with-set
            (ctob/enable-set hidden-theme set-name)

            changes
            (-> (pcb/empty-changes)
                (pcb/with-library-data data)
                (pcb/set-token-set set-name false token-set)
                (pcb/set-token-theme (:group hidden-theme)
                                     (:name hidden-theme)
                                     hidden-theme-with-set)
                (pcb/update-active-token-themes #{ctob/hidden-theme-path} #{}))]
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
                token-type (:type token)
                changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token (ctob/get-name token-set)
                                           (:id token)
                                           token))]

            (rx/of (dch/commit-changes changes)
                   (ptk/data-event ::ev/event {::ev/name "create-token" :type token-type})))

          (rx/of (create-token-with-set token)))))))

(defn update-token
  [id params]
  (assert (uuid? id) "expected uuid for `id`")

  (ptk/reify ::update-token
    ptk/WatchEvent
    (watch [it state _]
      (let [token-set (lookup-token-set state)
            data      (dsh/lookup-file-data state)
            token     (ctob/get-token token-set id)
            token'    (->> (merge token params)
                           (into {})
                           (ctob/make-token))
            token-type (:type token)
            changes   (-> (pcb/empty-changes it)
                          (pcb/with-library-data data)
                          (pcb/set-token (ctob/get-name token-set)
                                         id
                                         token'))]

        (rx/of (dch/commit-changes changes)
               (ptk/data-event ::ev/event {::ev/name "edit-token" :type token-type}))))))

(defn delete-token
  [set-name token-id]
  (dm/assert! (string? set-name))
  (dm/assert! (uuid? token-id))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token set-name token-id nil))]
        (rx/of (dch/commit-changes changes))))))

(defn duplicate-token
  [token-id]
  (dm/assert! (uuid? token-id))
  (ptk/reify ::duplicate-token
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [token-set (lookup-token-set state)]
        (when-let [token (ctob/get-token token-set token-id)]
          (let [tokens (ctob/get-tokens token-set)
                unames (map :name tokens)
                suffix (tr "workspace.tokens.duplicate-suffix")
                copy-name (cfh/generate-unique-name (:name token) unames :suffix suffix)]

            (rx/of (create-token (assoc token
                                        :id (uuid/next)
                                        :name copy-name)))))))))

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

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
  (ptk/reify ::create-token-set
    ptk/UpdateEvent
    (update [_ state]
      ;; Clear possible local state
      (update state :workspace-tokens dissoc :token-set-new-path))

    ptk/WatchEvent
    (watch [it state _]
      (let [data       (dsh/lookup-file-data state)
            tokens-lib (get data :tokens-lib)
            token-set  (ctob/rename token-set (ctob/normalize-set-name (ctob/get-name token-set)))]
        (if (and tokens-lib (ctob/get-set-by-name tokens-lib (ctob/get-name token-set)))
          (rx/of (ntf/show {:content (tr "errors.token-set-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/set-token-set (ctob/get-id token-set) token-set))]
            (rx/of (set-selected-token-set-id (ctob/get-id token-set))
                   (dch/commit-changes changes))))))))

(defn rename-token-set-group
  [set-group-path set-group-fname]
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

        (if (ctob/get-set-by-name tokens-lib name)
          (rx/of (ntf/show {:content (tr "errors.token-set-already-exists")
                            :type :toast
                            :level :error
                            :timeout 9000}))
          (let [changes (-> (pcb/empty-changes it)
                            (pcb/with-library-data data)
                            (pcb/rename-token-set (ctob/get-id token-set) name))]
            (rx/of (set-selected-token-set-id (ctob/get-id token-set))
                   (dch/commit-changes changes))))))))

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

        (rx/of (dch/commit-changes changes)
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
                  (ptk/data-event ::ev/event {::ev/name "create-token" :type token-type})))

         (rx/of (create-token-with-set token)))))))

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
         (rx/of (dch/commit-changes changes)
                (ptk/data-event ::ev/event {::ev/name "edit-token" :type token-type})))))))

(defn delete-token
  [set-id token-id]
  (dm/assert! (uuid? set-id))
  (dm/assert! (uuid? token-id))
  (ptk/reify ::delete-token
    ptk/WatchEvent
    (watch [it state _]
      (let [data    (dsh/lookup-file-data state)
            changes (-> (pcb/empty-changes it)
                        (pcb/with-library-data data)
                        (pcb/set-token set-id token-id nil))]
        (rx/of (dch/commit-changes changes))))))

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
                  unames (map :name tokens)
                  suffix (tr "workspace.tokens.duplicate-suffix")
                  copy-name (cfh/generate-unique-name (:name token) unames :suffix suffix)
                  new-token (-> token
                                (ctob/reid (uuid/next))
                                (ctob/rename copy-name))]
              (rx/of (create-token new-token)))))))))

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

(defn set-selected-token-set-id
  [id]
  (ptk/reify ::set-selected-token-set-id
    ptk/UpdateEvent
    (update [_ state]
      (update state :workspace-tokens assoc :selected-token-set-id id))))

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

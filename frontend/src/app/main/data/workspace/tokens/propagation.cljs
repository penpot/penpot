;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.propagation
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cfo]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.types.token :as ctt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace :as-alias dw]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [clojure.data :as data]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; Change this to :info :debug or :trace to debug this module, or :warn to reset to default
(l/set-level! :warn)

;; Helpers ---------------------------------------------------------------------

;; TODO: see if this can be replaced by more standard functions
(defn deep-merge
  "Like d/deep-merge but unions set values."
  ([a b]
   (cond
     (map? a) (merge-with deep-merge a b)
     (set? a) (set/union a b)
     :else b))
  ([a b & rest]
   (reduce deep-merge a (cons b rest))))

(defn- flatten-set-keyed-map
  "Flattens a map where the keys are sets of keywords."
  [m into-m]
  (reduce
   (fn [acc [ks action]]
     (into acc (map (fn [k] [k action]) ks)))
   into-m m))

;; Constants -------------------------------------------------------------------

(def ^:private filter-existing-values? false)

(def ^:private attributes->shape-update dwta/attributes->shape-update)

(def ^:private attribute-actions-map
  (flatten-set-keyed-map attributes->shape-update {}))

;; Data flows ------------------------------------------------------------------

(defn- invert-collect-key-vals
  [xs resolved-tokens shape]
  (-> (reduce
       (fn [acc [k v]]
         (let [resolved-token (get resolved-tokens v)
               resolved-value (get resolved-token :resolved-value)
               skip? (or
                      (not (get resolved-tokens v))
                      (and filter-existing-values? (= (get shape k) resolved-value)))]
           (if skip?
             acc
             (update acc resolved-value (fnil conj #{}) k))))
       {} xs)))

(defn- split-by-action
  "Attributes applied by different actions can share a value (a radius
  and a stroke width of 4): one group per action, or only one of them
  would be applied (see `actionize-shapes-update-info`)."
  [attrs-values-map]
  (reduce
   (fn [acc [attrs v]]
     (reduce (fn [acc group] (assoc acc (set group) v))
             acc
             (vals (group-by attribute-actions-map attrs))))
   {} attrs-values-map))

(defn- split-attribute-groups [attrs-values-map]
  (-> (reduce
       (fn [acc [attrs v]]
         (cond
           (some attrs #{:width :height}) (let [[_ a b] (data/diff #{:width :height} attrs)]
                                            (cond-> (assoc acc b v)
                                              ;; Exact match in attrs
                                              a (assoc a v)))
           (some attrs ctt/spacing-keys) (let [[_ rst gap] (data/diff #{:row-gap :column-gap} attrs)
                                               [_ position padding] (data/diff #{:p1 :p2 :p3 :p4} rst)]
                                           (cond-> acc
                                             (seq gap) (assoc gap v)
                                             (seq position) (assoc position v)
                                             (seq padding) (assoc padding v)))
           attrs (assoc acc attrs v)))
       {} attrs-values-map)
      (split-by-action)))

(defn- shape-ids-by-values
  [attrs-values-map object-id]
  (->> (map (fn [[value attrs]] [attrs {value #{object-id}}]) attrs-values-map)
       (into {})))

(defn- collect-shapes-update-info
  "Collect the token updates of `shapes`, a seq of [id shape] entries of
  `objects`."
  [resolved-tokens objects shapes]
  (loop [items (seq shapes)
         frame-ids #{}
         text-ids []
         tokens {}]
    (if-let [[shape-id {:keys [applied-tokens] :as shape}] (first items)]
      (let [applied-tokens
            (-> (invert-collect-key-vals applied-tokens resolved-tokens shape)
                (shape-ids-by-values shape-id)
                (split-attribute-groups))

            parent-frame-id
            (cfh/get-shape-id-root-frame objects shape-id)]

        (recur (rest items)
               (if parent-frame-id
                 (conj frame-ids parent-frame-id)
                 frame-ids)
               (if (cfh/text-shape? shape)
                 (conj text-ids shape-id)
                 text-ids)
               (deep-merge tokens applied-tokens)))

      [tokens frame-ids text-ids])))

(defn- actionize-shapes-update-info [page-id shapes-update-info]
  (mapcat (fn [[attrs update-infos]]
            (let [action (some attribute-actions-map attrs)]
              (assert (fn? action) "missing action function on attributes->shape-update")
              (map
               (fn [[v shape-ids]]
                 (action v shape-ids attrs page-id))
               update-infos)))
          shapes-update-info))

(defn- update-canvas-background
  "Set the background of `page-id` to the resolved value of its linked
  color token, when that value differs from the current background. The
  commit has `undo-group` when not nil."
  [page-id resolved-tokens undo-group]
  (ptk/reify ::update-canvas-background
    ptk/WatchEvent
    (watch [it state _]
      (let [page       (dsh/lookup-page state page-id)
            token-name (:background-token page)
            color      (some-> (get-in resolved-tokens [token-name :resolved-value])
                               (dwta/value->color)
                               (:color))]
        (when (and (some? color) (not= color (:background page)))
          (rx/of (dch/commit-changes
                  (-> (pcb/empty-changes it)
                      (pcb/with-page page)
                      (pcb/mod-page {:background color})
                      (cond-> (some? undo-group)
                        (pcb/set-undo-group undo-group))))))))))

(defn- scoped-shapes
  "Entries of `objects` for the shapes in `root-ids` and all their descendants."
  [objects root-ids]
  (into []
        (comp (mapcat #(cfh/get-children-ids-with-self objects %))
              (distinct)
              (keep (fn [id]
                      (when-let [shape (get objects id)]
                        [id shape]))))
        root-ids))

(defn propagate-tokens
  "Propagate tokens values to all shapes and page backgrounds where they
  are applied. A non-nil `scope` of `:page-id` and `:root-ids` limits it
  to those shapes and their descendants in that page. The commits it
  makes outside the update-shapes buffer have `undo-group` when given."
  [state resolved-tokens {scope-page-id :page-id root-ids :root-ids :as scope}
   & {:keys [undo-group]}]
  (let [file-id         (get state :current-file-id)
        current-page-id (get state :current-page-id)
        fdata           (dsh/lookup-file-data state file-id)
        scoped?         (some? scope)
        tpoint          (ct/tpoint-ms)]

    (l/inf :status "START" :hint "propagate-tokens" :scoped scoped?)
    (->> (if scoped?
           (rx/of scope-page-id)
           (rx/concat
            (rx/of current-page-id)
            (->> (rx/from (:pages fdata))
                 (rx/filter (fn [id] (not= id current-page-id))))))
         (rx/mapcat
          (fn [page-id]
            (let [page
                  (dsh/get-page fdata page-id)

                  objects
                  (:objects page)

                  [attrs frame-ids text-ids]
                  (collect-shapes-update-info resolved-tokens
                                              objects
                                              (if scoped?
                                                (scoped-shapes objects root-ids)
                                                objects))

                  actions
                  (actionize-shapes-update-info page-id attrs)

                  ;; Composed updates return observables and need to be executed differently
                  {:keys [observable normal]} (group-by #(if (rx/observable? %) :observable :normal) actions)]

              (l/inf :status "PROGRESS"
                     :hint "propagate-tokens"
                     :page-id (str page-id)
                     :elapsed (tpoint)
                     ::l/sync? true)

              (rx/merge
               (when (seq observable) (apply rx/merge observable))
               (when (seq normal) (rx/concat-all (rx/of normal)))

               (when (and (not scoped?) (:background-token page))
                 (rx/of (update-canvas-background page-id resolved-tokens undo-group)))

               (->> (rx/from frame-ids)
                    (rx/mapcat (fn [frame-id]
                                 (rx/of (dwt/clear-thumbnail file-id page-id frame-id "frame")
                                        (dwt/clear-thumbnail file-id page-id frame-id "component")))))
               (when (not= page-id current-page-id)   ;; Texts in the current page have already their position-data regenerated
                 (rx/of (dwsh/update-shapes text-ids  ;; after change. But those on other pages need to be specifically reset.
                                            (fn [shape]
                                              (dissoc shape :position-data))
                                            {:page-id page-id
                                             :ignore-touched true})))))))
         (rx/finalize
          (fn [_]
            (let [elapsed (tpoint)]
              (l/inf :status "END" :hint "propagate-tokens" :elapsed elapsed)))))))

(defn- resolve-active-tokens
  "Observable of the resolved values of the tokens in the active sets, or
  nil when the file has no tokens."
  [state]
  (let [tokens-lib    (dsh/lookup-tokens-lib state)
        tokens-status (dsh/lookup-tokens-status state)]
    (when (and tokens-lib tokens-status)
      (if (contains? cf/flags :tokenscript)
        (rx/of (ts/resolve-sets (cfo/get-active-token-sets tokens-status tokens-lib)))
        (sd/resolve-tokens (cfo/get-tokens-in-active-sets tokens-status tokens-lib))))))

(defn- buffered
  "`ob` inside the update-shapes buffer, so its shape updates are
  committed together, started with `buffer-opts`. The buffer is stopped
  even when `ob` fails; the error goes on."
  [buffer-opts ob]
  ;; FIXME: now the tokens propagations is done by accumulating the update-shapes
  ;; into a single commit-changes. This is not really the best way, the token application
  ;; should be done with a changes_builder and sending only one `commit-changes` instead
  ;; of creating lots of `update-shapes`.
  (rx/concat
   (rx/of (dwsh/update-shapes-buffer-start buffer-opts))
   (rx/catch #(rx/concat (rx/of (dwsh/update-shapes-buffer-stop))
                         (rx/throw %))
             ob)
   (rx/of (dwsh/update-shapes-buffer-stop))))

(defn propagate-workspace-tokens
  "Apply the resolved values of the active tokens to the shapes that use
  them. Without `scope` it covers every page of the file. A `scope` of
  `:page-id` and `:root-ids` limits it to those shapes and their
  descendants."
  ([]
   (propagate-workspace-tokens nil))
  ([scope]
   (ptk/reify ::propagate-workspace-tokens
     ptk/WatchEvent
     (watch [_ state _]
       (some->> (resolve-active-tokens state)
                (rx/mapcat
                 (fn [sd-tokens]
                   (let [undo-id (js/Symbol)
                         commit  (rx/of (dwu/commit-undo-transaction undo-id))]
                     (rx/concat
                      (rx/of (dwu/start-undo-transaction undo-id :timeout false))
                      ;; The transaction is closed even when the propagation fails
                      (rx/catch #(rx/concat commit (rx/throw %))
                                (buffered nil (propagate-tokens state sd-tokens scope)))
                      commit)))))))))

(defn- action-shape-ids
  "Ids of the shapes of the current page changed by the history entry of
  the undo action `undo-group`, and of their ancestors: the pipelines that
  resize texts and lay out boards mark their work on them."
  [state undo-group]
  (let [objects (dsh/lookup-page-objects state)
        item    (->> (get-in state [:workspace-undo :items])
                     (filter #(contains? (:undo-groups %) undo-group))
                     (last))]
    (into #{}
          (comp (keep #(when (= :mod-obj (:type %)) (:id %)))
                (filter #(contains? objects %))
                (mapcat #(cons % (cfh/get-parent-ids objects %))))
          (:redo-changes item))))

(defn- finish-action-when-settled
  "Ends the undo action `undo-group` once the shapes it changed have no
  layout or text work left (see `app.main.data.workspace.reflow`), so the
  texts it resized are in its history entry."
  [undo-group]
  (ptk/reify ::finish-action-when-settled
    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/merge (wrf/settled (action-shape-ids state undo-group))
                     (rx/filter (ptk/type? ::dw/finalize-workspace) stream))
           (rx/take 1)
           ;; Work is often marked done from inside a state update, which
           ;; would overwrite an update made meanwhile
           (rx/observe-on :async)
           (rx/map (fn [_] (dwu/finish-action undo-group)))))))

(defn- propagate-action
  "Propagates the active tokens as part of the undo action `undo-group`,
  and ends it once done, failures included."
  [undo-group scope]
  (ptk/reify ::propagate-action
    ptk/WatchEvent
    (watch [_ state _]
      (let [finish (rx/of (dwu/finish-action undo-group))]
        (if-let [resolved (resolve-active-tokens state)]
          (->> resolved
               (rx/mapcat
                (fn [sd-tokens]
                  (rx/concat
                   (buffered {:undo-group undo-group}
                             (propagate-tokens state sd-tokens scope :undo-group undo-group))
                   (rx/of (finish-action-when-settled undo-group)))))
               (rx/catch #(rx/concat finish (rx/throw %))))
          finish)))))

(defn with-propagation
  "Commits `changes`, which change what tokens resolve to (the active
  themes or sets, the tokens source...), and propagates the tokens that
  resolve differently because of it, as one undo action (see
  `app.main.data.workspace.undo`): one undo brings back the change, the
  propagated values and the sizes of the texts they resize, and nothing
  else, even when other edits are made meanwhile. Only the shapes of
  `scope` (see `propagate-workspace-tokens`) and their children can
  resolve differently."
  [changes scope]
  (ptk/reify ::with-propagation
    ptk/WatchEvent
    (watch [_ _ _]
      (let [undo-group (uuid/next)]
        (rx/of (dwu/start-action undo-group)
               (dch/commit-changes (assoc changes :undo-group undo-group))
               ;; After the commit, to resolve what it changed
               (propagate-action undo-group scope))))))

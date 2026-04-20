;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.propagation
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.thumbnails-wasm :as dwt.wasm]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.undo :as dwu]
   [app.main.features :as features]
   [app.render-wasm.api :as wasm.api]
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

(defn- split-attribute-groups [attrs-values-map]
  (reduce
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
   {} attrs-values-map))

(defn- shape-ids-by-values
  [attrs-values-map object-id]
  (->> (map (fn [[value attrs]] [attrs {value #{object-id}}]) attrs-values-map)
       (into {})))

(defn- collect-shapes-update-info [resolved-tokens objects]
  (loop [items (seq objects)
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

(defn propagate-tokens
  "Propagate tokens values to all shapes where they are applied"
  [state resolved-tokens]
  (let [file-id         (get state :current-file-id)
        current-page-id (get state :current-page-id)
        fdata           (dsh/lookup-file-data state file-id)
        tpoint          (ct/tpoint-ms)]

    (l/inf :status "START" :hint "propagate-tokens")
    (->> (rx/concat
          (rx/of current-page-id)
          (->> (rx/from (:pages fdata))
               (rx/filter (fn [id] (not= id current-page-id)))))
         (rx/observe-on :async) ;; Do not block UI on token propagation
         (rx/mapcat
          (fn [page-id]
            (let [page
                  (dsh/get-page fdata page-id)

                  [attrs frame-ids text-ids]
                  (collect-shapes-update-info resolved-tokens (:objects page))

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

               (->> (rx/from frame-ids)
                    (rx/mapcat (fn [frame-id]
                                 (rx/concat
                                  (rx/of (dwt/clear-thumbnail file-id page-id frame-id "frame"))
                                  ;; WASM: skip component thumbnails — they are rendered
                                  ;; lazily when the assets panel is opened, so clearing
                                  ;; them here would trigger a flood of render-shape-pixels calls.
                                  (if (features/active-feature? state "render-wasm/v1")
                                    (rx/empty)
                                    (rx/of (dwt/clear-thumbnail file-id page-id frame-id "component")))))))
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

(defn- reload-wasm-current-page
  "Bulk-reload all current-page shapes into WASM after token
   propagation has updated the data model without per-shape sync.
   After reload, refreshes all component thumbnails progressively,
   current-page components first, then components on other pages."
  []
  (ptk/reify ::reload-wasm-current-page
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects    (dsh/lookup-page-objects state)
            file-id    (get state :current-file-id)
            page-id    (get state :current-page-id)
            fdata      (dsh/lookup-file-data state file-id)
            components (ctkl/components-seq fdata)
            ;; Current-page components first (already loaded in WASM),
            ;; then other pages (render-thumbnail loads their subtree)
            sorted     (sort-by #(if (= (:main-instance-page %) page-id) 0 1) components)]

        (->> (rx/create
              (fn [subs]
                (wasm.api/set-objects objects
                                      (fn []
                                        (wasm.api/request-render "token-propagation")
                                        (rx/push! subs true)
                                        (rx/end! subs)))))
             (rx/mapcat
              (fn [_]
                (->> (rx/from sorted)
                     (rx/map (fn [{:keys [main-instance-page main-instance-id]}]
                               (dwt.wasm/render-thumbnail file-id main-instance-page main-instance-id :persist? true)))))))))))

(defn propagate-workspace-tokens
  []
  (ptk/reify ::propagate-workspace-tokens
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [tokens-tree (-> (dsh/lookup-file-data state)
                                 (get :tokens-lib)
                                 (ctob/get-tokens-in-active-sets))]
        (let [wasm? (features/active-feature? state "render-wasm/v1")]
          ;; Pause per-shape WASM sync so token propagation commits
          ;; only update the data model, bulk-reload WASM at the end.
          (when wasm?
            (dch/pause-wasm-sync!))
          (->> ;; Yield one frame so the UI can repaint (close dropdown,
           ;; show loading state, etc.) before the heavy work starts.
           (rx/of nil)
           (rx/observe-on :async)
           (rx/mapcat
            (fn [_]
              (if (contains? cf/flags :tokenscript)
                (rx/of (-> (ts/resolve-tokens tokens-tree)
                           (d/update-vals #(update % :resolved-value ts/tokenscript-symbols->penpot-unit))))
                (sd/resolve-tokens tokens-tree))))
           (rx/mapcat (fn [sd-tokens]
                        (let [undo-id (js/Symbol)]
                          (rx/concat
                           (rx/of (dwu/start-undo-transaction undo-id :timeout false))
                           (propagate-tokens state sd-tokens)
                           (rx/of (dwu/commit-undo-transaction undo-id))
                           (when wasm?
                             (rx/of (reload-wasm-current-page)))))))
           (rx/finalize (fn []
                          (when wasm?
                            (dch/resume-wasm-sync!))))))))))

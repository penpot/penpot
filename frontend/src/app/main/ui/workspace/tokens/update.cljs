;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.update
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.logging :as l]
   [app.common.types.token :as ctt]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.ui.workspace.tokens.changes :as wtch]
   [app.main.ui.workspace.tokens.style-dictionary :as wtsd]
   [app.main.ui.workspace.tokens.token-set :as wtts]
   [app.util.time :as dt]
   [beicon.v2.core :as rx]
   [clojure.data :as data]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; Constants -------------------------------------------------------------------

(def filter-existing-values? false)

(def attributes->shape-update
  {ctt/border-radius-keys wtch/update-shape-radius-for-corners
   ctt/color-keys wtch/update-fill-stroke
   ctt/stroke-width-keys wtch/update-stroke-width
   ctt/sizing-keys wtch/update-shape-dimensions
   ctt/opacity-keys wtch/update-opacity
   #{:x :y} wtch/update-shape-position
   #{:p1 :p2 :p3 :p4} wtch/update-layout-padding
   #{:m1 :m2 :m3 :m4} wtch/update-layout-item-margin
   #{:column-gap :row-gap} wtch/update-layout-spacing
   #{:width :height} wtch/update-shape-dimensions
   #{:layout-item-min-w :layout-item-min-h :layout-item-max-w :layout-item-max-h} wtch/update-layout-sizing-limits
   ctt/rotation-keys wtch/update-rotation})

(def attribute-actions-map
  (reduce
   (fn [acc [ks action]]
     (into acc (map (fn [k] [k action]) ks)))
   {} attributes->shape-update))

;; Helpers ---------------------------------------------------------------------

(defn deep-merge
  "Like d/deep-merge but unions set values."
  ([a b]
   (cond
     (map? a) (merge-with deep-merge a b)
     (set? a) (set/union a b)
     :else b))
  ([a b & rest]
   (reduce deep-merge a (cons b rest))))

;; Data flows ------------------------------------------------------------------

(defn invert-collect-key-vals
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

(defn split-attribute-groups [attrs-values-map]
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

(defn shape-ids-by-values
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

;; FIXME: revisit this
(defn- actionize-shapes-update-info [page-id shapes-update-info]
  (mapcat (fn [[attrs update-infos]]
            (let [action (some attribute-actions-map attrs)]
              (map
               (fn [[v shape-ids]]
                 (action v shape-ids attrs page-id))
               update-infos)))
          shapes-update-info))

(defn update-tokens
  [state resolved-tokens]
  (let [file-id         (get state :current-file-id)
        current-page-id (get state :current-page-id)
        fdata           (dsh/lookup-file-data state file-id)
        tpoint          (dt/tpoint-ms)]

    (l/inf :status "START" :hint "update-tokens")
    (->> (rx/concat
          (rx/of current-page-id)
          (->> (rx/from (:pages fdata))
               (rx/filter (fn [id] (not= id current-page-id)))))
         (rx/mapcat
          (fn [page-id]
            (let [page
                  (dsh/get-page fdata page-id)

                  [attrs frame-ids text-ids]
                  (collect-shapes-update-info resolved-tokens (:objects page))

                  actions
                  (actionize-shapes-update-info page-id attrs)]

              (l/inf :status "PROGRESS"
                     :hint "update-tokens"
                     :page-id (str page-id)
                     :elapsed (tpoint)
                     ::l/sync? true)

              (rx/merge
               (rx/from actions)
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
              (l/inf :status "END" :hint "update-tokens" :elapsed elapsed)))))))

(defn update-workspace-tokens
  []
  (ptk/reify ::update-workspace-tokens
    ptk/WatchEvent
    (watch [_ state _]
      (let [tokens (-> (wtts/get-active-theme-sets-tokens-names-map state)
                       (wtsd/resolve-tokens+))]
        (->> (rx/from tokens)
             (rx/mapcat (fn [sd-tokens]
                          (let [undo-id (js/Symbol)]
                            (rx/concat
                             (rx/of (dwu/start-undo-transaction undo-id :timeout false))
                             (update-tokens state sd-tokens)
                             (rx/of (dwu/commit-undo-transaction undo-id)))))))))))

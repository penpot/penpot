;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.common.logic.tokens
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [clojure.set :as set]))

(defn- generate-update-active-sets
  "Update active set ids in tokens-status by applying update-fn to the
   current active set ids. Returns updated changes."
  [changes tokens-status _tokens-lib update-fn]
  (let [current-set-ids  (ctos/get-active-set-ids tokens-status)
        new-set-ids      (update-fn current-set-ids)
        active-theme-ids (ctos/get-active-theme-ids tokens-status)
        tokens-status'   (ctos/set-tokens-status tokens-status active-theme-ids new-set-ids)]
    (pcb/set-tokens-status changes tokens-status')))

(defn generate-set-enabled-token-set
  "Enable or disable a token set at `set-name` in `tokens-lib` without modifying a user theme."
  [changes tokens-status tokens-lib set-name enabled?]
  (let [set-id (ctob/get-id (ctob/get-set-by-name tokens-lib set-name))]
    (if set-id
      (if enabled?
        (generate-update-active-sets changes tokens-status tokens-lib #(conj % set-id))
        (generate-update-active-sets changes tokens-status tokens-lib #(disj % set-id)))
      changes)))

(defn generate-toggle-token-set
  "Toggle a token set at `set-name` in `tokens-lib` without modifying a user theme."
  [changes tokens-status tokens-lib set-name]
  (let [set-id (ctob/get-id (ctob/get-set-by-name tokens-lib set-name))]
    (if set-id
      (generate-update-active-sets changes tokens-status tokens-lib
                                   #(if (contains? % set-id) (disj % set-id) (conj % set-id)))
      changes)))


;; ================== nuevo

(defn generate-activate-theme
  [changes tokens-status tokens-lib id]
  (let [tokens-status' (cfo/activate-theme tokens-status tokens-lib id)]
    (if (not= tokens-status tokens-status')
      (pcb/set-tokens-status changes tokens-status')
      changes)))

(defn generate-deactivate-theme
  [changes tokens-status tokens-lib id]
  (let [tokens-status' (cfo/deactivate-theme tokens-status tokens-lib id)]
    (if (not= tokens-status tokens-status')
      (pcb/set-tokens-status changes tokens-status')
      changes)))

(defn generate-set-theme-status
  [changes tokens-status tokens-lib id active?]
  (if active?
    (generate-activate-theme changes tokens-status tokens-lib id)
    (generate-deactivate-theme changes tokens-status tokens-lib id)))

(defn generate-toggle-theme
  [changes tokens-status tokens-lib id]
  (let [active? (ctos/theme-active? tokens-status id)]
    (if active?
      (generate-deactivate-theme changes tokens-status tokens-lib id)
      (generate-activate-theme changes tokens-status tokens-lib id))))

(defn generate-sync-tokens-status-with-lib
  "Synchronizes tokens status with the current tokens lib"
  [changes tokens-status tokens-lib]
  (let [tokens-status' (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)]
    (if (not= tokens-status tokens-status')
      (pcb/set-tokens-status changes tokens-status')
      changes)))

;; ======= fin nuevo

(defn generate-toggle-token-set-group
  "Toggle a token set group at `group-path` in `tokens-lib` without modifying a user theme."
  [changes tokens-status tokens-lib group-path]
  (let [sets-at-path (ctob/get-sets-at-path tokens-lib group-path)
        set-ids      (into #{} (map ctob/get-id) sets-at-path)
        active?      (cfo/sets-at-path-all-active? tokens-status tokens-lib group-path)]
    (if (contains? #{:all :partial} active?)
      ;; Deactivate all sets at path
      (generate-update-active-sets changes tokens-status tokens-lib
                                   #(set/difference % set-ids))
      ;; Activate all sets at path
      (generate-update-active-sets changes tokens-status tokens-lib
                                   #(set/union % set-ids)))))

(defn vec-starts-with? [v1 v2]
  (= (subvec v1 0 (min (count v1) (count v2))) v2))

(defn- calculate-move-token-set-or-set-group
  [tokens-lib {:keys [from-index to-index position collapsed-paths]
               :or {collapsed-paths #{}}}]
  (let [tree (-> (ctob/get-set-tree tokens-lib)
                 (ctob/walk-sets-tree-seq :skip-children-pred #(contains? collapsed-paths %)))

        from (nth tree from-index)
        to (nth tree to-index)
        before (case position
                 :top to
                 :bot (nth tree (inc to-index) nil)
                 :center nil)

        prev-before (if (:group? from)
                      (->> (drop (inc from-index) tree)
                           (filter (fn [element]
                                     (<= (:depth element) (:depth from))))
                           (first))
                      (nth tree (inc from-index) nil))

        drop-as-direct-group-child? (or
                                     (= :center position)
                                     (and
                                      (= :bot position)
                                      (:group? to)
                                      (not (get collapsed-paths (:path to)))))

        from-path (:path from)
        to-parent-path (if drop-as-direct-group-child?
                         (:path to)
                         (into [] (butlast (:path to))))
        to-path (conj to-parent-path (last from-path))

        identical? (or (= from-index to-index)
                       (and (= from-path to-path)
                            (case position
                              :top (= from-index (dec to-index))
                              :bot (= from-index to-index)
                              nil)))
        to-exists? (and
                    (not= (:parent-path from) to-parent-path)
                    (if (:group? from)
                      (ctob/set-group-path-exists? tokens-lib to-path)
                      (ctob/set-path-exists? tokens-lib to-path)))
        parent-to-child-drop? (and
                               (not= (:parent-path from) to-parent-path)
                               (:group? from)
                               (vec-starts-with? to-path (:path from)))]
    (cond
      identical? nil
      to-exists?
      (throw (ex-info "move token set error: path exists"
                      {:error :path-exists
                       :path to-path}))
      parent-to-child-drop?
      (throw (ex-info "move token set error: parent-to-child"
                      {:error :parent-to-child
                       :from-path from-path
                       :to-path to-path}))
      :else
      (cond-> {:from-path from-path
               :to-path to-path
               :before-path nil
               :before-group? nil}
        before (assoc :before-path (:path before)
                      :before-group? (:group? before))
        prev-before (assoc :prev-before-path (:path prev-before)
                           :prev-before-group? (:group? prev-before))))))

(defn generate-move-token-set
  "Create changes for dropping a token set or token set.
  Throws for impossible moves."
  [changes tokens-lib params]
  (if-let [params (calculate-move-token-set-or-set-group tokens-lib params)]
    (pcb/move-token-set changes params)
    changes))

(defn generate-move-token-set-group
  "Create changes for dropping a token set or token set group.
  Throws for impossible moves"
  [changes tokens-lib params]
  (if-let [params (calculate-move-token-set-or-set-group tokens-lib params)]
    (pcb/move-token-set-group changes params)
    changes))

(defn generate-delete-token-set-group
  "Create changes for deleting a token set group."
  [changes tokens-lib path]
  (let [sets (ctob/get-sets-at-path tokens-lib path)]
    (reduce (fn [changes set]
              (pcb/set-token-set changes (ctob/get-id set) nil))
            changes
            sets)))

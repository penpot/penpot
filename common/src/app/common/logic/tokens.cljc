;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.tokens
  (:require
   [app.common.files.changes :as ch]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.types.tokens-lib :as ctob]))

;; Tokens lib

(defn- vec-starts-with? [v1 v2]
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

(declare generate-sync-tokens-status-with-lib)

(defn generate-update-token-theme
  "Create changes for updating a token theme and regenerating the activation status
   of the sets inside it."
  [changes token-theme]
  (let [changes' (pcb/set-token-theme changes (ctob/get-id token-theme) token-theme)
        data (pcb/get-library-data changes')
        tokens-status (cfo/get-tokens-status data)
        tokens-lib' (-> data
                        (ch/process-changes (:redo-changes changes'))
                        (cfo/get-tokens-lib))]
    (generate-sync-tokens-status-with-lib changes'
                                          tokens-status
                                          tokens-lib')))

(defn generate-move-token-set
  "Create changes for dropping a token set or token set.
  Throws for impossible moves."
  [changes tokens-lib params]
  (if tokens-lib
    (if-let [params (calculate-move-token-set-or-set-group tokens-lib params)]
      (pcb/move-token-set changes params)
      changes)
    changes))

(defn generate-move-token-set-group
  "Create changes for dropping a token set or token set group.
  Throws for impossible moves"
  [changes tokens-lib params]
  (if tokens-lib
    (if-let [params (calculate-move-token-set-or-set-group tokens-lib params)]
      (pcb/move-token-set-group changes params)
      changes)
    changes))

(defn generate-delete-token-set-group
  "Create changes for deleting a token set group."
  [changes tokens-lib path]
  (if tokens-lib
    (let [sets (ctob/get-sets-at-path tokens-lib path)]
      (reduce (fn [changes set]
                (pcb/set-token-set changes (ctob/get-id set) nil))
              changes
              sets))
    changes))

;; Tokens Status

(defn- update-tokens-status
  [changes tokens-status update-fn & args]
  (if tokens-status
    (let [tokens-status' (apply update-fn tokens-status args)]
      (if (not= tokens-status tokens-status')
        (pcb/set-tokens-status changes tokens-status')
        changes))
    changes))

(defn generate-activate-theme
  [changes tokens-status tokens-lib id]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/activate-theme tokens-lib id)
    changes))

(defn generate-deactivate-theme
  [changes tokens-status tokens-lib id]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/deactivate-theme tokens-lib id)
    changes))

(defn generate-set-theme-status
  [changes tokens-status tokens-lib id active?]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/set-theme-active tokens-lib id active?)
    changes))

(defn generate-toggle-theme
  [changes tokens-status tokens-lib id]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/toggle-theme-active tokens-lib id)
    changes))

(defn generate-set-enabled-token-set
  [changes tokens-status tokens-lib id enabled?]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/set-set-active tokens-lib id enabled?)
    changes))

(defn generate-toggle-token-set
  [changes tokens-status tokens-lib id]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/toggle-set-active tokens-lib id)
    changes))

(defn generate-toggle-token-set-group
  [changes tokens-status tokens-lib group-path]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/toggle-set-group-active tokens-lib group-path)
    changes))

(defn generate-sync-tokens-status-with-lib
  [changes tokens-status tokens-lib]
  (if tokens-lib
    (update-tokens-status changes tokens-status cfo/sync-tokens-status-with-lib tokens-lib)
    changes))

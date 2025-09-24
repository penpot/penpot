;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.logic.tokens
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.tokens-lib :as ctob]))

(defn generate-update-active-sets
  "Copy the active sets from the currently active themes and move them
  to the hidden token theme and update the theme with
  `update-theme-fn`.

  Use this for managing sets active state without having to modify a
  user created theme (\"no themes selected\" state in the ui)."
  [changes tokens-lib update-theme-fn]
  (let [active-token-set-names (ctob/get-active-themes-set-names tokens-lib)

        hidden-theme  (ctob/get-hidden-theme tokens-lib)
        hidden-theme' (-> (some-> hidden-theme
                                  (ctob/set-sets active-token-set-names))
                          (update-theme-fn))]
    (-> changes
        (pcb/set-active-token-themes #{(ctob/get-theme-path hidden-theme')})
        (pcb/set-token-theme (ctob/get-id hidden-theme)
                             hidden-theme'))))

(defn generate-toggle-token-set
  "Toggle a token set at `set-name` in `tokens-lib` without modifying a
  user theme."
  [changes tokens-lib set-name]
  (generate-update-active-sets changes tokens-lib #(ctob/toggle-set % set-name)))

(defn toggle-token-set-group
  "Toggle a token set group at `group-path` in `tokens-lib` for a `tokens-lib-theme`."
  [group-path tokens-lib tokens-lib-theme]
  (let [deactivate? (contains? #{:all :partial} (ctob/sets-at-path-all-active? tokens-lib group-path))
        sets-names  (->> (ctob/get-sets-at-path tokens-lib group-path)
                         (map ctob/get-name)
                         (into #{}))]
    (if deactivate?
      (ctob/disable-sets tokens-lib-theme sets-names)
      (ctob/enable-sets tokens-lib-theme sets-names))))

(defn generate-toggle-token-set-group
  "Toggle a token set group at `group-path` in `tokens-lib` without modifying a user theme."
  [changes tokens-lib group-path]
  (generate-update-active-sets changes tokens-lib #(toggle-token-set-group group-path tokens-lib %)))

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
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
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [tokenscript.processor :as tsp]))

;; Tokens source

(defn- own-tokens-status
  "`tokens-status` keeping only the themes and sets that exist in the own
   tokens lib of `file-data`."
  [file-data tokens-status]
  (let [tokens-lib (cfo/get-tokens-lib file-data)]
    (if (and tokens-status tokens-lib)
      (cfo/sync-tokens-status-with-lib tokens-status tokens-lib)
      (ctos/make-tokens-status))))

(defn generate-set-tokens-source
  "Create changes for setting the tokens source of a file to `library`,
   copying the library's tokens status.

   While an external library is the source, the tokens status of the file's
   own tokens lib is kept aside, and it is restored when the file goes back
   to being its own source (the library is the file itself, or nil). With
   nothing kept aside, going back to the file itself deactivates the themes
   and sets that don't exist in its own tokens lib, and a nil library clears
   the tokens status."
  [changes library]
  (let [file-data      (pcb/get-library-data changes)
        file-id        (:id file-data)
        library-id     (:id library)
        to-own?        (or (nil? library) (= library-id file-id))
        from-own?      (cfo/effective-tokens-source? file-data file-id)
        current-status (cfo/get-tokens-status file-data)
        kept-status    (cfo/get-own-tokens-status file-data)

        tokens-status
        (cond
          (and to-own? (some? kept-status))
          (own-tokens-status file-data kept-status)

          (nil? library)
          (ctos/make-tokens-status)

          to-own?
          (own-tokens-status file-data current-status)

          :else
          (-> library ctf/file-data cfo/get-tokens-status))

        kept-status'
        (cond
          to-own?   nil
          from-own? (own-tokens-status file-data current-status)
          :else     kept-status)]

    (cond-> (-> changes
                (pcb/set-tokens-source library-id)
                (pcb/set-tokens-status tokens-status))
      (not (identical? kept-status kept-status'))
      (pcb/set-own-tokens-status kept-status'))))

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

;; Token renames
;;
;; A token value references other tokens by name. Which tokens a value
;; references is what tokenscript parses it to, so a rename rewrites the
;; references themselves, never a matching piece of text. Shapes apply
;; tokens by name too, in `:applied-tokens`.

(defn- shapes-applying
  "The shapes of `container` with one of the tokens of `names` applied."
  [container names]
  (filter (fn [shape] (some names (vals (:applied-tokens shape))))
          (ctn/shapes-seq container)))

(defn- generate-rename-applied-tokens
  [changes file-data renames]
  (reduce (fn [changes container]
            (let [ids (map :id (shapes-applying container renames))]
              (if (seq ids)
                ;; Each container gets changes of its own, so `with-file-data`
                ;; finds the objects of that container.
                (->> (-> (pcb/empty-changes)
                         (pcb/with-container container)
                         (pcb/with-file-data file-data)
                         (pcb/update-shapes ids
                                            (fn [shape]
                                              (update shape :applied-tokens update-vals #(get renames % %)))
                                            {:ignore-touched true}))
                     (pcb/concat-changes changes))
                changes)))
          changes
          (ctf/object-containers-seq file-data)))

(defn- generate-update-tokens
  "Sets the tokens of `updates`, `{[set-id token-name] f}`, to `f` of the
  token, once each."
  [changes tokens-lib updates]
  (reduce-kv (fn [changes [set-id token-name] f]
               (let [token (get (ctob/get-tokens tokens-lib set-id) token-name)]
                 (pcb/set-token changes set-id (ctob/get-id token)
                                (ctob/make-token (into {} (f token))))))
             changes
             updates))

(defn- token-value-updates
  "The new values of the tokens that reference a renamed token, in the
  sets of `scope`."
  [tokens-lib renames scope]
  (update-vals (tsp/reference-updates (cfo/get-token-sets tokens-lib) renames
                                      {:scope scope :token-value :value})
               (fn [value] #(assoc % :value value))))

(defn generate-rename-token-references
  "Makes the tokens and shapes that use the tokens of `renames`, old name
  to new name, use the new names. Token values follow in the sets the
  rename reaches (`:scope`, see `cfo/rename-scope`, all by default);
  shapes always follow. A copy keeps its sync with its main."
  [changes renames {:keys [scope set-id]}]
  (let [file-data  (pcb/get-library-data changes)
        tokens-lib (cfo/get-tokens-lib file-data)]
    (cond-> (generate-rename-applied-tokens changes file-data renames)
      tokens-lib
      (generate-update-tokens tokens-lib (token-value-updates tokens-lib renames (cfo/rename-scope file-data scope set-id))))))

(defn generate-rename-tokens
  "Renames the tokens of the set `set-id`, `renames` old name to new name.
  The names are checked against the sets the rename reaches (`:scope`,
  see `cfo/rename-scope`, all by default). With `update-references`, the
  tokens of those sets and the shapes that use the tokens follow. Throws
  when a new name collides."
  [changes set-id renames {:keys [update-references scope]}]
  (let [file-data  (pcb/get-library-data changes)
        tokens-lib (cfo/get-tokens-lib file-data)
        renames    (select-keys renames (keys (ctob/get-tokens tokens-lib set-id)))]
    (when-let [errors (seq (cfo/token-rename-collisions file-data set-id renames scope))]
      (throw (ex-info "token rename error: name collision"
                      {:error :name-collision
                       :errors (vec errors)})))
    ;; The library data of `changes` is not updated by the changes, so a
    ;; token both renamed and referencing a renamed one is set once.
    (let [name-updates (into {}
                             (map (fn [[old-name new-name]] [[set-id old-name] #(assoc % :name new-name)]))
                             renames)
          updates      (if update-references
                         (merge-with comp name-updates
                                     (token-value-updates tokens-lib renames (cfo/rename-scope file-data scope set-id)))
                         name-updates)]
      (cond-> (generate-update-tokens changes tokens-lib updates)
        update-references
        (generate-rename-applied-tokens file-data renames)))))

(defn count-token-references
  "How many tokens and applied shape attributes follow a rename of the
  tokens `names` (see `generate-rename-token-references`)."
  [file-data names {:keys [scope set-id]}]
  (let [names      (set names)
        tokens-lib (cfo/get-tokens-lib file-data)
        tokens     (if tokens-lib
                     (count (tsp/referencing-tokens (cfo/get-token-sets tokens-lib) names
                                                    {:scope (cfo/rename-scope file-data scope set-id)
                                                     :token-value :value}))
                     0)
        attributes (->> (ctf/object-containers-seq file-data)
                        (mapcat #(shapes-applying % names))
                        (mapcat (comp vals :applied-tokens))
                        (filter names)
                        (count))]
    (+ tokens attributes)))

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


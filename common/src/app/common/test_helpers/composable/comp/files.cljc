;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.files
  "File routing for the pure composable runner, including library inverses."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]))

(defn all-files
  [situation]
  (assoc (tm/aux-files situation) (:id (tm/file situation)) (tm/file situation)))

(defn libraries
  "Loaded library data, including unaccepted updates, as in the editor."
  [situation]
  (all-files situation))

(defn reference-libraries
  "Oracle inputs last used by this copy. Reset/swap can refresh one subtree
   while other copies still reflect an older library version. These inputs
   never replace the loaded data supplied to production operations."
  ([situation shape]
   (reference-libraries situation (:id (tm/file situation)) (:id shape)))
  ([situation file-id shape-id]
   (let [files (all-files situation)]
     (assoc (merge files
                   (get-in situation [:accepted-libraries file-id])
                   (get-in situation [:shape-libraries file-id shape-id]))
            file-id (get files file-id)))))

(defn object-trees
  [file]
  (concat (map :objects (vals (get-in file [:data :pages-index])))
          (keep (fn [component] (when (:deleted component) (:objects component)))
                (vals (get-in file [:data :components])))))

(defn component-shapes
  [file component]
  (let [objects (if (:deleted component) (:objects component)
                    (get-in file [:data :pages-index (:main-instance-page component) :objects]))]
    (cfh/get-children-with-self objects (:main-instance-id component))))

(defn dependent-components
  [file source-id component-ids]
  (into #{}
        (keep (fn [component]
                (when (some #(and (:shape-ref %) (= source-id (:component-file %))
                                  (contains? component-ids (:component-id %)))
                            (component-shapes file component))
                  (:id component))))
        (vals (get-in file [:data :components]))))

(defn affected-components
  "Same-file dependents, predicted from definitions before running sync."
  [file component-ids]
  (loop [ids (set component-ids)]
    (let [next-ids (into ids (dependent-components file (:id file) ids))]
      (if (= ids next-ids) ids (recur next-ids)))))

(defn remember-subtree
  [situation file-id root-id inputs]
  (if-not (:accepted-libraries situation)
    situation
    (let [file (get (all-files situation) file-id)
          objects (first (filter #(contains? % root-id) (object-trees file)))
          container (ctn/make-container {:objects objects} :page)
          shapes (cfh/get-children-with-self objects root-id)
          source-id (:component-file (get objects root-id))
          inputs (assoc inputs file-id file)
          retained (into {}
                         (mapcat (fn [shape]
                                   (when (and (not= root-id (:id shape)) (ctk/swapped? shape))
                                     (when-let [prior (get-in situation [:shape-libraries file-id (:id shape)])]
                                       (map #(vector (:id %) prior)
                                            (cfh/get-children-with-self objects (:id shape)))))))
                         shapes)]
      (reduce (fn [situation shape]
                (if-not (:shape-ref shape)
                  situation
                  (let [source (when-not (ctk/swapped? shape)
                                 (ctf/find-ref-shape file container inputs shape
                                                     :include-deleted? true :with-context? true))
                        inherited (when (:shape-ref source)
                                    (reference-libraries situation (get-in (meta source) [:file :id]) (:id source)))
                        ;; Retained swaps keep their content inputs. Ordinary
                        ;; images inherit their near copy's accepted inputs.
                        ;; This pull refreshes only its own source boundary.
                        sources (-> (merge inputs inherited (get retained (:id shape)))
                                    (assoc source-id (get inputs source-id))
                                    (dissoc file-id))]
                    (assoc-in situation [:shape-libraries file-id (:id shape)] sources))))
              situation shapes))))

(defn remember-copies
  [situation file-id source-id component-ids inputs]
  (if-not (:accepted-libraries situation)
    situation
    (let [source (get inputs source-id)
          source-shape-ids (into {}
                                 (map (fn [id]
                                        [id (into #{} (map :id)
                                                  (component-shapes source (get-in source [:data :components id])))]))
                                 component-ids)]
      (reduce (fn [situation shape]
                (if (and (:shape-ref shape) (= source-id (:component-file shape))
                         (contains? component-ids (:component-id shape))
                         ;; Normal sync refreshes direct copies. An indirect
                         ;; image still follows its enclosing component's
                         ;; accepted definition, even if this source is newer.
                         (or (ctk/swapped? shape)
                             (contains? (get source-shape-ids (:component-id shape)) (:shape-ref shape))))
                  (remember-subtree situation file-id (:id shape) inputs)
                  situation))
              situation (mapcat vals (object-trees (get (all-files situation) file-id)))))))

(defn freeze-libraries
  "Remember initial oracle baselines without hiding loaded library data."
  [situation]
  (let [files (all-files situation)]
    (assoc situation :accepted-libraries
           (into {} (map (fn [id] [id (dissoc files id)])) (keys files)))))

(defn accept-library
  [situation library-id]
  (if (:accepted-libraries situation)
    (assoc-in situation [:accepted-libraries (:id (tm/file situation)) library-id]
              (get (all-files situation) library-id))
    situation))

(defn- with-files
  [situation files]
  (let [id (:id (tm/file situation))]
    (-> situation
        (tm/with-file (get files id))
        (tm/with-aux-files (dissoc files id)))))

(defn route
  "Tag changes destined for another file, preserving their original order."
  [changes file-id]
  (let [tag #(mapv (fn [change]
                     (cond-> change (nil? (::destination change)) (assoc ::destination file-id))) %)]
    (-> changes (update :redo-changes tag) (update :undo-changes tag))))

(defn route-update-main
  [changes current-id main-id]
  (let [tag (fn [change]
              (cond-> (dissoc change :local-change?)
                (and (not (:local-change? change)) (not= current-id main-id))
                (assoc ::destination main-id)))
        tag-all #(mapv tag %)]
    (-> changes (update :redo-changes tag-all) (update :undo-changes tag-all))))

(defn apply-changes
  "Apply each consecutive run to its file, stripping runner routing metadata."
  ([situation changes] (apply-changes situation changes :redo-changes))
  ([situation changes side]
   (let [current-id (:id (tm/file situation))]
     (with-files situation
       (reduce (fn [files group]
                 (let [id (or (::destination (first group)) current-id)
                       file (get files id)]
                   (when-not file
                     (throw (ex-info "Missing composable change destination" {:file-id id})))
                   (assoc files id
                          (thf/apply-changes file
                                             {:redo-changes (mapv #(dissoc % ::destination) group)}
                                             :validate? false))))
               (all-files situation)
               (partition-by #(or (::destination %) current-id) (get changes side)))))))

(defn changed-components
  ([before changes]
   (let [current-id (:id (tm/file before))
         files (all-files before)]
     (into #{}
           (mapcat (fn [[id group]]
                     (cll/changed-components (:data (get files id)) {:redo-changes group})))
           (group-by #(or (::destination %) current-id) (:redo-changes changes)))))
  ([before after changes]
   (into (changed-components before changes)
         (mapcat (fn [libraries] (mapcat val libraries)))
         (vals (:library-pulls after)))))

(defn- sync-local
  [situation file-id ids]
  (let [files (all-files situation)
        context (-> situation
                    (tm/with-file (get files file-id))
                    (tm/with-aux-files (dissoc files file-id)))
        result (cll/generate-sync-components-cascade (pcb/empty-changes) file-id ids (libraries context)
                                                     (:id (tm/file situation)))
        changes (route (:changes result) file-id)]
    {:situation (-> (with-files situation (assoc-in files [file-id :data] (:data result)))
                    (remember-copies file-id file-id
                                     (affected-components (get files file-id) ids) files))
     :component-ids (affected-components (get files file-id) ids)
     :syncs (if (seq (:redo-changes changes)) [changes] [])}))

(defn- pull-components
  [situation consumer-id library-id component-ids]
  (reduce (fn [{:keys [situation syncs]} component-id]
            (let [inputs (all-files situation)
                  consumer (get inputs consumer-id)
                  result (cll/generate-sync-file-cascade
                          (pcb/empty-changes) nil :components consumer-id component-id library-id
                          inputs (:id (tm/file situation)))
                  changes (route (:changes result) consumer-id)
                  dependents (affected-components consumer
                                                  (dependent-components consumer library-id #{component-id}))
                  updated (-> (apply-changes situation changes)
                              (remember-copies consumer-id library-id #{component-id} inputs)
                              (remember-copies consumer-id consumer-id dependents inputs))]
              {:situation updated
               :syncs (cond-> syncs (seq (:redo-changes changes)) (conj changes))}))
          {:situation situation :syncs []} (sort-by str component-ids)))

(defn- sync-independent-world
  [situation component-ids]
  (let [files (all-files situation)
        owners (into {} (mapcat (fn [[id file]]
                                  (map #(vector % id) (keys (get-in file [:data :components]))))) files)]
    (reduce
     (fn [{:keys [situation syncs]} [file-id ids]]
       (let [settled (sync-local situation file-id ids)]
         (reduce (fn [{:keys [situation syncs]} [consumer-id requests]]
                   (let [requested (get requests file-id #{})
                         accepted (into #{} (filter requested) (:component-ids settled))
                         pulled (pull-components situation consumer-id file-id
                                                 (affected-components (get (all-files situation) file-id)
                                                                      accepted))]
                     {:situation (update-in (:situation pulled) [:library-pulls consumer-id file-id]
                                            #(apply disj (or % #{}) accepted))
                      :syncs (into syncs (:syncs pulled))}))
                 {:situation (:situation settled) :syncs (into syncs (:syncs settled))}
                 (:library-pulls (:situation settled)))))
     {:situation situation :syncs []}
     (sort-by (comp str key) (group-by owners component-ids)))))

(defn sync-world
  "Settle changed mains in their source files, then local inputs. Legacy
   worlds pull library changes immediately; snapshot worlds keep consumers
   stale until explicit acceptance. Cascades join their user's undo group."
  [situation component-ids]
  (if (:accepted-libraries situation)
    (sync-independent-world situation component-ids)
    (let [current-id (:id (tm/file situation))
          owners (into {} (mapcat (fn [[id file]]
                                    (map #(vector % id) (keys (get-in file [:data :components])))))
                       (all-files situation))
          by-file (group-by owners component-ids)
          foreign (sort-by (comp str key) (dissoc by-file current-id))
          result
          (reduce (fn [{:keys [situation syncs]} [library-id ids]]
                    (let [settled (sync-local situation library-id ids)
                          situation (:situation settled)
                          pulled (cll/generate-sync-file-cascade
                                  (pcb/empty-changes) nil :components current-id nil library-id
                                  (libraries situation) current-id)
                          changes (:changes pulled)]
                      {:situation (apply-changes situation changes)
                       :syncs (cond-> (into syncs (:syncs settled))
                                (seq (:redo-changes changes)) (conj changes))}))
                  {:situation situation :syncs []} foreign)
          local (sync-local (:situation result) current-id (get by-file current-id []))]
      {:situation (:situation local)
       :syncs (into (:syncs result) (:syncs local))})))

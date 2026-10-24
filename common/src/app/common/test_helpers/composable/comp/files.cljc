;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.test-helpers.composable.comp.files
  "File routing for the pure composable runner, including library inverses."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.libraries :as cll]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.test-helpers.files :as thf]))

(defn all-files
  [situation]
  (assoc (tm/aux-files situation) (:id (tm/file situation)) (tm/file situation)))

(defn- with-files
  [situation files]
  (let [id (:id (tm/file situation))]
    (-> situation
        (tm/with-file (get files id))
        (tm/with-aux-files (dissoc files id)))))

(defn route
  "Tag changes destined for another file, preserving their original order."
  [changes file-id]
  (let [tag #(mapv (fn [change] (assoc change ::destination file-id)) %)]
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
  [before changes]
  (let [current-id (:id (tm/file before))
        files (all-files before)]
    (into #{}
          (mapcat (fn [[id group]]
                    (cll/changed-components (:data (get files id)) {:redo-changes group})))
          (group-by #(or (::destination %) current-id) (:redo-changes changes)))))

(defn- sync-local
  [situation file-id ids]
  (let [files (all-files situation)
        result (cll/generate-sync-components-cascade (pcb/empty-changes) file-id ids files
                                                     (:id (tm/file situation)))
        changes (route (:changes result) file-id)]
    {:situation (with-files situation (assoc-in files [file-id :data] (:data result)))
     :syncs (if (seq (:redo-changes changes)) [changes] [])}))

(defn sync-world
  "Settle changed library mains first, pull their copies into the current
   file, then settle local inputs. Each cascade joins the same undo group."
  [situation component-ids]
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
                                (all-files situation) current-id)
                        changes (:changes pulled)]
                    {:situation (apply-changes situation changes)
                     :syncs (cond-> (into syncs (:syncs settled))
                              (seq (:redo-changes changes)) (conj changes))}))
                {:situation situation :syncs []} foreign)
        local (sync-local (:situation result) current-id (get by-file current-id []))]
    {:situation (:situation local)
     :syncs (into (:syncs result) (:syncs local))}))

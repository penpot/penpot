;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.data.workspace.guides
  (:require
   [app.main.data.workspace.changes :as dwc]
   [app.main.data.workspace.state-helpers :as wsh]
   [beicon.core :as rx]
   [potok.core :as ptk]))

(defn make-update-guide [guide]
  (fn [other]
    (cond-> other
      (= (:id other) (:id guide))
      (merge guide))))

(defn update-guides [guide]
  (ptk/reify ::update-guides
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            guides (-> state wsh/lookup-page-options (:guides []))
            guides-ids? (into #{} (map :id) guides)

            new-guides
            (if (guides-ids? (:id guide))
              ;; Update existing guide
              (mapv (make-update-guide guide) guides)

              ;; Add new guide
              (conj guides guide))
            
            rch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value new-guides}]
            uch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value guides}]]
        (rx/of
         (dwc/commit-changes
          {:redo-changes rch
           :undo-changes uch
           :origin it}))))))

(defn remove-guide [guide]
  (ptk/reify ::remove-guide
    ptk/WatchEvent
    (watch [it state _]
      (let [page-id (:current-page-id state)
            guides (-> state wsh/lookup-page-options (:guides []))
            new-guides (filterv #(not= (:id %) (:id guide)) guides)
            
            rch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value new-guides}]
            uch [{:type :set-option
                  :page-id page-id
                  :option :guides
                  :value guides}]]
        (rx/of
         (dwc/commit-changes
          {:redo-changes rch
           :undo-changes uch
           :origin it}))))))

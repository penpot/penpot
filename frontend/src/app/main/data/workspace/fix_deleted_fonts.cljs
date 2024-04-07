;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.fix-deleted-fonts
  (:require
   [app.common.data :as d]
   [app.common.files.helpers :as cfh]
   [app.common.text :as txt]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; This event will update the file so the texts with non existing custom fonts try to be fixed.
;; This can happen when:
;; - Exporting/importing files to different teams or penpot instances
;; - Moving files from one team to another in the same instance
;; - Custom fonts are explicitly deleted in the team area

(defn has-invalid-font-family
  [node]
  (let [fonts (deref fonts/fontsdb)]
    (and
     (some? (:font-family node))
     (nil? (get fonts (:font-id node))))))

(defn calculate-alternative-font-id
  [value]
  (let [fonts (deref fonts/fontsdb)]
    (->> (vals fonts)
         (filter #(= (:family %) value))
         (first)
         :id)))

(defn should-fix-deleted-font-shape?
  [shape]
  (let [text-nodes (txt/node-seq txt/is-text-node? (:content shape))]
    (and (cfh/text-shape? shape) (some has-invalid-font-family text-nodes))))

(defn should-fix-deleted-font-component?
  [component]
  (->> (:objects component)
       (vals)
       (d/seek should-fix-deleted-font-shape?)))

(defn should-fix-deleted-font-typography?
  [typography]
  (let [fonts (deref fonts/fontsdb)]
    (nil? (get fonts (:font-id typography)))))

(defn fix-deleted-font
  [node]
  (let [alternative-font-id (calculate-alternative-font-id (:font-family node))]
    (cond-> node
      (some? alternative-font-id) (assoc :font-id alternative-font-id))))

(defn fix-deleted-font-shape
  [shape]
  (let [transform (partial txt/transform-nodes has-invalid-font-family fix-deleted-font)]
    (update shape :content transform)))

(defn fix-deleted-font-component
  [component]
  (update component
          :objects
          (fn [objects]
            (d/mapm #(fix-deleted-font-shape %2) objects))))

(defn fix-deleted-font-typography
  [typography]
  (let [alternative-font-id (calculate-alternative-font-id (:font-family typography))]
    (cond-> typography
      (some? alternative-font-id) (assoc :font-id alternative-font-id))))

(defn fix-deleted-fonts
  []
  (ptk/reify ::fix-deleted-fonts
    ptk/WatchEvent
    (watch [it state _]
      (let [objects (wsh/lookup-page-objects state)

            ids (into #{}
                      (comp (filter should-fix-deleted-font-shape?) (map :id))
                      (vals objects))

            components (->> (wsh/lookup-local-components state)
                            (vals)
                            (filter should-fix-deleted-font-component?))

            component-changes
            (into []
                  (map (fn [component]
                         {:type :mod-component
                          :id (:id component)
                          :objects (-> (fix-deleted-font-component component) :objects)}))
                  components)

            typographies (->> (get-in state [:workspace-data :typographies])
                              (vals)
                              (filter should-fix-deleted-font-typography?))

            typography-changes
            (into []
                  (map (fn [typography]
                         {:type :mod-typography
                          :typography (fix-deleted-font-typography typography)}))
                  typographies)]

        (rx/concat
         (rx/of (dch/update-shapes ids #(fix-deleted-font-shape %) {:reg-objects? false
                                                                    :save-undo? false
                                                                    :ignore-tree true}))
         (if (empty? component-changes)
           (rx/empty)
           (rx/of (dch/commit-changes {:origin it
                                       :redo-changes component-changes
                                       :undo-changes []
                                       :save-undo? false})))

         (if (empty? typography-changes)
           (rx/empty)
           (rx/of (dch/commit-changes {:origin it
                                       :redo-changes typography-changes
                                       :undo-changes []
                                       :save-undo? false}))))))))

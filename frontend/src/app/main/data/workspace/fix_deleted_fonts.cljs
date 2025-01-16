;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.fix-deleted-fonts
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.text :as txt]
   [app.main.data.changes :as dwc]
   [app.main.data.helpers :as dsh]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; This event will update the file so the texts with non existing custom fonts try to be fixed.
;; This can happen when:
;; - Exporting/importing files to different teams or penpot instances
;; - Moving files from one team to another in the same instance
;; - Custom fonts are explicitly deleted in the team area

(defn- calculate-alternative-font-id
  [value]
  (let [fonts (deref fonts/fontsdb)]
    (->> (vals fonts)
         (filter #(= (:family %) value))
         (first)
         :id)))

(defn- has-invalid-font-family?
  [node]
  (let [fonts               (deref fonts/fontsdb)
        font-family         (:font-family node)
        alternative-font-id (calculate-alternative-font-id font-family)]
    (and (some? font-family)
         (nil? (get fonts (:font-id node)))
         (some? alternative-font-id))))

(defn- should-fix-deleted-font-shape?
  [shape]
  (let [text-nodes (txt/node-seq txt/is-text-node? (:content shape))]
    (and (cfh/text-shape? shape)
         (some has-invalid-font-family? text-nodes))))

(defn- should-fix-deleted-font-component?
  [component]
  (let [xf (comp (map val)
                 (filter should-fix-deleted-font-shape?))]
    (first (sequence xf (:objects component)))))

(defn- fix-deleted-font
  [node]
  (let [alternative-font-id (calculate-alternative-font-id (:font-family node))]
    (cond-> node
      (some? alternative-font-id) (assoc :font-id alternative-font-id))))

(defn- fix-deleted-font-shape
  [shape]
  (let [transform (partial txt/transform-nodes has-invalid-font-family? fix-deleted-font)]
    (update shape :content transform)))

(defn- fix-deleted-font-component
  [component]
  (update component
          :objects
          (fn [objects]
            (update-vals objects fix-deleted-font-shape))))

(defn fix-deleted-font-typography
  [typography]
  (let [alternative-font-id (calculate-alternative-font-id (:font-family typography))]
    (cond-> typography
      (some? alternative-font-id) (assoc :font-id alternative-font-id))))

(defn- generate-deleted-font-shape-changes
  [{:keys [objects id]}]
  (sequence
   (comp (map val)
         (filter should-fix-deleted-font-shape?)
         (map (fn [shape]
                {:type :mod-obj
                 :id (:id shape)
                 :page-id id
                 :operations [{:type :set
                               :attr :content
                               :val (:content (fix-deleted-font-shape shape))}
                              {:type :set
                               :attr :position-data
                               :val nil}]})))
   objects))

(defn- generate-deleted-font-components-changes
  [fdata]
  (sequence
   (comp (map val)
         (filter should-fix-deleted-font-component?)
         (map (fn [component]
                {:type :mod-component
                 :id (:id component)
                 :objects (-> (fix-deleted-font-component component) :objects)})))
   (:components fdata)))

(defn- generate-deleted-font-typography-changes
  [fdata]
  (sequence
   (comp (map val)
         (filter has-invalid-font-family?)
         (map (fn [typography]
                {:type :mod-typography
                 :typography (fix-deleted-font-typography typography)})))
   (:typographies fdata)))

(defn fix-deleted-fonts
  []
  (ptk/reify ::fix-deleted-fonts
    ptk/WatchEvent
    (watch [it state _]
      (let [fdata              (dsh/lookup-file-data state)
            pages              (:pages-index fdata)

            shape-changes      (mapcat generate-deleted-font-shape-changes (vals pages))
            components-changes (generate-deleted-font-components-changes fdata)
            typography-changes (generate-deleted-font-typography-changes fdata)
            changes            (concat shape-changes
                                       components-changes
                                       typography-changes)]
        (if (seq changes)
          (rx/of (dwc/commit-changes
                  {:origin it
                   :redo-changes (vec changes)
                   :undo-changes []
                   :save-undo? false}))
          (rx/empty))))))

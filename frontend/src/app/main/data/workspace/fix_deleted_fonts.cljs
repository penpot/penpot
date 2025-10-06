;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.fix-deleted-fonts
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.types.text :as txt]
   [app.main.data.changes :as dwc]
   [app.main.data.helpers :as dsh]
   [app.main.fonts :as fonts]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

;; This event will update the file so the texts with non existing
;; custom fonts try to be fixed. This can happen when:
;;
;; - Exporting/importing files to different teams or penpot instances
;; - Moving files from one team to another in the same instance
;; - Custom fonts are explicitly deleted in the team area

(defn- calculate-alternative-font-id
  [value]
  (let [fonts (deref fonts/fontsdb)]
    (reduce-kv (fn [_ _ font]
                 (if (= (:family font) value)
                   (reduced (:id font))
                   nil))
               nil
               fonts)))

(defn- has-invalid-font-family?
  [node]
  (let [fonts (deref fonts/fontsdb)
        font-family (:font-family node)]
    (and (some? font-family)
         (nil? (get fonts (:font-id node))))))

(defn- shape-has-invalid-font-family??
  [shape]
  (and (cfh/text-shape? shape)
       (some has-invalid-font-family?
             (txt/node-seq txt/is-text-node? (:content shape)))))

(defn- fix-deleted-font
  [node]
  (if-let [alternative-font-id (calculate-alternative-font-id (:font-family node))]
    (assoc node :font-id alternative-font-id)
    node))

(defn- fix-shape-content
  [shape]
  (txt/transform-nodes has-invalid-font-family? fix-deleted-font
                       (:content shape)))

(defn- fix-typography
  [typography]
  (if-let [alternative-font-id (calculate-alternative-font-id (:font-family typography))]
    (assoc typography :font-id alternative-font-id)
    typography))

(defn- generate-page-changes
  [{:keys [objects id]}]
  (reduce-kv (fn [changes shape-id shape]
               (if (shape-has-invalid-font-family?? shape)
                 (conj changes {:type :mod-obj
                                :id shape-id
                                :page-id id
                                :operations [{:type :set
                                              :attr :content
                                              :val (fix-shape-content shape)}
                                             {:type :set
                                              :attr :position-data
                                              :val nil}]})
                 changes))
             []
             objects))

(defn- generate-library-changes
  [fdata]
  (reduce-kv (fn [changes _ typography]
               (if (has-invalid-font-family? typography)
                 (conj changes {:type :mod-typography
                                :typography (fix-typography typography)})
                 changes))
             []
             (:typographies fdata)))

(defn fix-deleted-fonts-for-local-library
  "Looks the file local library for deleted fonts and emit changes if
  invalid but fixable typographyes found."
  [file-id]
  (ptk/reify ::fix-deleted-fonts-for-local-library
    ptk/WatchEvent
    (watch [it state _]
      (let [fdata (dsh/lookup-file-data state file-id)]
        (when-let [changes (-> (generate-library-changes fdata)
                               (not-empty))]
          (rx/of (dwc/commit-changes
                  {:origin it
                   :redo-changes changes
                   :undo-changes []
                   :save-undo? false})))))))

;; FIXME: would be nice to not execute this code twice per page in the
;; same working session, maybe some local memoization can improve that

(defn fix-deleted-fonts-for-page
  [file-id page-id]
  (ptk/reify ::fix-deleted-fonts-for-page
    ptk/WatchEvent
    (watch [it state _]
      (let [page (dsh/lookup-page state file-id page-id)]
        (when-let [changes (-> (generate-page-changes page)
                               (not-empty))]
          (rx/of (dwc/commit-changes
                  {:origin it
                   :redo-changes changes
                   :undo-changes []
                   :save-undo? false})))))))

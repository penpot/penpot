;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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

(defn- index-fonts-by-family
  [fonts]
  (reduce-kv (fn [index id font]
               (let [family (:family font)]
                 (if (contains? index family)
                   index
                   (assoc index family id))))
             {}
             fonts))

(defn- has-invalid-font-family?
  [fonts node]
  (and (some? (:font-family node))
       (nil? (get fonts (:font-id node)))))

(defn- fixable-font?
  [fonts families node]
  (and (has-invalid-font-family? fonts node)
       (contains? families (:font-family node))))

(defn- fix-deleted-font
  [families node]
  (assoc node :font-id (get families (:font-family node))))

(defn- generate-page-changes
  [{:keys [objects id]}]
  (let [fonts    (deref fonts/fontsdb)
        families (index-fonts-by-family fonts)
        fixable? (partial fixable-font? fonts families)]
    (reduce-kv (fn [changes shape-id shape]
                 (let [content (:content shape)]
                   (if (and (cfh/text-shape? shape)
                            (some (partial has-invalid-font-family? fonts)
                                  (txt/node-seq txt/is-text-node? content))
                            (some fixable? (txt/node-seq txt/is-node? content)))
                     (conj changes {:type :mod-obj
                                    :id shape-id
                                    :page-id id
                                    :operations [{:type :set
                                                  :attr :content
                                                  :val (txt/transform-nodes
                                                        fixable?
                                                        (partial fix-deleted-font families)
                                                        content)}
                                                 {:type :set
                                                  :attr :position-data
                                                  :val nil}]})
                     changes)))
               []
               objects)))

(defn- generate-library-changes
  [fdata]
  (let [fonts    (deref fonts/fontsdb)
        families (index-fonts-by-family fonts)]
    (reduce-kv (fn [changes _ typography]
                 (if (fixable-font? fonts families typography)
                   (conj changes {:type :mod-typography
                                  :typography (fix-deleted-font families typography)})
                   changes))
               []
               (:typographies fdata))))

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
                   :save-undo? false
                   :ignore-wasm? true})))))))

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
                   :save-undo? false
                   :ignore-wasm? true})))))))

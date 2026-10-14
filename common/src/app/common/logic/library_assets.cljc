;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.logic.library-assets
  "Sync of the shapes that use the colors and typographies of a library. The
   values come from the library; they are derived writes, so they set no
   override on a copy."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.types.library :as ctl]
   [app.common.types.text :as txt]))

(defn generate-sync-colors
  "Copy into `shape` the value of each color of the library `library-id` that
   it uses."
  [changes library-id shape libraries]
  (let [library-colors (get-in libraries [library-id :data :colors])]
    (pcb/update-shapes changes
                       [(:id shape)]
                       #(ctl/sync-colors % library-id library-colors)
                       {:ignore-touched true})))

(defn- generate-sync-text-shape
  [changes shape container update-node]
  (let [old-content (:content shape)
        new-content (txt/transform-nodes update-node old-content)
        set-content (fn [content]
                      {:type :mod-obj
                       :id (:id shape)
                       :operations [{:type :set
                                     :attr :content
                                     :val content
                                     :ignore-touched true}
                                    {:type :set
                                     :attr :position-data
                                     :val nil}]})]
    (if (= new-content old-content)
      changes
      (pcb/add-changes changes container [(set-content new-content)] [(set-content old-content)]))))

(defn generate-sync-typographies
  "Copy into the text `shape` the text styles of each typography of the
   library `library-id` that its runs use; a run whose typography is no
   longer in the library loses its reference."
  [changes library-id container shape libraries]
  (let [typographies  (get-in libraries [library-id :data :typographies])
        update-node (fn [node]
                      (if-let [typography (get typographies (:typography-ref-id node))]
                        (merge node (select-keys typography txt/typography-fields))
                        (dissoc node :typography-ref-id
                                :typography-ref-file)))]
    (generate-sync-text-shape changes shape container update-node)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.svg :as csvg]
   [app.common.svg.shapes-builder :as csvg.shapes-builder]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]))

(defn extract-name [href]
  (let [query-idx (str/last-index-of href "?")
        href (if (> query-idx 0) (subs href 0 query-idx) href)
        filename (->> (str/split href "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn upload-images
  "Extract all bitmap images inside the svg data, and upload them, associated to the file.
  Return a map {<href> <image-data>}."
  [svg-data file-id]
  (->> (rx/from (csvg/collect-images svg-data))
       (rx/map (fn [{:keys [href] :as item}]
                 (let [item (-> item
                                (assoc :file-id file-id)
                                (assoc :is-local true)
                                (assoc :name "image"))]
                   (if (str/starts-with? href "data:")
                     (assoc item :content (wapi/data-uri->blob href))
                     (-> item
                         (assoc :name (extract-name href))
                         (assoc :url href))))))
       (rx/mapcat (fn [item]
                    ;; TODO: :create-file-media-object-from-url is
                    ;; deprecated and this should be resolved in
                    ;; frontend
                    (->> (rp/cmd! (if (contains? item :content)
                                    :upload-file-media-object
                                    :create-file-media-object-from-url)
                                  (dissoc item :href))
                         ;; When the image uploaded fail we skip the shape
                         ;; returning `nil` will afterward not create the shape.
                         (rx/catch #(rx/of nil))
                         (rx/map #(vector (:href item) %)))))
       (rx/reduce conj {})))

(defn add-svg-shapes
  ([svg-data position]
   (add-svg-shapes nil svg-data position nil))

  ([id svg-data position {:keys [change-selection? ignore-selection?]
                          :or {ignore-selection? false change-selection? true}}]
   (ptk/reify ::add-svg-shapes
     ptk/WatchEvent
     (watch [it state _]
       (try
         (let [id              (d/nilv id (uuid/next))
               page-id         (:current-page-id state)
               objects         (dsh/lookup-page-objects state page-id)
               selected        (if ignore-selection? #{} (dsh/lookup-selected state))
               base            (cfh/get-base-shape objects selected)

               selected-id     (first selected)
               selected-frame? (and (= 1 (count selected))
                                    (= :frame (dm/get-in objects [selected-id :type])))

               base-id         (:parent-id base)

               frame-id        (if (or selected-frame? (empty? selected)
                                       (not= :frame (dm/get-in objects [base-id :type])))
                                 (ctst/top-nested-frame objects position)
                                 base-id)

               parent-id       (if (or selected-frame? (empty? selected))
                                 frame-id
                                 base-id)

               [new-shape new-children]
               (csvg.shapes-builder/create-svg-shapes id svg-data position objects frame-id parent-id selected true)

               changes         (-> (pcb/empty-changes it page-id)
                                   (pcb/with-objects objects)
                                   (pcb/add-object new-shape))

               changes         (reduce (fn [changes new-child]
                                         (pcb/add-object changes new-child))
                                       changes
                                       new-children)

               changes         (pcb/resize-parents changes
                                                   (->> (:redo-changes changes)
                                                        (filter #(= :add-obj (:type %)))
                                                        (map :id)
                                                        (reverse)
                                                        (vec)))
               undo-id         (js/Symbol)]

           (rx/of (dwu/start-undo-transaction undo-id)
                  (dch/commit-changes changes)
                  (when change-selection?
                    (dws/select-shapes (d/ordered-set (:id new-shape))))
                  (ptk/data-event :layout/update {:ids [(:id new-shape)]})
                  (dwu/commit-undo-transaction undo-id)))

         (catch :default cause
           (rx/throw {:type :svg-parser
                      :data cause})))))))


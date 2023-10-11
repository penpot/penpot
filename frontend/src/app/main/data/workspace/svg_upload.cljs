;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.svg-upload
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.svg :as csvg]
   [app.common.svg.shapes-builder :as csvg.shapes-builder]
   [app.common.types.shape-tree :as ctst]
   [app.main.data.workspace.changes :as dch]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.undo :as dwu]
   [app.main.repo :as rp]
   [app.util.webapi :as wapi]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [potok.core :as ptk]))

(defn extract-name [url]
  (let [query-idx (str/last-index-of url "?")
        url (if (> query-idx 0) (subs url 0 query-idx) url)
        filename (->> (str/split url "/") (last))
        ext-idx (str/last-index-of filename ".")]
    (if (> ext-idx 0) (subs filename 0 ext-idx) filename)))

(defn upload-images
  "Extract all bitmap images inside the svg data, and upload them, associated to the file.
  Return a map {<url> <image-data>}."
  [svg-data file-id]
  (->> (rx/from (csvg/collect-images svg-data))
       (rx/map (fn [uri]
                 (merge
                   {:file-id file-id
                    :is-local true
                    :url uri}
                   (if (str/starts-with? uri "data:")
                     {:name "image"
                      :content (wapi/data-uri->blob uri)}
                     {:name (extract-name uri)}))))
       (rx/mapcat (fn [uri-data]
                    (->> (rp/cmd! (if (contains? uri-data :content)
                                    :upload-file-media-object
                                    :create-file-media-object-from-url)
                                  uri-data)
                         ;; When the image uploaded fail we skip the shape
                         ;; returning `nil` will afterward not create the shape.
                         (rx/catch #(rx/of nil))
                         (rx/map #(vector (:url uri-data) %)))))
       (rx/reduce (fn [acc [url image]] (assoc acc url image)) {})))

(defn add-svg-shapes
  [svg-data position]
  (ptk/reify ::add-svg-shapes
    ptk/WatchEvent
    (watch [it state _]
      (try
        (let [page-id         (:current-page-id state)
              objects         (wsh/lookup-page-objects state page-id)
              frame-id        (ctst/top-nested-frame objects position)
              selected        (wsh/lookup-selected state)
              base            (cph/get-base-shape objects selected)

              selected-id     (first selected)
              selected-frame? (and (= 1 (count selected))
                                   (= :frame (dm/get-in objects [selected-id :type])))

              parent-id       (if (or selected-frame? (empty? selected))
                                frame-id
                                (:parent-id base))

              [new-shape new-children]
              (csvg.shapes-builder/create-svg-shapes svg-data position objects frame-id parent-id selected true)

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
                 (dws/select-shapes (d/ordered-set (:id new-shape)))
                 (ptk/data-event :layout/update [(:id new-shape)])
                 (dwu/commit-undo-transaction undo-id)))

        (catch :default cause
          (js/console.log (.-stack cause))
          (rx/throw {:type :svg-parser
                     :data cause}))))))

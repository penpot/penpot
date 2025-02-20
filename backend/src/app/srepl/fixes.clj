;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes
  "A misc of fix functions"
  (:refer-clojure :exclude [parse-uuid])
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cpc]
   [app.common.files.helpers :as cfh]
   [app.common.files.repair :as cfr]
   [app.common.files.validate :as cfv]
   [app.common.logging :as l]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.fdata :as feat.fdata]
   [app.srepl.helpers :as h]))

(defn disable-fdata-features
  [{:keys [id features] :as file} _]
  (when (or (contains? features "fdata/pointer-map")
            (contains? features "fdata/objects-map"))
    (l/warn :hint "disable fdata features" :file-id (str id))
    (-> file
        (update :data feat.fdata/process-pointers deref)
        (update :data feat.fdata/process-objects (partial into {}))
        (update :features disj "fdata/pointer-map" "fdata/objects-map"))))

(def sql:get-fdata-files
  "SELECT id FROM file
    WHERE deleted_at is NULL
      AND (features @> '{fdata/pointer-map}' OR
           features @> '{fdata/objects-map}')
    ORDER BY created_at DESC")

(defn find-fdata-pointers
  [{:keys [id features data] :as file} _]
  (when (contains? features "fdata/pointer-map")
    (let [pointers (feat.fdata/get-used-pointer-ids data)]
      (l/warn :hint "found pointers" :file-id (str id) :pointers pointers)
      nil)))

(defn repair-file-media
  "A helper intended to be used with `srepl.main/process-files!` that
  fixes all not propertly referenced file-media-object for a file"
  [{:keys [id data] :as file} & _]
  (let [conn  (db/get-connection h/*system*)
        used  (cfh/collect-used-media data)
        ids   (db/create-array conn "uuid" used)
        sql   "SELECT * FROM file_media_object WHERE id = ANY(?)"
        rows  (db/exec! conn [sql ids])
        index (reduce (fn [index media]
                        (if (not= (:file-id media) id)
                          (let [media-id (uuid/next)]
                            (l/wrn :hint "found not referenced media"
                                   :file-id (str id)
                                   :media-id (str (:id media)))

                            (db/insert! conn :file-media-object
                                        (-> media
                                            (assoc :file-id id)
                                            (assoc :id media-id)))
                            (assoc index (:id media) media-id))
                          index))
                      {}
                      rows)]

    (when (seq index)
      (binding [bfc/*state* (atom {:index index})]
        (update file :data (fn [fdata]
                             (-> fdata
                                 (update :pages-index #'bfc/relink-shapes)
                                 (update :components #'bfc/relink-shapes)
                                 (update :media #'bfc/relink-media)
                                 (d/without-nils))))))))


(defn repair-file
  "Internal helper for validate and repair the file. The operation is
  applied multiple times untile file is fixed or max iteration counter
  is reached (default 10)"
  [file libs & {:keys [max-iterations] :or {max-iterations 10}}]

  (let [validate-and-repair
        (fn [file libs iteration]
          (when-let [errors (not-empty (cfv/validate-file file libs))]
            (l/trc :hint "repairing file"
                   :file-id (str (:id file))
                   :iteration iteration
                   :errors (count errors))
            (let [changes (cfr/repair-file file libs errors)]
              (-> file
                  (update :revn inc)
                  (update :data cpc/process-changes changes)))))

        process-file
        (fn [file libs]
          (loop [file      file
                 iteration 0]
            (if (< iteration max-iterations)
              (if-let [file (validate-and-repair file libs iteration)]
                (recur file (inc iteration))
                file)
              (do
                (l/wrn :hint "max retry num reached on repairing file"
                       :file-id (str (:id file))
                       :iteration iteration)
                file))))

        file'
        (process-file file libs)]

    (when (not= (:revn file) (:revn file'))
      (l/trc :hint "file repaired" :file-id (str (:id file))))

    file'))

(defn fix-touched-shapes-group
  [file _]
  ;; Remove :shapes-group from the touched elements
  (letfn [(fix-fdata [data]
            (-> data
                (update :pages-index update-vals fix-container)))

          (fix-container [container]
            (d/update-when container :objects update-vals fix-shape))

          (fix-shape [shape]
            (d/update-when shape :touched
                           (fn [touched]
                             (disj touched :shapes-group))))]
    file  (-> file
              (update :data fix-fdata))))

(defn add-swap-slots
  [file libs _opts]
  ;; Detect swapped copies and try to generate a valid swap-slot.
  (letfn [(process-fdata [data]
            ;; Walk through all containers in the file, both pages and deleted components.
            (reduce process-container data (ctf/object-containers-seq data)))

          (process-container [data container]
            ;; Walk through all shapes in depth-first tree order.
            (l/dbg :hint "Processing container" :type (:type container) :name (:name container))
            (let [root-shape (ctn/get-container-root container)]
              (ctf/update-container data
                                    container
                                    #(reduce process-shape % (ctn/get-direct-children container root-shape)))))

          (process-shape [container shape]
            ;; Look for head copies in the first level (either component roots or inside main components).
            ;; Even if they have been swapped, we don't add slot to them because there is no way to know
            ;; the original shape. Only children.
            (if (and (ctk/instance-head? shape)
                     (ctk/in-component-copy? shape)
                     (nil? (ctk/get-swap-slot shape)))
              (process-copy-head container shape)
              (reduce process-shape container (ctn/get-direct-children container shape))))

          (process-copy-head [container head-shape]
            ;; Process recursively all children, comparing each one with the corresponding child in the main
            ;; component, looking by position. If the shape-ref does not point to the found child, then it has
            ;; been swapped and need to set up a slot.
            (l/trc :hint "Processing copy-head" :id (:id head-shape) :name (:name head-shape))
            (let [component-shape     (ctf/find-ref-shape file container libs head-shape :include-deleted? true :with-context? true)
                  component-container (:container (meta component-shape))]
              (loop [container          container
                     children           (map #(ctn/get-shape container %) (:shapes head-shape))
                     component-children (map #(ctn/get-shape component-container %) (:shapes component-shape))]
                (let [child           (first children)
                      component-child (first component-children)]
                  (if (or (nil? child) (nil? component-child))
                    container
                    (let [container (if (and (not (ctk/is-main-of? component-child child true))
                                             (nil? (ctk/get-swap-slot child))
                                             (ctk/instance-head? child))
                                      (let [slot (guess-swap-slot component-child component-container)]
                                        (l/dbg :hint "child" :id (:id child) :name (:name child) :slot slot)
                                        (ctn/update-shape container (:id child) #(ctk/set-swap-slot % slot)))
                                      container)]
                      (recur (process-copy-head container child)
                             (rest children)
                             (rest component-children))))))))

          (guess-swap-slot [shape container]
            ;; To guess the slot, we must follow the chain until we find the definitive main. But
            ;; we cannot navigate by shape-ref, because main shapes may also have been swapped. So
            ;; chain by position, too.
            (if-let [slot (ctk/get-swap-slot shape)]
              slot
              (if-not (ctk/in-component-copy? shape)
                (:id shape)
                (let [head-copy (ctn/get-component-shape (:objects container) shape)]
                  (if (= (:id head-copy) (:id shape))
                    (:id shape)
                    (let [head-main (ctf/find-ref-shape file
                                                        container
                                                        libs
                                                        head-copy
                                                        :include-deleted? true
                                                        :with-context? true)
                          container-main (:container (meta head-main))
                          shape-main (find-match-by-position shape
                                                             head-copy
                                                             container
                                                             head-main
                                                             container-main)]
                      (guess-swap-slot shape-main container-main)))))))

          (find-match-by-position [shape-copy head-copy container-copy head-main container-main]
            ;; Find the shape in the main that has the same position under its parent than
            ;; the copy under its one. To get the parent we must process recursively until
            ;; the component head, because mains may also have been swapped.
            (let [parent-copy   (ctn/get-shape container-copy (:parent-id shape-copy))
                  parent-main   (if (= (:id parent-copy) (:id head-copy))
                                  head-main
                                  (find-match-by-position parent-copy
                                                          head-copy
                                                          container-copy
                                                          head-main
                                                          container-main))
                  index         (cfh/get-position-on-parent (:objects container-copy)
                                                            (:id shape-copy))
                  shape-main-id (dm/get-in parent-main [:shapes index])]
              (ctn/get-shape container-main shape-main-id)))]

    file  (-> file
              (update :data process-fdata))))



(defn fix-find-duplicated-slots
  [file _]
  ;; Find the shapes whose children have duplicated slots
  (let [check-duplicate-swap-slot
        (fn [shape page]
          (let [shapes   (map #(get (:objects page) %) (:shapes shape))
                slots    (->> (map #(ctk/get-swap-slot %) shapes)
                              (remove nil?))
                counts   (frequencies slots)]
            #_(when (some (fn [[_ count]] (> count 1)) counts)
                (l/trc :info "This shape has children with the same swap slot" :id (:id shape) :file-id (str (:id file))))
            (some (fn [[_ count]] (> count 1)) counts)))

        count-slots-shape
        (fn [page shape]
          (if (ctk/instance-root? shape)
            (check-duplicate-swap-slot shape page)
            false))

        count-slots-page
        (fn [page]
          (->> (:objects page)
               (vals)
               (mapv #(count-slots-shape page %))
               (filter true?)
               count))

        count-slots-data
        (fn [data]
          (->> (:pages-index data)
               (vals)
               (mapv count-slots-page)
               (reduce +)))

        num-missing-slots (count-slots-data (:data file))]

    (when (pos? num-missing-slots)
      (l/trc :info (str "Shapes with children with the same swap slot: " num-missing-slots) :file-id (str (:id file))))
    file))

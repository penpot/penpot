;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes
  "A collection of adhoc fixes scripts."
  (:require
   [app.common.data :as d]
   [app.common.logging :as l]
   [app.common.pages.helpers :as cph]
   [app.common.pprint :refer [pprint]]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc.commands.files :as files]
   [app.srepl.helpers :as h]
   [app.util.blob :as blob]))

(defn validate-file
  [file]
  (let [libs (->> (files/get-file-libraries app.srepl.helpers/*conn* (:id file))
                  (cons file)
                  (map #(files/get-file app.srepl.helpers/*conn* (:id %) (:features file)))
                  (d/index-by :id))

        update-page (fn [page]
                      (let [errors (ctf/validate-shape uuid/zero file page libs)]
                        (when (seq errors)
                          (prn "******Errors in file " (:id file) " page " (:id page))
                          (pprint errors {:level 3}))))]

    (update file :data h/update-pages update-page)))

(defn repair-orphaned-shapes
  "There are some shapes whose parent has been deleted. This function
  detects them and puts them as children of the root node."
  ([data]
   (letfn [(is-orphan? [shape objects]
             (and (some? (:parent-id shape))
                  (nil? (get objects (:parent-id shape)))))

           (update-page [page]
             (let [objects (:objects page)
                   orphans (into #{} (filter #(is-orphan? % objects)) (vals objects))]
               (if (seq orphans)
                 (do
                   (l/info :hint "found a file with orphans" :file-id (:id data) :broken-shapes (count orphans))
                   (-> page
                       (h/update-shapes (fn [shape]
                                          (if (contains? orphans shape)
                                            (assoc shape :parent-id uuid/zero)
                                            shape)))
                       (update-in [:objects uuid/zero :shapes] into (map :id) orphans)))
                 page)))]

     (h/update-pages data update-page)))

  ;; special arity for to be called from h/analyze-files to search for
  ;; files with possible issues

  ([file state]
   (repair-orphaned-shapes (:data file))
   (update state :total (fnil inc 0))))

(defn rename-layout-attrs
  ([file]
   (let [found? (volatile! false)]
     (letfn [(update-shape
               [shape]
               (when (or (= (:layout-flex-dir shape) :reverse-row)
                         (= (:layout-flex-dir shape) :reverse-column)
                         (= (:layout-wrap-type shape) :no-wrap))
                 (vreset! found? true))
               (cond-> shape
                 (= (:layout-flex-dir shape) :reverse-row)
                 (assoc :layout-flex-dir :row-reverse)
                 (= (:layout-flex-dir shape) :reverse-column)
                 (assoc :layout-flex-dir :column-reverse)
                 (= (:layout-wrap-type shape) :no-wrap)
                 (assoc :layout-wrap-type :nowrap)))

             (update-page
               [page]
               (h/update-shapes page update-shape))]

       (let [new-file (update file :data h/update-pages update-page)]
         (when @found?
           (l/info :hint "Found attrs to rename in file"
                   :id (:id file)
                   :name (:name file)))
         new-file))))

   ([file state]
    (rename-layout-attrs file)
    (update state :total (fnil inc 0))))

(defn fix-components-shaperefs
  ([file]
   (if-not (contains? (:features file) "components/v2")
     (prn "   This file is not v2")
     (let [libs (->> (files/get-file-libraries app.srepl.helpers/*conn* (:id file))
                     (cons file)
                     (map #(files/get-file app.srepl.helpers/*conn* (:id %) (:features file)))
                     (d/index-by :id))

           fix-copy-item
           (fn fix-copy-item [allow-head shapes-copy shapes-base copy-id base-id]
             (let [copy (first (filter #(= (:id %) copy-id) shapes-copy))
                  ;; do nothing if it is a copy inside of a copy. It will be treated later
                   stop? (and (not allow-head) (ctk/instance-head? copy))
                   base (first (filter #(= (:id %) base-id) shapes-base))
                   fci (partial fix-copy-item false shapes-copy shapes-base)

                   updates (if (and
                                (not stop?)
                                (not= (:shape-ref copy) base-id))
                             [[(:id copy) base-id]]
                             [])

                   child-updates (if (and
                                      (not stop?)
                                      ;; If the base has the same number of childrens than the copy, we asume
                                      ;; that the shaperefs can be fixed ad pointed in the same order
                                      (= (count (:shapes copy)) (count (:shapes base))))
                                   (apply concat (map fci (:shapes copy) (:shapes base)))
                                   [])]
               (concat updates child-updates)))

           fix-copy
           (fn [objects updates copy]
             (let [component        (ctf/find-component libs (:component-id copy) {:include-deleted? true})
                   component-file   (get libs (:component-file copy))
                   component-shapes (ctf/get-component-shapes (:data component-file) component)
                   copy-shapes      (cph/get-children-with-self objects (:id copy))

                   copy-updates (fix-copy-item true copy-shapes component-shapes (:id copy) (:main-instance-id component))]
               (concat updates copy-updates)))

           update-page
           (fn [page]
             (let [objects      (:objects page)
                   fc           (partial fix-copy objects)
                   copies       (->> objects
                                     vals
                                     (filter #(and (ctk/instance-head? %) (not (ctk/main-instance? %)))))
                               updates      (reduce fc [] copies)
                   updated-page (reduce (fn [p [id shape-ref]]
                                          (assoc-in p [:objects id :shape-ref] shape-ref))
                                        page
                                        updates)]
               (prn (str "Page " (:name page) " - Fixing " (count updates)))
               updated-page))]

       (prn (str "Updating " (:name file) " " (:id file)))
       (update file :data h/update-pages update-page))))

  ([file save?]
   (let [file (-> file
                  (update :data blob/decode)
                  (fix-components-shaperefs))]
     (when save?
       (let [features (db/create-array h/*conn* "text" (:features file))
             data     (blob/encode (:data file))]
         (db/update! h/*conn* :file
                     {:data data
                      ;; :revn (:revn file)
                      :features features}
                     {:id (:id file)})

         (when (contains? (:features file) "storage/pointer-map")
           (files/persist-pointers! h/*conn* (:id file))))))))

(defn fix-component-root
  ([file]
   (let [update-shape (fn [page shape]
                        (let [parent (get (:objects page) (:parent-id shape))]
                          (if (and parent
                                   (:component-root shape)
                                   (:shape-ref parent))
                            (do
                              (prn (str "   Shape " (:name shape) " " (:id shape)))
                              (dissoc shape :component-root))
                            shape)))

         update-page (fn [page]
                       (prn (str "Page " (:name page)))
                       (h/update-shapes page (partial update-shape page)))]

     (prn (str "Updating " (:name file) " " (:id file)))
     (update file :data h/update-pages update-page)))

  ([file save?]
   (let [file (-> file
                  (update :data blob/decode)
                  (fix-component-root))]
     (when save?
       (let [features (db/create-array h/*conn* "text" (:features file))
             data     (blob/encode (:data file))]
         (db/update! h/*conn* :file
                     {:data data
                      ;; :revn (:revn file)
                      :features features}
                     {:id (:id file)})

         (when (contains? (:features file) "storage/pointer-map")
           (files/persist-pointers! h/*conn* (:id file))))))))

(defn update-near-components
  ([file]
   (prn (str "Updating " (:name file) " " (:id file)))
   (if-not (contains? (:features file) "components/v2")
     (prn "   This file is not v2")
     (let [libs (->> (files/get-file-libraries h/*conn* (:id file))
                     (cons file)
                     (map #(files/get-file h/*conn* (:id %) (:features file)))
                     (d/index-by :id))

           update-shape
           (fn [page shape]
             (if-not (:shape-ref shape)
               shape
               (do
                 ;; Uncomment prn's to debug
                 ;; (prn (str "  -> Shape " (:name shape) " " (:id shape) " shape-ref " (:shape-ref shape)))
                 (let [root-shape (ctn/get-copy-root (:objects page) shape)]
                   (if root-shape
                     (let [component (ctf/get-component libs (:component-file root-shape) (:component-id root-shape) {:include-deleted? true})
                           component-file (get libs (:component-file root-shape))
                           component-shapes (ctf/get-component-shapes (:data component-file) component)
                           ref-shape (d/seek #(= (:id %) (:shape-ref shape)) component-shapes)]
                       (if-not (and component component-file component-shapes)
                         (do
                           ;; (prn (str "  -> Shape " (:name shape) " " (:id shape) " shape-ref " (:shape-ref shape)))
                           ;; (when-not component (prn "     (component not found)"))
                           ;; (when-not component-file (prn "     (component-file not found)"))
                           ;; (when-not component-shapes (prn "     (component-shapes not found)"))
                           shape)
                         (if ref-shape
                           shape  ; This means that the copy is not nested, or this script already was run
                           (let [near-shape (d/seek #(= (:shape-ref %) (:shape-ref shape)) component-shapes)]
                             (if near-shape
                               (do
                                 (prn (str "  -> Shape " (:name shape) " " (:id shape) " shape-ref " (:shape-ref shape)))
                                 (prn (str "     new ref-shape " (:id near-shape)))
                                 (assoc shape :shape-ref (:id near-shape)))
                               (do
                                 ;; We assume in this case that this is a fostered sub instance, so we do nothing
                                 ;; (prn (str "  -> Shape " (:name shape) " " (:id shape) " shape-ref " (:shape-ref shape)))
                                 ;; (prn "     (near-shape not found)")
                                 shape))))))
                     (do
                       ;; (prn (str "  -> Shape " (:name shape) " " (:id shape) " shape-ref " (:shape-ref shape)))
                       ;; (prn "     (root shape not found)")
                       shape))))))

           update-page
           (fn [page]
             (prn (str "Page " (:name page)))
             (h/update-shapes page (partial update-shape page)))]

       (update file :data h/update-pages update-page))))

  ([file save?]
   (let [file (-> file
                  (update :data blob/decode)
                  (update-near-components))]
     (when save?
       (let [features (db/create-array h/*conn* "text" (:features file))
             data     (blob/encode (:data file))]
         (db/update! h/*conn* :file
                     {:data data
                      ;; :revn (:revn file)
                      :features features}
                     {:id (:id file)})

         (when (contains? (:features file) "storage/pointer-map")
           (files/persist-pointers! h/*conn* (:id file))))))))

(defn fix-main-shape-name
  ([file]
   (prn (str "Updating " (:name file) " " (:id file)))
   (if-not (contains? (:features file) "components/v2")
     (prn "   This file is not v2")
     (let [libs (->> (files/get-file-libraries h/*conn* (:id file))
                     (cons file)
                     (map #(files/get-file h/*conn* (:id %) (:features file)))
                     (d/index-by :id))

           update-shape
           (fn [shape]
             (if-not (ctk/instance-head? shape)
               shape
               (let [component (ctf/get-component libs (:component-file shape) (:component-id shape) {:include-deleted? true})
                     [path _name] (cph/parse-path-name (:name shape))]
                 (if (= path (:path component))
                 (assoc shape :name (:name component))
                 shape))))


           update-page
           (fn [page]
             (prn (str "Page " (:name page)))
             (h/update-shapes page update-shape))]

       (update file :data h/update-pages update-page))))

  ([file save?]
   (let [file (-> file
                  (update :data blob/decode)
                  (fix-main-shape-name))]
     (when save?
       (let [features (db/create-array h/*conn* "text" (:features file))
             data     (blob/encode (:data file))]
         (db/update! h/*conn* :file
                     {:data data
                      ;; :revn (:revn file)
                      :features features}
                     {:id (:id file)})

         (when (contains? (:features file) "storage/pointer-map")
           (files/persist-pointers! h/*conn* (:id file))))))))

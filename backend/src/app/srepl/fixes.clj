;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.srepl.fixes
  "A collection of adhoc fixes scripts."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.rpc.commands.files :as files]
   [app.srepl.helpers :as h]))

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
  [file]
  (if-not (contains? (:features file) "components/v2")
    (ex/raise :type :invalid-file
              :code :invalid-file
              :hint "this file is not v2")
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
                                  (apply concat (mapv fci (:shapes copy) (:shapes base)))
                                  [])]
              (concat updates child-updates)))

          fix-copy
          (fn [objects updates copy]
            (let [component        (ctf/find-component libs (:component-id copy) {:included-delete? true})
                  component-file   (get libs (:component-file copy))
                  component-shapes (ctf/get-component-shapes (:data component-file) component)
                  copy-shapes      (cph/get-children-with-self objects (:id copy))

                  copy-updates (fix-copy-item true copy-shapes component-shapes (:id copy) (:main-instance-id component))]
              (concat updates copy-updates)))

          update-page (fn [page]
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

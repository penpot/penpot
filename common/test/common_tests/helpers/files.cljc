;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.helpers.files
  (:require
   [app.common.data.macros :as dm]
   [app.common.features :as ffeat]
   [app.common.files.changes :as cfc]
   [app.common.files.helpers :as cfh]
   [app.common.files.validate :as cfv]
   [app.common.geom.point :as gpt]
   [app.common.pprint :refer [pprint]]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as cttl]
   [app.common.types.typography :as ctt]
   [common-tests.helpers.ids-map :as thi]))

;; ----- Files

(defn sample-file
  [label & {:keys [page-label name] :as params}]
  (binding [ffeat/*current* #{"components/v2"}]
    (let [params (cond-> params
                   label
                   (assoc :id (thi/new-id! label))

                   page-label
                   (assoc :page-id (thi/new-id! page-label))

                   (nil? name)
                   (assoc :name "Test file"))

          file (-> (ctf/make-file (dissoc params :page-label))
                   (assoc :features #{"components/v2"}))

          page (-> file
                   :data
                   (ctpl/pages-seq)
                   (first))]

      (with-meta file
        {:current-page-id (:id page)}))))

(defn validate-file!
  ([file] (validate-file! file {}))
  ([file libraries]
   (cfv/validate-file-schema! file)
   (cfv/validate-file! file libraries)))

(defn apply-changes
  [file changes]
  (let [file' (ctf/update-file-data file #(cfc/process-changes % (:redo-changes changes) true))]
    (validate-file! file')
    file'))

(declare current-page-id)
(declare get-page)

(defn dump-file
  [file & {:keys [page-label libraries] :as params}]
  (let [params    (-> params
                      (or {:show-ids true :show-touched true})
                      (dissoc page-label libraries))
        page      (if (some? page-label)
                    (:id (get-page file page-label))
                    (current-page-id file))
        libraries (or libraries {})]

    (ctf/dump-tree file page libraries params)))

(defn pprint-file
  [file & {:keys [level length] :or {level 10 length 1000}}]
  (pprint file {:level level :length length}))

;; ----- Pages

(defn sample-page
  [label & {:keys [] :as params}]
  (ctp/make-empty-page (assoc params :id (thi/new-id! label))))

(defn add-sample-page
  [file label & {:keys [] :as params}]
  (let [page (sample-page label params)]
    (-> file
        (ctf/update-file-data #(ctpl/add-page % page))
        (vary-meta assoc :current-page-id (:id page)))))

(defn get-page
  [file label]
  (ctpl/get-page (:data file) (thi/id label)))

(defn current-page-id
  [file]
  (:current-page-id (meta file)))

(defn current-page
  [file]
  (ctpl/get-page (:data file) (current-page-id file)))

(defn switch-to-page
  [file label]
  (vary-meta file assoc :current-page-id (thi/id label)))

;; ----- Shapes

(defn sample-shape
  [label & {:keys [type] :as params}]
  (let [params (cond-> params
                 label
                 (assoc :id (thi/new-id! label))

                 (nil? type)
                 (assoc :type :rect))]

    (cts/setup-shape params)))

(defn add-sample-shape
  [file label & {:keys [parent-label] :as params}]
  (let [page      (current-page file)
        shape     (sample-shape label (dissoc params :parent-label))
        parent-id (when parent-label
                    (thi/id parent-label))
        parent    (when parent-id
                    (ctst/get-shape page parent-id))
        frame-id  (if (cfh/frame-shape? parent)
                    (:id parent)
                    (:frame-id parent))]
    (ctf/update-file-data
     file
     (fn [file-data]
       (ctpl/update-page file-data
                         (:id page)
                         #(ctst/add-shape (:id shape)
                                          shape
                                          %
                                          frame-id
                                          parent-id
                                          nil
                                          true))))))

(defn get-shape
  [file label & {:keys [page-label]}]
  (let [page (if page-label
               (get-page file page-label)
               (current-page file))]
    (ctst/get-shape page (thi/id label))))

(defn get-shape-by-id
  [file id & {:keys [page-label]}]
  (let [page (if page-label
               (get-page file page-label)
               (current-page file))]
    (ctst/get-shape page id)))

;; ----- Components

(defn make-component
  [file label root-label]
  (let [page (current-page file)
        root (get-shape file root-label)]

    (dm/assert!
     "Need that root is already a frame"
     (cfh/frame-shape? root))

    (let [[_new-root _new-shapes updated-shapes]
          (ctn/convert-shape-in-component root (:objects page) (:id file))

          updated-root (first updated-shapes)] ; Can't use new-root because it has a new id

      (thi/set-id! label (:component-id updated-root))

      (ctf/update-file-data
       file
       (fn [file-data]
         (as-> file-data $
           (reduce (fn [file-data shape]
                     (ctpl/update-page file-data
                                       (:id page)
                                       #(update % :objects assoc (:id shape) shape)))
                   $
                   updated-shapes)
           (ctkl/add-component $
                               {:id (:component-id updated-root)
                                :name (:name updated-root)
                                :main-instance-id (:id updated-root)
                                :main-instance-page (:id page)
                                :shapes updated-shapes})))))))

(defn get-component
  [file label]
  (ctkl/get-component (:data file) (thi/id label)))

(defn get-component-by-id
  [file id]
  (ctkl/get-component (:data file) id))

(defn instantiate-component
  [file component-label copy-root-label & {:keys [parent-label library] :as params}]
  (let [page      (current-page file)
        library   (or library file)
        component (get-component library component-label)
        parent-id (when parent-label
                    (thi/id parent-label))
        parent    (when parent-id
                    (ctst/get-shape page parent-id))
        frame-id  (if (cfh/frame-shape? parent)
                    (:id parent)
                    (:frame-id parent))

        [copy-root copy-shapes]
        (ctn/make-component-instance page
                                     component
                                     (:data library)
                                     (gpt/point 100 100)
                                     true
                                     {:force-id (thi/new-id! copy-root-label)
                                      :force-frame-id frame-id})

        copy-root' (cond-> copy-root
                     (some? parent)
                     (assoc :parent-id parent-id)

                     (some? frame-id)
                     (assoc :frame-id frame-id)

                     (and (some? parent) (ctn/in-any-component? (:objects page) parent))
                     (dissoc :component-root))]

    (ctf/update-file-data
     file
     (fn [file-data]
       (as-> file-data $
         (ctpl/update-page $
                           (:id page)
                           #(ctst/add-shape (:id copy-root')
                                            copy-root'
                                            %
                                            frame-id
                                            parent-id
                                            nil
                                            true))
         (reduce (fn [file-data shape]
                   (ctpl/update-page file-data
                                     (:id page)
                                     #(ctst/add-shape (:id shape)
                                                      shape
                                                      %
                                                      (:parent-id shape)
                                                      (:frame-id shape)
                                                      nil
                                                      true)))
                 $
                 (remove #(= (:id %) (:did copy-root')) copy-shapes)))))))

(defn sample-color
  [label & {:keys [] :as params}]
  (ctc/make-color (assoc params :id (thi/new-id! label))))

(defn add-sample-color
  [file label & {:keys [] :as params}]
  (let [color (sample-color label params)]
    (ctf/update-file-data file #(ctcl/add-color % color))))

(defn sample-typography
  [label & {:keys [] :as params}]
  (ctt/make-typography (assoc params :id (thi/new-id! label))))

(defn add-sample-typography
  [file label & {:keys [] :as params}]
  (let [typography (sample-typography label params)]
    (ctf/update-file-data file #(cttl/add-typography % typography))))

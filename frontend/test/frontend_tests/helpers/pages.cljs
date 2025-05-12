;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.helpers.pages
  (:require
   [app.common.data :as d]
   [app.common.files.changes :as cp]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.shapes-helpers :as cfsh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.libraries :as cll]
   [app.common.types.component :as ctk]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.uuid :as uuid]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.groups :as dwg]
   [app.main.data.workspace.layout :as layout]))

;; ---- Helpers to manage pages and objects

(def current-file-id (uuid/next))

(def initial-state
  {:current-file-id current-file-id
   :current-page-id nil
   :workspace-layout layout/default-layout
   :workspace-global layout/default-global

   :files
   {current-file-id
    {:id current-file-id
     :data {:id current-file-id
            :options {:components-v2 true}
            :components {}
            :pages []
            :pages-index {}}}}
   :features-team #{"components/v2"}})

(def ^:private idmap (atom {}))

(defn reset-idmap! []
  (reset! idmap {}))

(defn current-page
  [state]
  (let [page-id (:current-page-id state)
        file-id (:current-file-id state)]
    (get-in state [:files file-id :data :pages-index page-id])))

(defn id
  [label]
  (get @idmap label))

(defn get-shape
  [state label]
  (let [page (current-page state)]
    (get-in page [:objects (id label)])))

(defn get-children
  [state label]
  (let [page (current-page state)]
    (cfh/get-children (:objects page) (id label))))

(defn apply-changes
  [state changes]
  (let [file-id (:current-file-id state)]
    (update-in state [:files file-id :data] cp/process-changes changes)))

(defn sample-page
  ([state] (sample-page state {}))
  ([state {:keys [id name] :as props
           :or {id (uuid/next)
                name "page1"}}]
   (swap! idmap assoc :page id)
   (-> state
       (assoc :current-page-id id)
       (apply-changes [{:type :add-page
                        :id id
                        :name name}]))))

(defn sample-shape
  ([state label type] (sample-shape state type {}))
  ([state label type props]
   (let [page  (current-page state)
         frame (cfh/get-frame (:objects page))
         shape (cts/setup-shape (merge {:type type :x 0 :y 0 :width 1 :height 1} props))]
     (swap! idmap assoc label (:id shape))
     (apply-changes state
                    [{:type :add-obj
                      :id (:id shape)
                      :page-id (:id page)
                      :frame-id (:id frame)
                      :obj shape}]))))

(defn group-shapes
  ([state label ids] (group-shapes state label ids "Group"))
  ([state label ids prefix]
   (let [page  (current-page state)
         shapes (dwg/shapes-for-grouping (:objects page) ids)]
     (if (empty? shapes)
       state
       (let [[group changes]
             (dwg/prepare-create-group (pcb/empty-changes) nil (:objects page) (:id page) shapes prefix true)]

         (swap! idmap assoc label (:id group))
         (apply-changes state (:redo-changes changes)))))))

(defn frame-shapes
  ([state label ids] (frame-shapes state label ids "Board"))
  ([state label ids frame-name]
   (let [page    (current-page state)
         shapes  (dwg/shapes-for-grouping (:objects page) ids)
         changes (pcb/empty-changes nil (:id page))]
     (if (empty? shapes)
       state
       (let [[frame changes]
             (cfsh/prepare-create-artboard-from-selection changes
                                                          nil
                                                          nil
                                                          (:objects page)
                                                          (map :id shapes)
                                                          nil
                                                          frame-name
                                                          true)]

         (swap! idmap assoc label (:id frame))
         (apply-changes state (:redo-changes changes)))))))

(defn make-component
  [state instance-label component-label shape-ids]
  (let [page    (current-page state)
        objects (dsh/lookup-page-objects state (:id page))
        shapes  (dwg/shapes-for-grouping objects shape-ids)

        [group component-id changes]
        (cll/generate-add-component (pcb/empty-changes nil)
                                    shapes
                                    (:objects page)
                                    (:id page)
                                    current-file-id
                                    cfsh/prepare-create-artboard-from-selection)]

    (swap! idmap assoc instance-label (:id group)
           component-label component-id)
    (apply-changes state (:redo-changes changes))))

(defn instantiate-component
  ([state label component-id]
   (instantiate-component state label component-id current-file-id))
  ([state label component-id file-id]
   (let [page      (current-page state)
         libraries (dsh/lookup-libraries state)
         objects (:objects page)

         changes   (-> (pcb/empty-changes nil (:id page))
                       (pcb/with-objects objects))

         [new-shape changes]
         (cll/generate-instantiate-component changes
                                             objects
                                             file-id
                                             component-id
                                             (gpt/point 100 100)
                                             page
                                             libraries)]

     (swap! idmap assoc label (:id new-shape))
     (apply-changes state (:redo-changes changes)))))

(defn move-to-library
  [state label name]
  (let [library-id (uuid/next)
        file-id    (:current-file-id state)
        data       (get-in state [:files file-id :data])]
    (swap! idmap assoc label library-id)
    (-> state
        (update :files assoc library-id
                {:id library-id
                 :name name
                 :data {:id library-id
                        :options (:options data)
                        :pages (:pages data)
                        :pages-index (:pages-index data)
                        :components (:components data)}})
        (update-in [:files file-id :data] assoc
                   :components {}
                   :pages []
                   :pages-index {}))))

(defn simulate-copy-shape
  [selected objects libraries page file features version]
  (letfn [(sort-selected [data]
            (let [;; Narrow the objects map so it contains only relevant data for
                  ;; selected and its parents
                  objects  (cfh/selected-subtree objects selected)
                  selected (->> (ctst/sort-z-index objects selected)
                                (reverse)
                                (into (d/ordered-set)))]

              (assoc data :selected selected)))

          ;; Prepare the shape object.
          (prepare-object [objects parent-frame-id obj]
            (maybe-translate obj objects parent-frame-id))

          ;; Collects all the items together and split images into a
          ;; separated data structure for a more easy paste process.
          (collect-data [result {:keys [id ::images] :as item}]
            (cond-> result
              :always
              (update :objects assoc id (dissoc item ::images))

              (some? images)
              (update :images into images)))

          (maybe-translate [shape objects parent-frame-id]
            (if (= parent-frame-id uuid/zero)
              shape
              (let [frame (get objects parent-frame-id)]
                (gsh/translate-to-frame shape frame))))

          ;; When copying an instance that is nested inside another one, we need to
          ;; advance the shape refs to one or more levels of remote mains.
          (advance-copies [data]
            (let [heads     (mapcat #(ctn/get-child-heads (:objects data) %) selected)]
              (update data :objects
                      #(reduce (partial advance-copy file libraries page)
                               %
                               heads))))

          (advance-copy [file libraries page objects shape]
            (if (and (ctk/instance-head? shape) (not (ctk/main-instance? shape)))
              (let [level-delta (ctn/get-nesting-level-delta (:objects page) shape uuid/zero)]
                (if (pos? level-delta)
                  (reduce (partial advance-shape file libraries page level-delta)
                          objects
                          (cfh/get-children-with-self objects (:id shape)))
                  objects))
              objects))

          (advance-shape [file libraries page level-delta objects shape]
            (let [new-shape-ref (ctf/advance-shape-ref file page libraries shape level-delta {:include-deleted? true})]
              (cond-> objects
                (and (some? new-shape-ref) (not= new-shape-ref (:shape-ref shape)))
                (assoc-in [(:id shape) :shape-ref] new-shape-ref))))]

    (let [file-id  (:id file)
          frame-id (cfh/common-parent-frame objects selected)

          initial  {:type :copied-shapes
                    :features features
                    :version version
                    :file-id file-id
                    :selected selected
                    :objects {}
                    :images #{}
                    :in-viewport false}

          shapes   (->> (cfh/selected-with-children objects selected)
                        (keep (d/getf objects)))]

      (->> shapes
           (map (partial prepare-object objects frame-id))
           (reduce collect-data initial)
           sort-selected
           advance-copies))))

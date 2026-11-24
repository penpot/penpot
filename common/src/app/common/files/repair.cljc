;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.repair
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.changes :as cfc]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.files.tokens :as cfo]
   [app.common.files.variant :as cfv]
   [app.common.logging :as log]
   [app.common.path-names :as cpn]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.types.variant :as ctv]
   [app.common.uuid :as uuid]))

(defmulti repair-error
  (fn [code _error _file-data _libraries] code))

(defmethod repair-error :invalid-geometry
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Reset geometry to minimal
          (log/debug :hint "  -> reset geometry")
          (-> shape
              (assoc :x 0)
              (assoc :y 0)
              (assoc :width 0.01)
              (assoc :height 0.01)
              (cts/setup-rect)))]
    (log/debug :hint "repairing shape :invalid-geometry" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :parent-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  ;; Move the shape to the root frame. `:mov-objects` adds it to the
  ;; root's children list when applied, so several repairs on the same
  ;; root do not overwrite each other
  (log/debug :hint "repairing shape :parent-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
  (log/debug :hint "  -> set to " :parent-id uuid/zero)
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-file-data file-data)
      (pcb/change-parent uuid/zero [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :child-not-in-parent
  [_ {:keys [shape page-id] :as error} file-data _]
  ;; Add the shape to its parent's children list. `:mov-objects` adds it
  ;; when applied, so several repairs on the same parent do not
  ;; overwrite each other
  (log/debug :hint "repairing shape :child-not-in-parent" :id (:id shape) :name (:name shape) :page-id page-id)
  (log/debug :hint "  -> add children to" :parent-id (:parent-id shape))
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-file-data file-data)
      (pcb/change-parent (:parent-id shape) [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :duplicated-children
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Remove duplicated
          (log/debug :hint "  -> remove duplicated children")
          (update shape :shapes distinct))]

    (log/debug :hint "repairing shape :duplicated-children" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :child-not-found
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [parent-shape]
          (log/debug :hint "  -> remove child" :child-id (:child-id args))
          (update parent-shape :shapes (fn [shapes]
                                         (d/removev #(= (:child-id args) %) shapes))))]
    (log/debug :hint "repairing shape :child-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-parent
  [_ {:keys [shape page-id args] :as error} file-data _]
  (log/debug :hint "repairing shape :invalid-parent" :id (:id shape) :name (:name shape) :page-id page-id)
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-file-data file-data)
      (pcb/change-parent (:parent-id args) [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :frame-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cfh/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/debug :hint "repairing shape :frame-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cfh/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/debug :hint "repairing shape :invalid-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Set the :shape as main instance root
          (log/debug :hint "  -> set :main-instance")
          (assoc shape :main-instance true))]

    (log/debug :hint "repairing shape :component-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main-external
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Set :component-file to local file
          (log/debug :hint "  -> set :component-file to local file")
          (assoc shape :component-file (:id file-data)))]

    ;; There is no solution that may recover it with confidence
    ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
    ;; shape)]

    (log/debug :hint "repairing shape :component-main-external" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))

        repair-shape
        (fn [shape]
          ;; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]
    ;; There is no solution that may recover it with confidence
    ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
    ;; shape)]

    (log/debug :hint "repairing shape :component-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes shape-ids repair-shape))))

(defmethod repair-error :invalid-main-instance-id
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [component (ctkl/get-component file-data (:component-id shape))

        repair-component
        (fn [component]
          ;; Assign main instance in the component to current shape
          (log/debug :hint "  -> assign main-instance-id" :component-id (:id component))
          (assoc component :main-instance-id (:id shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/debug :hint "repairing shape :invalid-main-instance-id" :id (:id shape) :name (:name shape) :page-id page-id)
    (if (and (some? component) (not (:deleted component)))
      (-> (pcb/empty-changes nil page-id)
          (pcb/with-library-data file-data)
          (pcb/update-component (:component-id shape) repair-component))

      (-> (pcb/empty-changes nil page-id)
          (pcb/with-file-data file-data)
          (pcb/update-shapes [(:id shape)] detach-shape)))))

(defmethod repair-error :invalid-main-instance-page
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-component
        (fn [component]
          ;; Assign main instance in the component to current shape
          (log/debug :hint "  -> assign main-instance-page" :component-id (:id component))
          (assoc component :main-instance-page page-id))]
    (log/debug :hint "repairing shape :invalid-main-instance-page" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-library-data file-data)
        (pcb/update-component (:component-id shape) repair-component))))

(defmethod repair-error :invalid-main-instance-name
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Change the name to match the component
          (let [component-name (:component-name args)]
            (log/debug :hint (str "  -> rename shape " (:id shape) " to " component-name))
            (assoc shape :name component-name)))]

    (log/debug :hint "repairing shape :invalid-main-instance-name" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-main-instance
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; There is no solution that may recover it with confidence
          (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          shape)]

    (log/debug :hint "repairing shape :invalid-main-instance" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Unset the :shape as main instance root
          (log/debug :hint "  -> unset :main-instance")
          (dissoc shape :main-instance))]

    (log/debug :hint "repairing shape :component-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a top copy root.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/debug :hint "repairing shape :should-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-not-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a nested copy root.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/debug :hint "repairing shape :should-not-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defn- find-lost-main
  "Return the main of the local copy root `root-shape` when its component's
  `:main-instance-id` points to a missing shape and the main is still on
  the component's page; nil otherwise."
  [file-data root-shape]
  (when (= (:component-file root-shape) (:id file-data))
    (let [component (ctkl/get-component file-data (:component-id root-shape))]
      (when (and (some? component)
                 (nil? (ctf/get-component-root file-data component)))
        (d/seek #(and (ctk/main-instance? %)
                      (= (:component-id %) (:id component))
                      (= (:component-file %) (:id file-data)))
                (ctn/shapes-seq (ctf/get-component-page file-data component)))))))

(defmethod repair-error :ref-shape-not-found
  [_ {:keys [shape page-id] :as error} file-data libraries]
  (let [;; An earlier repair of the same pass may have removed the shape, or
        ;; fixed the reference (e.g. pointed the component back to its main).
        current        (let [page (ctpl/get-page file-data page-id)]
                         (get (:objects page) (:id shape)))

        ref-shape      (when (some? current)
                         (ctf/find-ref-shape {:id (:id file-data) :data file-data}
                                             (ctpl/get-page file-data page-id) libraries current
                                             :include-deleted? true))

        lost-main      (when (and (some? current) (not (ctk/main-instance? current)))
                         (let [page (ctpl/get-page file-data page-id)]
                           (find-lost-main file-data (ctn/get-component-shape (:objects page) shape))))

        matching-shape (when (and (some? current) (not (ctk/main-instance? current)))
                         (let [page              (ctpl/get-page file-data page-id)
                               root-shape        (ctn/get-component-shape (:objects page) shape)
                               component-file    (if (= (:component-file root-shape) (:id file-data))
                                                   file-data
                                                   (-> (get libraries (:component-file root-shape)) :data))
                               component         (when component-file
                                                   (ctkl/get-component component-file (:component-id root-shape) true))
                               component-shapes  (ctf/get-component-shapes file-data component)]

                           ;; Check if the shape points to the remote main. If so, reassign to the near main.
                           (if-let [near-shape-1 (d/seek #(= (:shape-ref %) (:shape-ref shape)) component-shapes)]
                             near-shape-1
                             ;; Check if it points to any random shape in the page. If so, try to find a matchng
                             ;; shape in the near main component.
                             (when-let [random-shape (some->> (:shape-ref shape) (ctn/get-shape page))]
                               (if-let [near-shape-2 (d/seek #(= (:id %) (:shape-ref random-shape)) component-shapes)]
                                 near-shape-2
                                 ;; If not, check if it's a fostered copy and find a direct main.
                                 (let [head-shape        (ctn/get-head-shape (:objects page) shape)
                                       component-file    (if (= (:component-file head-shape) (:id file-data))
                                                           file-data
                                                           (-> (get libraries (:component-file head-shape)) :data))
                                       component         (when component-file
                                                           (ctkl/get-component component-file (:component-id head-shape) true))
                                       component-shapes  (ctf/get-component-shapes file-data component)]
                                   (if-let [near-shape-3 (d/seek #(= (:id %) (:shape-ref random-shape)) component-shapes)]
                                     near-shape-3
                                     nil)))))))
        reassign-shape
        (fn [shape]
          (log/debug :hint "  -> reassign shape-ref to" :shape-ref (:id matching-shape))
          (assoc shape :shape-ref (:id matching-shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    ;; If the copy's component has lost track of its main, point it back to the main. Else, if
    ;; the shape still refers to the remote component, try to find the corresponding near one
    ;; and link to it. If not, detach the shape.
    (log/debug :hint "repairing shape :ref-shape-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (cond
      (or (nil? current) (some? ref-shape) (ctk/main-instance? current))
      (do
        (log/debug :hint "  -> shape removed, ref shape found or shape is a main, nothing to repair")
        (pcb/empty-changes nil page-id))

      (some? lost-main)
      (do
        (log/debug :hint "  -> assign main-instance-id" :main-instance-id (:id lost-main))
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-library-data file-data)
            (pcb/update-component (:component-id lost-main) #(assoc % :main-instance-id (:id lost-main)))))

      (some? matching-shape)
      (-> (pcb/empty-changes nil page-id)
          (pcb/with-file-data file-data)
          (pcb/update-shapes [(:id shape)] reassign-shape))

      :else
      (let [page      (ctpl/get-page file-data page-id)
            shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))]
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-file-data file-data)
            (pcb/update-shapes shape-ids detach-shape))))))

(defmethod repair-error :ref-shape-is-not-head
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert shape in a normal copy, removing nested copy status
          (log/debug :hint "  -> unhead shape")
          (ctk/unhead-shape shape))]

    (log/debug :hint "repairing shape :shape-ref-is-not-head" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-id-mismatch
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set the component-id and component-file to the ones of the near main
          (log/debug :hint (str "  -> set component-id to " (:component-id args)))
          (log/debug :hint (str "  -> set component-file to " (:component-file args)))
          (cond-> shape
            (some? (:component-id args))
            (assoc :component-id (:component-id args))

            (nil? (:component-id args))
            (dissoc :component-id)

            (some? (:component-file args))
            (assoc :component-file (:component-file args))

            (nil? (:component-file args))
            (dissoc :component-file)))]

    (log/debug :hint "repairing shape :component-id-mismatch" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :ref-shape-is-head
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert shape in a nested head, adding component info
          (log/debug :hint "  -> reroot shape")
          (ctk/rehead-shape shape (:component-file args) (:component-id args)))]

    (log/debug :hint "repairing shape :shape-ref-is-head" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :shape-ref-cycle
  [_ {:keys [shape args] :as error} file-data _]
  (let [repair-component
        (fn [component]
          (let [objects   (:objects component)
                ;; we only have encounter this on deleted components,
                ;; so the relevant objects are inside the component
                to-detach (->> (:cycles-ids args)
                               (map #(get objects %))
                               (map #(ctn/get-head-shape objects %))
                               (map :id)
                               distinct
                               (mapcat #(ctn/get-children-in-instance objects %))
                               (map :id)
                               set)]

            (update component :objects
                    (fn [objects]
                      (reduce-kv (fn [acc k v]
                                   (if (contains? to-detach k)
                                     (assoc acc k (ctk/detach-shape v))
                                     (assoc acc k v)))
                                 {}
                                 objects)))))]
    (log/debug :hint "repairing component :shape-ref-cycle" :id (:id shape) :name (:name shape))
    (-> (pcb/empty-changes nil nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id shape) repair-component))))

(defmethod repair-error :shape-ref-in-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Remove shape-ref
          (log/debug :hint "  -> unset :shape-ref")
          (dissoc shape :shape-ref))]

    (log/debug :hint "repairing shape :shape-ref-in-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :root-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a nested main head.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/debug :hint "repairing shape :root-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [objects      (-> (ctpl/get-page file-data page-id) :objects)
        current      (get objects (:id shape))
        inside-main? (some ctk/main-instance? (cfh/get-parents objects (:id shape)))

        repair-shape
        (fn [shape]
          ;; Convert the shape in a top main head. It moves to the page
          ;; root only when an ancestor is a main; otherwise it stays in place.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/debug :hint "repairing shape :nested-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (if (nil? current)
      (do
        (log/debug :hint "  -> shape removed, nothing to repair")
        (pcb/empty-changes nil page-id))
      (cond-> (-> (pcb/empty-changes nil page-id)
                  (pcb/with-file-data file-data)
                  (pcb/update-shapes [(:id shape)] repair-shape))
        inside-main?
        (pcb/change-parent uuid/zero [current] nil {:allow-altering-copies true})))))

(defmethod repair-error :root-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a nested copy head.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/debug :hint "repairing shape :root-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a top copy root.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/debug :hint "repairing shape :nested-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/debug :hint "repairing shape :not-head-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/debug :hint "repairing shape :not-head-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-component-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; There is no solution that may recover it with confidence
          (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          shape)]

    (log/debug :hint "repairing shape :not-component-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :instance-head-not-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Convert the shape in a frame.
          (log/debug :hint "  -> set :type :frame")
          (assoc shape :type :frame
                 :fills []
                 :hide-in-viewer true
                 :r1 0
                 :r2 0
                 :r3 0
                 :r4 0))]

    (log/debug :hint "repairing shape :instance-head-not-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-nil-objects-not-allowed
  [_ {component :shape} file-data _]   ; in this error the :shape argument is the component
  (let [repair-component
        (fn [component]
          ;; Remove the objects key, or set it to {} if the component is deleted
          (if (:deleted component)
            (do
              (log/debug :hint "  -> set :objects {}")
              (assoc component :objects {}))
            (do
              (log/debug :hint "  -> remove :objects")
              (dissoc component :objects))))]

    (log/debug :hint "repairing component :component-nil-objects-not-allowed" :id (:id component) :name (:name component))
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id component) repair-component))))

(defmethod repair-error :non-deleted-component-cannot-have-objects
  [_ {component :shape} file-data _]   ; in this error the :shape argument is the component
  (let [repair-component
        (fn [component]
          ; Remove the :objects field
          (if-not (:deleted component)
            (do
              (log/debug :hint "  -> remove :objects")
              (dissoc component :objects))
            component))]

    (log/debug :hint "repairing component :non-deleted-component-cannot-have-objects" :id (:id component) :name (:name component))
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id component) repair-component))))

(defn- find-claiming-main
  "Return `[page main]` for a shape, on any page of the file, that is a main
  of `component`; nil if there is none."
  [file-data component]
  (some (fn [page]
          (when-let [main (d/seek #(and (ctk/main-instance? %)
                                        (= (:component-id %) (:id component))
                                        (= (:component-file %) (:id file-data)))
                                  (ctn/shapes-seq page))]
            [page main]))
        (ctpl/pages-seq file-data)))

(defmethod repair-error :component-main-not-found
  [_ {component :shape} file-data _]   ; in this error the :shape argument is the component
  ;; An earlier repair of the same pass may have fixed the component, so read
  ;; it again from the current data.
  (let [component  (ctkl/get-component file-data (:id component))
        fixed?     (or (nil? component)
                       (some? (ctf/get-component-root file-data component)))
        [page main] (when-not fixed?
                      (find-claiming-main file-data component))]

    (log/debug :hint "repairing component :component-main-not-found" :id (:id component) :name (:name component))
    (cond
      fixed?
      (pcb/empty-changes nil)

      (some? main)
      (do
        (log/debug :hint "  -> relink main" :main-instance-id (:id main) :main-instance-page (:id page))
        (-> (pcb/empty-changes nil)
            (pcb/with-library-data file-data)
            (pcb/update-component (:id component)
                                  #(assoc % :main-instance-id (:id main) :main-instance-page (:id page)))))

      :else
      (do
        (log/debug :hint "  -> remove component without main")
        (-> (pcb/empty-changes nil)
            (update :redo-changes conj {:type :del-component
                                        :id (:id component)
                                        :skip-undelete? true}))))))

(defmethod repair-error :invalid-text-touched
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Add content group
          (log/debug :hint "  -> add :content-group to :touched-groups")
          (update shape :touched ctk/set-touched-group :content-group))]

    (log/debug :hint "repairing shape :invalid-text-touched" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :misplaced-slot
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Remove the swap slot
          (log/debug :hint "  -> remove swap-slot")
          (ctk/remove-swap-slot shape))]

    (log/debug :hint "repairing shape :misplaced-slot" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :duplicate-slot
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        childs    (map #(get (:objects page) %) (:shapes shape))
        child-with-duplicate (let [result (reduce (fn [[seen duplicates] item]
                                                    (let [swap-slot (ctk/get-swap-slot item)]
                                                      (if (contains? seen swap-slot)
                                                        [seen (conj duplicates item)]
                                                        [(conj seen swap-slot) duplicates])))
                                                  [#{} []]
                                                  childs)]
                               (second result))
        repair-shape
        (fn [shape]
          ;; Remove the swap slot
          (log/debug :hint "  -> remove swap-slot" :child-id (:id shape))
          (ctk/remove-swap-slot shape))]

    (log/debug :hint "repairing shape :duplicated-slot" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes (map :id child-with-duplicate) repair-shape))))

(defmethod repair-error :component-duplicate-slot
  [_ {:keys [shape] :as error} file-data _]
  (let [main-shape            (get-in shape [:objects (:main-instance-id shape)])
        childs                (map #(get (:objects shape) %) (:shapes main-shape))
        childs-with-duplicate (let [result (reduce (fn [[seen duplicates] item]
                                                     (let [swap-slot (ctk/get-swap-slot item)]
                                                       (if (contains? seen swap-slot)
                                                         [seen (conj duplicates item)]
                                                         [(conj seen swap-slot) duplicates])))
                                                   [#{} []]
                                                   childs)]
                                (second result))
        duplicated-ids        (set (mapv :id childs-with-duplicate))
        repair-component
        (fn [component]
          (let [objects (reduce-kv (fn [acc k v]
                                     (if (contains? duplicated-ids k)
                                       (assoc acc k (ctk/remove-swap-slot v))
                                       (assoc acc k v)))
                                   {}
                                   (:objects component))]
            (assoc component :objects objects)))]

    (log/debug :hint "repairing component :component-duplicated-slot" :id (:id shape) :name (:name shape))
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id shape) repair-component))))

(defmethod repair-error :missing-slot
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Set the desired swap slot
          (let [slot (:swap-slot args)]
            (if (some? slot)
              (do (log/debug :hint (str "  -> set swap-slot to " slot))
                  (ctk/set-swap-slot shape slot))
              shape)))]

    (log/debug :hint "repairing shape :missing-slot" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :main-instance-not-a-variant
  [_ {:keys [shape page-id args]} file-data _]
  (let [variant-id (:variant-id args)
        component  (get-in file-data [:components (:component-id args)])
        repair-shape
        (fn [shape]
          ;; Set the desired variant-id
          (log/debug :hint (str "  -> set variant-id to " variant-id))
          (assoc shape :variant-id variant-id))]

    (log/debug :hint "repairing shape :main-instance-not-a-variant" :id (:id shape) :name (:name shape) :page-id page-id)
    (if (:deleted component)
      ;; The main of a deleted component lives in the component's objects
      (-> (pcb/empty-changes nil)
          (pcb/with-library-data file-data)
          (pcb/update-component (:id component)
                                #(update-in % [:objects (:id shape)] repair-shape)))
      (-> (pcb/empty-changes nil page-id)
          (pcb/with-file-data file-data)
          (pcb/update-shapes [(:id shape)] repair-shape)))))

(defmethod repair-error :variant-container-child-not-main
  [_ {:keys [shape page-id args]} file-data _]
  (let [objects   (:objects (ctpl/get-page file-data page-id))
        board-id  (:variant-id args)
        parent-id (dm/get-in objects [board-id :parent-id])
        parent-shapes (:shapes (get objects parent-id))
        index         (or (some-> (cfh/get-position-on-parent objects board-id) inc)
                          (count parent-shapes))]
    (log/debug :hint "repairing shape :variant-container-child-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (log/debug :hint "  -> move shape out of the variant container" :parent-id parent-id :index index)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/change-parent parent-id [shape] index {:allow-altering-copies true}))))

(defmethod repair-error :main-instance-invalid-variant-id
  [_ {:keys [shape page-id args]} file-data _]
  (let [repair-shape
        (fn [shape]
          (let [variant-id (:variant-id args)]
            ;; Set the desired variant-id
            (log/debug :hint (str "  -> set variant-id to " variant-id))
            (assoc shape
                   :variant-id variant-id)))]

    (log/debug :hint "repairing shape :main-instance-invalid-variant-id" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defn- repair-variant-member-changes
  [file-data member]
  (let [component      (:component member)
        props          (:props member)
        variant-name   (ctv/properties-to-name props)
        changed?       (cfv/variant-props-changed? component props)
        main-id        (:main-instance-id component)
        page-id        (:main-instance-page component)
        deleted?       (:deleted component)
        shape          (cfv/get-variant-main-shape file-data component)
        label-differs? (cfv/variant-main-label-changed? file-data component props)
        comp-changes
        (when (or changed?
                  (and deleted? label-differs?))
          (log/debug :hint "  -> write variant properties"
                     :component-id (:id component)
                     :changed changed?
                     :label-differs label-differs?)
          (-> (pcb/empty-changes nil)
              (pcb/with-library-data file-data)
              (pcb/update-component
               (:id component)
               (fn [component]
                 (cond-> component
                   changed?
                   (assoc :variant-properties props)

                   (and (not deleted?) changed?)
                   (dissoc :objects)

                   (and deleted? (nil? (:objects component)))
                   (assoc :objects {})

                   (and deleted?
                        label-differs?
                        (map? (get-in component [:objects main-id])))
                   (assoc-in [:objects main-id :variant-name] variant-name))))))
        shape-changes
        (when (and (not deleted?) label-differs? (map? shape))
          (log/debug :hint "  -> set variant-name" :variant-name variant-name :page-id page-id)
          (-> (pcb/empty-changes nil page-id)
              (pcb/with-file-data file-data)
              (pcb/update-shapes [main-id]
                                 (fn [shape]
                                   (assoc shape :variant-name variant-name)))))]
    (cond-> (or comp-changes (pcb/empty-changes nil))
      shape-changes (pcb/concat-changes shape-changes))))

(defn- repair-variant-property-set
  [file-data shape page-id owner-id]
  (let [objects  (if (some? owner-id)
                   (get-in file-data [:components owner-id :objects])
                   (get-in file-data [:pages-index page-id :objects]))
        ;; An earlier repair of this pass may have changed the board
        board    (get objects (:id shape) shape)
        members  (cfv/get-variant-members-props file-data objects board ctv/align-properties-set)]
    (reduce (fn [changes member]
              (pcb/concat-changes changes
                                  (repair-variant-member-changes file-data member)))
            (pcb/empty-changes nil)
            members)))

(defn- repair-solo-variant-properties
  [file-data component]
  (repair-variant-member-changes
   file-data
   {:component component
    :props     (ctv/sanitize-properties (or (:variant-properties component) []))}))

(defmethod repair-error :invalid-variant-properties
  [_ {:keys [shape page-id args]} file-data _]
  (log/debug :hint "repairing :invalid-variant-properties" :id (:id shape) :page-id page-id)
  (if (ctk/is-variant-container? shape)
    (repair-variant-property-set file-data shape page-id (:component-id args))
    (repair-solo-variant-properties file-data
                                    (get-in file-data [:components (:id shape)]))))

(defmethod repair-error :variant-main-outside-board
  [_ {:keys [args]} file-data _]
  ;; Detach the variant, as the editor does when a variant leaves its board.
  ;; The error comes from the main shape or from its component.
  (let [component     (get-in file-data [:components (:component-id args)])
        page-id       (:main-instance-page component)
        main          (cfv/get-variant-main-shape file-data component)
        props         (ctv/sanitize-properties (or (:variant-properties component) []))
        new-name      (ctv/variant-name-to-name
                       (assoc main :variant-name (ctv/properties-to-name props)))]
    (log/debug :hint "repairing component :variant-main-outside-board" :id (:id component) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/with-library-data file-data)
        (cfv/generate-detach-variant main new-name))))

(defmethod repair-error :variant-not-main
  [_ {:keys [shape page-id]} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        current   (get (:objects page) (:id shape))
        component (ctkl/get-component file-data (:component-id shape))
        ;; A stale :main-instance-page is fixed by the
        ;; :invalid-main-instance-page repair once the shape is a main again.
        claimed?  (and (some? component)
                       (= (:component-file shape) (:id file-data))
                       (= (:main-instance-id component) (:id shape)))]
    (log/debug :hint "repairing shape :variant-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (cond
      (nil? current)
      (do
        (log/debug :hint "  -> shape removed, nothing to repair")
        (pcb/empty-changes nil page-id))

      claimed?
      (do
        (log/debug :hint "  -> set :main-instance")
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-file-data file-data)
            (pcb/update-shapes [(:id shape)] #(assoc % :main-instance true))))

      :else
      (let [shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))]
        (log/debug :hint "  -> delete shapes" :shape-ids shape-ids)
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-file-data file-data)
            (pcb/remove-objects shape-ids))))))

(defmethod repair-error :parent-not-variant
  [_ {:keys [shape page-id]} file-data _]
  (let [parent-id (:parent-id shape)
        repair-fn
        (fn [parent]
          (log/debug :hint "  -> set :is-variant-container true")
          (assoc parent :is-variant-container true))]
    (log/debug :hint "repairing shape :parent-not-variant" :id (:id shape) :name (:name shape) :parent-id parent-id :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [parent-id] repair-fn))))

(defmethod repair-error :variant-container-bad-name
  [_ {:keys [shape page-id args]} file-data _]
  (let [clean-name (:clean-name args)

        repair-shape
        (fn [shape]
          (log/debug :hint "  -> set :name of shape" :id (:id shape) :name clean-name)
          (assoc shape :name clean-name))

        repair-component
        (fn [component]
          (let [[path name] (cpn/split-group-name clean-name)]
            (log/debug :hint "  -> set :path and :name of component" :id (:id component) :path path :name name)
            (assoc component :path path :name name)))

        all-shape-ids
        (cons (:id shape) (:shapes shape))

        objects
        (dm/get-in file-data [:pages-index page-id :objects])

        all-main-instances
        (map (d/getf objects) (:shapes shape))

        all-component-ids
        (map :component-id all-main-instances)]

    (log/debug :hint "repairing shape :variant-container-bad-name" :id (:id shape) :name (:name shape) :page-id page-id)
    (as-> (pcb/empty-changes nil page-id) $
      (pcb/with-file-data $ file-data)
      (pcb/with-library-data $ file-data)
      (pcb/update-shapes $ all-shape-ids repair-shape)
      (reduce #(pcb/update-component %1 %2 repair-component)
              $
              all-component-ids))))

(defmethod repair-error :variant-main-bad-name
  [_ {:keys [shape page-id args]} file-data _]
  (let [repair-fn
        (fn [shape]
          (log/debug :hint "  -> set :name" :name (:variant-name args))
          (assoc shape :name (:variant-name args)))]
    (log/debug :hint "repairing shape :variant-main-bad-name" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-fn))))

(defn- get-rewritten-variant-props
  "Props the property repair would store for this main. On its board,
   the props of its member in the board's set, or nil when it is not a
   qualifying member, so the caller keeps the stored vector. Elsewhere,
   its own cleaned props. Reads the main as earlier repairs of this pass
   left it."
  [file-data shape component page-id]
  (let [objects (get-in file-data [:pages-index page-id :objects])
        shape   (get objects (:id shape) shape)
        parent  (get objects (:parent-id shape))]
    (if (and (ctk/is-variant-container? parent)
             (= (:variant-id shape) (:id parent)))
      (some (fn [member]
              (when (= (:id component) (:component-id member))
                (:props member)))
            (cfv/get-variant-members-props file-data objects parent ctv/align-properties-set))
      (ctv/sanitize-properties (or (:variant-properties component) [])))))

(defmethod repair-error :variant-main-bad-variant-name
  [_ {:keys [shape page-id]} file-data _]
  (let [component    (get-in file-data [:components (:component-id shape)])
        props        (get-rewritten-variant-props file-data shape component page-id)
        variant-name (ctv/properties-to-name (or props (:variant-properties component)))
        repair-fn
        (fn [shape]
          (log/debug :hint "  -> set :variant-name" :variant-name variant-name)
          (assoc shape :variant-name variant-name))]
    (log/dbg :hint "repairing shape :variant-main-bad-variant-name" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-fn))))

(defmethod repair-error :variant-component-bad-name
  [_ {:keys [shape page-id args]} file-data _]
  (let [[path name] (cpn/split-group-name (:variant-container-name args))
        repair-fn
        (fn [component]
          (log/debug :hint "  -> set :path and :name" :path path :name name)
          (assoc component :path path :name name))]
    (log/dbg :hint "repairing shape :variant-component-bad-name" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-library-data file-data)
        (pcb/update-component (:component-id shape) repair-fn))))

(defmethod repair-error :variant-component-bad-id
  [_ {:keys [shape page-id args]} file-data _]
  (let [repair-shape
        (fn [shape]
          (let [variant-id (:variant-id args)]
            ;; Set the desired variant-id
            (log/debug :hint (str "  -> set variant-id in shape to " variant-id))
            (assoc shape :variant-id variant-id)))

        repair-component
        (fn [component]
          (let [variant-id (:variant-id args)]
            ;; Set the desired variant-id
            (log/debug :hint (str "  -> set variant-id in component to " variant-id))
            (assoc component :variant-id variant-id)))]

    (log/debug :hint "repairing shape :variant-component-bad-id" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/with-library-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape)
        (pcb/update-component (:component-id shape) repair-component))))

(defmethod repair-error :missing-tokens-status
  [_ _ file-data _]
  (let [tokens-lib (cfo/get-tokens-lib file-data)
        tokens-status (cfo/make-tokens-status-from-lib tokens-lib)]
    (log/debug :hint "repairing shape :tokens-status-missing")
    (log/debug :hint "  -> add a tokens-status generated from the library")
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/set-tokens-status tokens-status))))

(defmethod repair-error :default
  [_ error file _]
  (log/error :hint "Unknown error code, don't know how to repair" :code (:code error))
  file)

(defn- repair-file*
  "Repair `errors` in `file`. Return `{:data data' :changes redo-changes}`.

  Each repair is built on the data left by the previous ones, so two
  repairs that write the same component or shape attribute do not undo
  each other. The `:shape` of each error is still the one taken by the
  validation."
  [{:keys [data id]} libraries errors]
  (log/debug :hint "repairing file" :id (str id) :errors (count errors))
  (reduce (fn [{:keys [data] :as result} error]
            (let [{:keys [redo-changes]} (repair-error (:code error) error data libraries)]
              (cond-> result
                (seq redo-changes)
                (-> (assoc :data (cfc/process-changes data redo-changes))
                    (update :changes into redo-changes)))))
          {:data data :changes []}
          errors))

(defn repair-file
  "Return the redo changes that repair `errors` in `file`. Applied in
  order to the file data, they give the repaired data."
  [file libraries errors]
  (:changes (repair-file* file libraries errors)))

(defn- error-fingerprint
  [errors]
  (frequencies (map #(dissoc % :shape) errors)))

(defn repair-file-until-valid
  "Repeatedly validate and repair `file` until `errors-fn` returns nil,
  a repair pass leaves `:data` unchanged, or the error fingerprint
  (errors without `:shape`) is unchanged from the previous pass.

  `errors-fn` is `(fn [file applied-changes] errors-or-nil)`. Never
  changes `:revn`.

  Returns `{:file file' :changes [...] :iterations n :errors remaining}`."
  [file libraries errors-fn & {:keys [max-iterations initial-errors]
                               :or {max-iterations 10}}]
  (loop [file        file
         applied     []
         iteration   0
         prev-fp     nil
         seed-errors initial-errors]
    (if (>= iteration max-iterations)
      {:file       file
       :changes    applied
       :iterations iteration
       :errors     (not-empty (errors-fn file applied))}
      (let [errors (if (and (zero? iteration) (some? seed-errors))
                     seed-errors
                     (errors-fn file applied))]
        (if (empty? errors)
          {:file       file
           :changes    applied
           :iterations iteration
           :errors     nil}
          (let [fp (error-fingerprint errors)]
            (cond
              (= fp prev-fp)
              {:file       file
               :changes    applied
               :iterations (inc iteration)
               :errors     (not-empty errors)}

              :else
              (let [{data' :data batch :changes} (repair-file* file libraries errors)]
                (if (= data' (:data file))
                  {:file       file
                   :changes    applied
                   :iterations (inc iteration)
                   :errors     (not-empty errors)}
                  (recur (assoc file :data data')
                         (into applied batch)
                         (inc iteration)
                         fp
                         nil))))))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.repair
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.pages.changes-builder :as pcb]
   [app.common.pages.helpers :as cph]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file :as ctf]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]))

(log/set-level! :debug)

(defmulti repair-error
  (fn [code _error _file-data _libraries] code))

(defmethod repair-error :invalid-geometry
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Reset geometry to minimal
          (log/debug :hint "  -> Reset geometry")
          (-> shape
              (assoc :x 0)
              (assoc :y 0)
              (assoc :width 0.01)
              (assoc :height 0.01)
              (cts/setup-rect)))]
    (log/info :hint "Repairing shape :invalid-geometry" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :parent-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set parent to root frame.
          (log/debug :hint "  -> Set to " :parent-id uuid/zero)
          (assoc shape :parent-id uuid/zero))]

    (log/info :hint "Repairing shape :parent-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :child-not-in-parent
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [parent-shape]
          ; Add shape to parent's children list
          (log/debug :hint "  -> Add children to" :parent-id (:id parent-shape))
          (update parent-shape :shapes conj (:id shape)))]

    (log/info :hint "Repairing shape :child-not-in-parent" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:parent-id shape)] repair-shape))))

(defmethod repair-error :child-not-found
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [parent-shape]
          ; Remove child shape from children list
          (log/debug :hint "  -> Remove child " :child-id (:child-id args))
          (update parent-shape :shapes d/removev #(= % (:child-id args))))]

    (log/info :hint "Repairing shape :child-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :frame-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cph/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> Set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/info :hint "Repairing shape :frame-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cph/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> Set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/info :hint "Repairing shape :invalid-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set the :shape as main instance root
          (log/debug :hint "  -> Set :main-instance")
          (assoc shape :main-instance true))]

    (log/info :hint "Repairing shape :component-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main-external
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set :component-file to local file
          (log/debug :hint "  -> Set :component-file to local file")
          (assoc shape :component-file (:id file-data)))]
          ; There is no solution that may recover it with confidence
          ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          ;; shape)]

    (log/info :hint "Repairing shape :component-main-external" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        shape-ids (cph/get-children-ids-with-self (:objects page) (:id shape))

        repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> Detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]
          ; There is no solution that may recover it with confidence
          ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          ;; shape)]

    (log/info :hint "Repairing shape :component-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes shape-ids repair-shape))))

(defmethod repair-error :invalid-main-instance-id
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [component (ctkl/get-component file-data (:component-id shape))

        repair-component
        (fn [component]
          ; Assign main instance in the component to current shape
          (log/debug :hint "  -> Assign main-instance-id" :component-id (:id component))
          (assoc component :main-instance-id (:id shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> Detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/info :hint "Repairing shape :invalid-main-instance-id" :id (:id shape) :name (:name shape) :page-id page-id)
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
          ; Assign main instance in the component to current shape
          (log/debug :hint "  -> Assign main-instance-page" :component-id (:id component))
          (assoc component :main-instance-page page-id))]
    (log/info :hint "Repairing shape :invalid-main-instance-page" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-library-data file-data)
        (pcb/update-component (:component-id shape) repair-component))))

(defmethod repair-error :invalid-main-instance
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; There is no solution that may recover it with confidence
          (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          shape)]

    (log/info :hint "Repairing shape :invalid-main-instance" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Unset the :shape as main instance root
          (log/debug :hint "  -> Unset :main-instance")
          (dissoc shape :main-instance))]

    (log/info :hint "Repairing shape :component-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> Set :component-root")
          (assoc shape :component-root true))]

    (log/info :hint "Repairing shape :should-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-not-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy root.
          (log/debug :hint "  -> Unset :component-root")
          (dissoc shape :component-root))]

    (log/info :hint "Repairing shape :should-not-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :ref-shape-not-found
  [_ {:keys [shape page-id] :as error} file-data libraries]
  (let [matching-shape (let [page           (ctpl/get-page file-data page-id)
                             root-shape     (ctn/get-component-shape (:objects page) shape)
                             component-file (if (= (:component-file root-shape) (:id file-data))
                                              file-data
                                              (-> (get libraries (:component-file root-shape)) :data))
                             component      (when component-file
                                              (ctkl/get-component (:data component-file) (:component-id root-shape) true))
                             shapes         (ctf/get-component-shapes file-data component)]
                         (d/seek #(= (:shape-ref %) (:shape-ref shape)) shapes))

        reassign-shape
        (fn [shape]
          (log/debug :hint "  -> Reassign shape-ref to" :shape-ref (:id matching-shape))
          (assoc shape :shape-ref (:id matching-shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> Detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    ; If the shape still refers to the remote component, try to find the corresponding near one
    ; and link to it. If not, detach the shape.
    (log/info :hint "Repairing shape :ref-shape-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (if (some? matching-shape)
      (-> (pcb/empty-changes nil page-id)
          (pcb/with-file-data file-data)
          (pcb/update-shapes [(:id shape)] reassign-shape))
      (let [page      (ctpl/get-page file-data page-id)
            shape-ids (cph/get-children-ids-with-self (:objects page) (:id shape))]
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-file-data file-data)
            (pcb/update-shapes shape-ids detach-shape))))))

(defmethod repair-error :shape-ref-in-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Remove shape-ref
          (log/debug :hint "  -> Unset :shape-ref")
          (dissoc shape :shape-ref))]

    (log/info :hint "Repairing shape :shape-ref-in-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :root-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested main head.
          (log/debug :hint "  -> Unset :component-root")
          (dissoc shape :component-root))]

    (log/info :hint "Repairing shape :root-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top main head.
          (log/debug :hint "  -> Set :component-root")
          (assoc shape :component-root true))]

    (log/info :hint "Repairing shape :nested-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :root-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy head.
          (log/debug :hint "  -> Unset :component-root")
          (dissoc shape :component-root))]

    (log/info :hint "Repairing shape :root-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> Set :component-root")
          (assoc shape :component-root true))]

    (log/info :hint "Repairing shape :nested-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> Detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/info :hint "Repairing shape :not-head-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> Detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/info :hint "Repairing shape :not-head-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-component-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; There is no solution that may recover it with confidence
          (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          shape)]

    (log/info :hint "Repairing shape :not-component-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :instance-head-not-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a frame.
          (log/debug :hint "  -> Set :type :frame")
          (assoc shape :type :frame
                       :fills []
                       :hide-in-viewer true
                       :rx 0
                       :ry 0))]

    (log/info :hint "Repairing shape :instance-head-not-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :default
  [_ error file _]
  (log/error :hint "Unknown error code, don't know how to repair" :code (:code error))
  file)

(defn repair-file
  [file-data libraries errors]
  (log/info :hint "Repairing file" :id (:id file-data) :error-count (count errors))
  (reduce (fn [changes error]
            (pcb/concat-changes changes
                                (repair-error (:code error)
                                              error
                                              file-data
                                              libraries)))
          (pcb/empty-changes nil)
          errors))

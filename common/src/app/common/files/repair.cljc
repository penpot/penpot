;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.files.repair
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.helpers :as cfh]
   [app.common.logging :as log]
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
          (log/debug :hint "  -> reset geometry")
          (-> shape
              (assoc :x 0)
              (assoc :y 0)
              (assoc :width 0.01)
              (assoc :height 0.01)
              (cts/setup-rect)))]
    (log/dbg :hint "repairing shape :invalid-geometry" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :parent-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set parent to root frame.
          (log/debug :hint "  -> set to " :parent-id uuid/zero)
          (assoc shape :parent-id uuid/zero))]

    (log/dbg :hint "repairing shape :parent-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :child-not-in-parent
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [parent-shape]
          ; Add shape to parent's children list
          (log/debug :hint "  -> add children to" :parent-id (:id parent-shape))
          (update parent-shape :shapes conj (:id shape)))]

    (log/dbg :hint "repairing shape :child-not-in-parent" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:parent-id shape)] repair-shape))))

(defmethod repair-error :duplicated-children
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Remove duplicated
          (log/debug :hint "  -> remove duplicated children")
          (update shape :shapes distinct))]

    (log/dbg :hint "repairing shape :duplicated-children" :id (:id shape) :name (:name shape) :page-id page-id)
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
    (log/dbg :hint "repairing shape :child-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-parent
  [_ {:keys [shape page-id args] :as error} file-data _]
  (log/dbg :hint "repairing shape :invalid-parent" :id (:id shape) :name (:name shape) :page-id page-id)
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-file-data file-data)
      (pcb/change-parent (:parent-id args) [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :frame-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cfh/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/dbg :hint "repairing shape :frame-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :invalid-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Locate the first frame in parents and set frame-id to it.
          (let [page     (ctpl/get-page file-data page-id)
                frame    (cfh/get-frame (:objects page) (:parent-id shape))
                frame-id (or (:id frame) uuid/zero)]
            (log/debug :hint "  -> set to " :frame-id frame-id)
            (assoc shape :frame-id frame-id)))]

    (log/dbg :hint "repairing shape :invalid-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set the :shape as main instance root
          (log/debug :hint "  -> set :main-instance")
          (assoc shape :main-instance true))]

    (log/dbg :hint "repairing shape :component-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main-external
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Set :component-file to local file
          (log/debug :hint "  -> set :component-file to local file")
          (assoc shape :component-file (:id file-data)))]
          ; There is no solution that may recover it with confidence
          ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          ;; shape)]

    (log/dbg :hint "repairing shape :component-main-external" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-not-found
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))

        repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]
          ; There is no solution that may recover it with confidence
          ;; (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
          ;; shape)]

    (log/dbg :hint "repairing shape :component-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes shape-ids repair-shape))))

(defmethod repair-error :invalid-main-instance-id
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [component (ctkl/get-component file-data (:component-id shape))

        repair-component
        (fn [component]
          ; Assign main instance in the component to current shape
          (log/debug :hint "  -> assign main-instance-id" :component-id (:id component))
          (assoc component :main-instance-id (:id shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/dbg :hint "repairing shape :invalid-main-instance-id" :id (:id shape) :name (:name shape) :page-id page-id)
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
          (log/debug :hint "  -> assign main-instance-page" :component-id (:id component))
          (assoc component :main-instance-page page-id))]
    (log/dbg :hint "repairing shape :invalid-main-instance-page" :id (:id shape) :name (:name shape) :page-id page-id)
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

    (log/dbg :hint "repairing shape :invalid-main-instance" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Unset the :shape as main instance root
          (log/debug :hint "  -> unset :main-instance")
          (dissoc shape :main-instance))]

    (log/dbg :hint "repairing shape :component-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/dbg :hint "repairing shape :should-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :should-not-be-component-root
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy root.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/dbg :hint "repairing shape :should-not-be-component-root" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :ref-shape-not-found
  [_ {:keys [shape page-id] :as error} file-data libraries]
  (let [matching-shape (let [page              (ctpl/get-page file-data page-id)
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
                           (when-let [random-shape (ctn/get-shape page (:shape-ref shape))]
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
                                   nil))))))
        reassign-shape
        (fn [shape]
          (log/debug :hint "  -> reassign shape-ref to" :shape-ref (:id matching-shape))
          (assoc shape :shape-ref (:id matching-shape)))

        detach-shape
        (fn [shape]
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    ; If the shape still refers to the remote component, try to find the corresponding near one
    ; and link to it. If not, detach the shape.
    (log/dbg :hint "repairing shape :ref-shape-not-found" :id (:id shape) :name (:name shape) :page-id page-id)
    (if (some? matching-shape)
      (-> (pcb/empty-changes nil page-id)
          (pcb/with-file-data file-data)
          (pcb/update-shapes [(:id shape)] reassign-shape))
      (let [page      (ctpl/get-page file-data page-id)
            shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))]
        (-> (pcb/empty-changes nil page-id)
            (pcb/with-file-data file-data)
            (pcb/update-shapes shape-ids detach-shape))))))


(defmethod repair-error :shape-ref-cycle
  [_ {:keys [shape args] :as error} file-data _]
  (let [repair-component
        (fn [component]
          (let [objects   (:objects component) ;; we only have encounter this on deleted components,
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
    (log/dbg :hint "repairing component :shape-ref-cycle" :id (:id shape) :name (:name shape))
    (-> (pcb/empty-changes nil nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id shape) repair-component))))

(defmethod repair-error :shape-ref-in-main
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Remove shape-ref
          (log/debug :hint "  -> unset :shape-ref")
          (dissoc shape :shape-ref))]

    (log/dbg :hint "repairing shape :shape-ref-in-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :root-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested main head.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/dbg :hint "repairing shape :root-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top main head.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/dbg :hint "repairing shape :nested-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape)
        (pcb/change-parent uuid/zero [shape] nil {:allow-altering-copies true}))))

(defmethod repair-error :root-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a nested copy head.
          (log/debug :hint "  -> unset :component-root")
          (dissoc shape :component-root))]

    (log/dbg :hint "repairing shape :root-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :nested-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a top copy root.
          (log/debug :hint "  -> set :component-root")
          (assoc shape :component-root true))]

    (log/dbg :hint "repairing shape :nested-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-main-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/dbg :hint "repairing shape :not-head-main-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-head-copy-not-allowed
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Detach the shape and convert it to non instance.
          (log/debug :hint "  -> detach shape" :shape-id (:id shape))
          (ctk/detach-shape shape))]

    (log/dbg :hint "repairing shape :not-head-copy-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
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

    (log/dbg :hint "repairing shape :not-component-not-allowed" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :instance-head-not-frame
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ; Convert the shape in a frame.
          (log/debug :hint "  -> set :type :frame")
          (assoc shape :type :frame
                 :fills []
                 :hide-in-viewer true
                 :r1 0
                 :r2 0
                 :r3 0
                 :r4 0))]

    (log/dbg :hint "repairing shape :instance-head-not-frame" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :component-nil-objects-not-allowed
  [_ {:keys [shape] :as error} file-data _]
  (let [repair-component
        (fn [component]
          ; Remove the objects key, or set it to {} if the component is deleted
          (if (:deleted component)
            (do
              (log/debug :hint "  -> set :objects {}")
              (assoc component :objects {}))
            (do
              (log/debug :hint "  -> remove :objects")
              (dissoc component :objects))))]

    (log/dbg :hint "repairing component :component-nil-objects-not-allowed" :id (:id shape) :name (:name shape))
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id shape) repair-component))))

(defmethod repair-error :misplaced-slot
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Remove the swap slot
          (log/debug :hint "  -> remove swap-slot")
          (ctk/remove-swap-slot shape))]

    (log/dbg :hint "repairing shape :misplaced-slot" :id (:id shape) :name (:name shape) :page-id page-id)
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

    (log/dbg :hint "repairing shape :duplicated-slot" :id (:id shape) :name (:name shape) :page-id page-id)
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

    (log/dbg :hint "repairing component :component-duplicated-slot" :id (:id shape) :name (:name shape))
    (-> (pcb/empty-changes nil)
        (pcb/with-library-data file-data)
        (pcb/update-component (:id shape) repair-component))))

(defmethod repair-error :missing-slot
  [_ {:keys [shape page-id args] :as error} file-data _]
  (let [repair-shape
        (fn [shape]
          ;; Set the desired swap slot
          (let [slot (:swap-slot args)]
            (when (some? slot)
              (log/debug :hint (str "  -> set swap-slot to " slot))
              (ctk/set-swap-slot shape slot))))]

    (log/dbg :hint "repairing shape :missing-slot" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :not-a-variant
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :invalid-variant-id
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :invalid-variant-properties
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :variant-not-main
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :parent-not-variant
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :variant-bad-name
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :variant-bad-variant-name
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :variant-component-bad-name
  [_ error file _]
  (log/error :hint "Variant error code, we don't want to auto repair it for now" :code (:code error))
  file)

(defmethod repair-error :default
  [_ error file _]
  (log/error :hint "Unknown error code, don't know how to repair" :code (:code error))
  file)

(defn repair-file
  [{:keys [data id] :as file} libraries errors]
  (log/dbg :hint "repairing file" :id (str id) :errors (count errors))
  (let [{:keys [redo-changes]}
        (reduce (fn [changes error]
                  (pcb/concat-changes changes
                                      (repair-error (:code error)
                                                    error
                                                    data
                                                    libraries)))
                (pcb/empty-changes nil)
                errors)]
    redo-changes))

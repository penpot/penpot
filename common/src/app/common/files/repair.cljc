;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.files.repair
  (:require
   [app.common.data :as d]
   [app.common.files.changes-builder :as pcb]
   [app.common.files.comp-processors :as cfcp]
   [app.common.files.helpers :as cfh]
   [app.common.files.swap-slots :as cfss]
   [app.common.files.tokens :as cfo]
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

(log/set-level! :debug)

(defmulti repair-error
  (fn [code _error _file-data _libraries] code))

(defn- repair-shapes
  "The changes that apply `repair-fn` to the shapes `ids` of the page of
  `error` (its shape, by default)."
  ([code error file-data repair-fn]
   (repair-shapes code error file-data [(:id (:shape error))] repair-fn))
  ([code {:keys [shape page-id]} file-data ids repair-fn]
   (log/debug :hint (str "repairing shape " code) :id (:id shape) :name (:name shape) :page-id page-id)
   (-> (pcb/empty-changes nil page-id)
       (pcb/with-file-data file-data)
       (pcb/update-shapes ids repair-fn))))

(defn- repair-component
  "The changes that apply `repair-fn` to the component `component-id`."
  [code {:keys [shape page-id]} file-data component-id repair-fn]
  (log/debug :hint (str "repairing component " code) :id (:id shape) :name (:name shape) :page-id page-id)
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-library-data file-data)
      (pcb/update-component component-id repair-fn)))

(defn- detach-shape
  [shape]
  (log/debug :hint "  -> detach shape" :shape-id (:id shape))
  (ctk/detach-shape shape))

(defn- set-frame-id
  "Set the frame of `shape` to the first frame among its parents."
  [page shape]
  (let [frame-id (or (:id (cfh/get-frame (:objects page) (:parent-id shape))) uuid/zero)]
    (log/debug :hint "  -> set to " :frame-id frame-id)
    (assoc shape :frame-id frame-id)))

(defn- cannot-repair
  [shape]
  ;; There is no solution that may recover it with confidence
  (log/warn :hint "  -> CANNOT REPAIR THIS AUTOMATICALLY.")
  shape)

(defmethod repair-error :invalid-geometry
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> reset geometry")
                   (-> shape
                       (assoc :x 0 :y 0 :width 0.01 :height 0.01)
                       (cts/setup-rect)))))

(defmethod repair-error :parent-not-found
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> set to " :parent-id uuid/zero)
                   (assoc shape :parent-id uuid/zero))))

(defmethod repair-error :child-not-in-parent
  [code {:keys [shape] :as error} file-data _]
  (repair-shapes code error file-data [(:parent-id shape)]
                 (fn [parent-shape]
                   (log/debug :hint "  -> add children to" :parent-id (:id parent-shape))
                   (update parent-shape :shapes conj (:id shape)))))

(defmethod repair-error :duplicated-children
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> remove duplicated children")
                   (update shape :shapes distinct))))

(defmethod repair-error :child-not-found
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data
                 (fn [parent-shape]
                   (log/debug :hint "  -> remove child" :child-id (:child-id args))
                   (update parent-shape :shapes (fn [shapes]
                                                  (d/removev #(= (:child-id args) %) shapes))))))

(defmethod repair-error :invalid-parent
  [_ {:keys [shape page-id args] :as error} file-data _]
  (log/debug :hint "repairing shape :invalid-parent" :id (:id shape) :name (:name shape) :page-id page-id)
  (-> (pcb/empty-changes nil page-id)
      (pcb/with-file-data file-data)
      (pcb/change-parent (:parent-id args) [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :frame-not-found
  [code {:keys [page-id] :as error} file-data _]
  (repair-shapes code error file-data #(set-frame-id (ctpl/get-page file-data page-id) %)))

(defmethod repair-error :invalid-frame
  [code {:keys [page-id] :as error} file-data _]
  (repair-shapes code error file-data #(set-frame-id (ctpl/get-page file-data page-id) %)))

(defmethod repair-error :component-main-external
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> set :component-file to local file")
                   (assoc shape :component-file (:id file-data)))))

(defmethod repair-error :component-not-found
  [code {:keys [shape page-id] :as error} file-data _]
  (let [page (ctpl/get-page file-data page-id)]
    (repair-shapes code error file-data
                   (cfh/get-children-ids-with-self (:objects page) (:id shape))
                   detach-shape)))

(defmethod repair-error :invalid-main-instance-id
  [code {:keys [shape] :as error} file-data _]
  (let [component (ctkl/get-component file-data (:component-id shape))]
    (if (and (some? component) (not (:deleted component)))
      (repair-component code error file-data (:component-id shape)
                        (fn [component]
                          (log/debug :hint "  -> assign main-instance-id" :component-id (:id component))
                          (assoc component :main-instance-id (:id shape))))
      (repair-shapes code error file-data detach-shape))))

(defmethod repair-error :invalid-main-instance-page
  [code {:keys [shape page-id] :as error} file-data _]
  (repair-component code error file-data (:component-id shape)
                    (fn [component]
                      (log/debug :hint "  -> assign main-instance-page" :component-id (:id component))
                      (assoc component :main-instance-page page-id))))

(defmethod repair-error :invalid-main-instance
  [code error file-data _]
  (repair-shapes code error file-data cannot-repair))

(defmethod repair-error :component-main
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> unset :main-instance")
                   (dissoc shape :main-instance))))

(defmethod repair-error :should-not-be-component-root
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> unset :component-root")
                   (dissoc shape :component-root))))

(defmethod repair-error :ref-shape-not-found
  [code {:keys [shape page-id] :as error} file-data libraries]
  (let [page           (ctpl/get-page file-data page-id)
        matching-shape (let [root-shape        (ctn/get-component-shape (:objects page) shape)
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
                                   nil))))))]

    ;; If the shape still refers to the remote component, try to find the corresponding near one
    ;; and link to it. If not, detach the shape.
    (if (some? matching-shape)
      (repair-shapes code error file-data
                     (fn [shape]
                       (log/debug :hint "  -> reassign shape-ref to" :shape-ref (:id matching-shape))
                       (assoc shape :shape-ref (:id matching-shape))))
      (repair-shapes code error file-data
                     (cfh/get-children-ids-with-self (:objects page) (:id shape))
                     detach-shape))))

(defmethod repair-error :ref-shape-is-not-head
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> unhead shape")
                   (ctk/unhead-shape shape))))

(defmethod repair-error :component-id-mismatch
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
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
                     (dissoc :component-file)))))

(defmethod repair-error :ref-shape-is-head
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> reroot shape")
                   (ctk/rehead-shape shape (:component-file args) (:component-id args)))))

(defmethod repair-error :shape-ref-cycle
  [code {:keys [shape args] :as error} file-data _]
  ;; the :shape of this error is the component
  (repair-component code (dissoc error :page-id) file-data (:id shape)
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
                                             objects)))))))

(defmethod repair-error :shape-ref-in-main
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> unset :shape-ref")
                   (dissoc shape :shape-ref))))

(defmethod repair-error :root-main-not-allowed
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   ;; Convert the shape in a nested main head.
                   (log/debug :hint "  -> unset :component-root")
                   (dissoc shape :component-root))))

(defmethod repair-error :nested-main-not-allowed
  [code {:keys [shape] :as error} file-data _]
  (-> (repair-shapes code error file-data
                     (fn [shape]
                       ;; Convert the shape in a top main head.
                       (log/debug :hint "  -> set :component-root")
                       (assoc shape :component-root true)))
      (pcb/change-parent uuid/zero [shape] nil {:allow-altering-copies true})))

(defmethod repair-error :root-copy-not-allowed
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   ;; Convert the shape in a nested copy head.
                   (log/debug :hint "  -> unset :component-root")
                   (dissoc shape :component-root))))

(defmethod repair-error :nested-copy-not-allowed
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   ;; Convert the shape in a top copy root.
                   (log/debug :hint "  -> set :component-root")
                   (assoc shape :component-root true))))

(defmethod repair-error :not-head-main-not-allowed
  [code error file-data _]
  (repair-shapes code error file-data detach-shape))

(defmethod repair-error :not-head-copy-not-allowed
  [code error file-data _]
  (repair-shapes code error file-data detach-shape))

(defmethod repair-error :instance-head-not-frame
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> set :type :frame")
                   (assoc shape :type :frame
                          :fills []
                          :hide-in-viewer true
                          :r1 0
                          :r2 0
                          :r3 0
                          :r4 0))))

(defmethod repair-error :component-nil-objects-not-allowed
  [code {component :shape :as error} file-data _]   ; in this error the :shape argument is the component
  (repair-component code (dissoc error :page-id) file-data (:id component)
                    (fn [component]
                      ;; Remove the objects key, or set it to {} if the component is deleted
                      (if (:deleted component)
                        (do
                          (log/debug :hint "  -> set :objects {}")
                          (assoc component :objects {}))
                        (do
                          (log/debug :hint "  -> remove :objects")
                          (dissoc component :objects))))))

(defmethod repair-error :non-deleted-component-cannot-have-objects
  [code {component :shape :as error} file-data _]   ; in this error the :shape argument is the component
  (repair-component code (dissoc error :page-id) file-data (:id component)
                    (fn [component]
                      (if-not (:deleted component)
                        (do
                          (log/debug :hint "  -> remove :objects")
                          (dissoc component :objects))
                        component))))

(defmethod repair-error :invalid-text-touched
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> add :content-group to :touched-groups")
                   (update shape :touched ctk/set-touched-group :content-group))))

(defmethod repair-error :legacy-text-touched
  [code {:keys [shape page-id] :as error} file-data libraries]
  (let [page (ctpl/get-page file-data page-id)
        file {:id (:id file-data) :data file-data}
        ref  (when (ctk/in-component-copy? shape)
               (ctf/find-ref-shape file (ctn/make-container page :page) libraries shape
                                   :include-deleted? true))]
    (repair-shapes code error file-data
                   #(assoc % :touched (cfcp/recompute-text-touched % ref)))))

(defmethod repair-error :misplaced-slot
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> remove swap-slot")
                   (cfss/remove-swap-slot shape))))

(defmethod repair-error :misplaced-swap-group
  [code error file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> remove :swap-group")
                   (ctk/remove-swap-group shape))))

(defmethod repair-error :ref-not-in-near-main
  [code {:keys [shape page-id] :as error} file-data libraries]
  (let [page     (ctpl/get-page file-data page-id)
        file     {:id (:id file-data) :data file-data}
        objects  (:objects page)
        replaced (cfcp/find-replaced-shape file (ctn/make-container page :page) libraries shape)
        ids      (cfh/get-children-ids-with-self objects (:id shape))]
    (if (some? replaced)
      (do (log/debug :hint "  -> take the position of" :shape-ref (:id replaced))
          (repair-shapes code error file-data ids
                         (ctf/take-position file libraries objects shape (:id replaced) replaced)))
      ;; no position to take: the copy keeps its content, detached
      (repair-shapes code error file-data ids ctk/detach-shape))))

(defmethod repair-error :duplicate-ref
  [_ {:keys [shape page-id] :as error} file-data _]
  (let [page    (ctpl/get-page file-data page-id)
        objects (:objects page)
        ;; sync pairs the first child that refers to a main shape; the
        ;; others have no position to take
        extra   (->> (:shapes shape)
                     (map #(get objects %))
                     (reduce (fn [[seen extra] child]
                               (let [ref (:shape-ref child)]
                                 (if (and (some? ref) (contains? seen ref))
                                   [seen (conj extra (:id child))]
                                   [(cond-> seen (some? ref) (conj ref)) extra])))
                             [#{} []])
                     (second))
        ids     (mapcat #(cfh/get-children-ids-with-self objects %) extra)]
    (log/debug :hint "repairing shape :duplicate-ref" :id (:id shape) :name (:name shape) :page-id page-id)
    (log/debug :hint "  -> delete shapes" :shape-ids ids)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/remove-objects ids))))

(defn- set-variant-id
  [variant-id shape]
  (log/debug :hint (str "  -> set variant-id to " variant-id))
  (assoc shape :variant-id variant-id))

(defmethod repair-error :main-instance-not-a-variant
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data #(set-variant-id (:variant-id args) %)))

(defmethod repair-error :main-instance-invalid-variant-id
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data #(set-variant-id (:variant-id args) %)))

(defmethod repair-error :invalid-variant-properties
  [_ {:keys [shape page-id args]} file-data _]
  (let [prop-names (:prop-names args)

        component   (get-in file-data [:components (:component-id shape)])
        prop-values (into {} (map (juxt :name :value)) (:variant-properties component))
        properties' (mapv (fn [name] {:name name :value (get prop-values name "")}) prop-names)
        variant-name (ctv/properties-to-name properties')

        repair-component
        (fn [component]
          ;; Rebuild component properties, removing any extra ones and adding missing ones with empty value
          (log/debug :hint "  -> rebuild properties" :component-id (:id component) :prop-names (str prop-names))
          (assoc component :variant-properties properties'))

        repair-shape
        (fn [shape]
          (log/debug :hint "  -> set variant-name" :variant-name variant-name)
          (assoc shape :variant-name variant-name))]

    (log/debug :hint "repairing shape :invalid-variant-properties" :id (:id shape) :name (:name shape) :page-id page-id)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/with-library-data file-data)
        (pcb/update-component (:component-id shape) repair-component)
        (pcb/update-shapes [(:id shape)] repair-shape))))

(defmethod repair-error :variant-not-main
  [_ {:keys [shape page-id]} file-data _]
  (let [page      (ctpl/get-page file-data page-id)
        shape-ids (cfh/get-children-ids-with-self (:objects page) (:id shape))]
    (log/debug :hint "repairing shape :variant-not-main" :id (:id shape) :name (:name shape) :page-id page-id)
    (log/debug :hint "  -> delete shapes" :shape-ids shape-ids)
    (-> (pcb/empty-changes nil page-id)
        (pcb/with-file-data file-data)
        (pcb/remove-objects shape-ids))))

(defmethod repair-error :parent-not-variant
  [code {:keys [shape] :as error} file-data _]
  (repair-shapes code error file-data [(:parent-id shape)]
                 (fn [parent]
                   (log/debug :hint "  -> set :is-variant-container true")
                   (assoc parent :is-variant-container true))))

(defmethod repair-error :variant-main-bad-name
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data
                 (fn [shape]
                   (log/debug :hint "  -> set :name" :name (:variant-name args))
                   (assoc shape :name (:variant-name args)))))

(defmethod repair-error :variant-main-bad-variant-name
  [code {:keys [shape] :as error} file-data _]
  (let [component    (get-in file-data [:components (:component-id shape)])
        variant-name (ctv/properties-to-name (:variant-properties component))]
    (repair-shapes code error file-data
                   (fn [shape]
                     (log/debug :hint "  -> set :variant-name" :variant-name variant-name)
                     (assoc shape :variant-name variant-name)))))

(defmethod repair-error :variant-component-bad-name
  [code {:keys [shape args] :as error} file-data _]
  (let [[path name] (cpn/split-group-name (:variant-container-name args))]
    (repair-component code error file-data (:component-id shape)
                      (fn [component]
                        (log/debug :hint "  -> set :path and :name" :path path :name name)
                        (assoc component :path path :name name)))))

(defmethod repair-error :variant-component-bad-id
  [code {:keys [args] :as error} file-data _]
  (repair-shapes code error file-data #(set-variant-id (:variant-id args) %)))

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

(defn repair-file
  [{:keys [data id] :as file} libraries errors]
  (log/debug :hint "repairing file" :id (str id) :errors (count errors))
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

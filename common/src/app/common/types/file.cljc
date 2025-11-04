;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.file
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.features :as cfeat]
   [app.common.files.defaults :refer [version]]
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.geom.shapes.tree-seq :as gsts]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.color :as ctc]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.library :as ctlb]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.plugins :refer [schema:plugin-data]]
   [app.common.types.shape :as cts]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :refer [schema:tokens-lib]]
   [app.common.types.typographies-list :as ctyl]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTANTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce BASE-FONT-SIZE "16px")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:media
  "A schema that represents the file media object"
  [:map {:title "FileMedia"}
   [:id ::sm/uuid]
   [:created-at {:optional true} ::ct/inst]
   [:deleted-at {:optional true} ::ct/inst]
   [:name :string]
   [:width ::sm/safe-int]
   [:height ::sm/safe-int]
   [:mtype :string]
   [:media-id ::sm/uuid]
   [:file-id {:optional true} ::sm/uuid]
   [:thumbnail-id {:optional true} ::sm/uuid]
   [:is-local {:optional true} :boolean]])

(def schema:colors
  [:map-of {:gen/max 5} ::sm/uuid ctc/schema:library-color])

(def schema:components
  [:map-of {:gen/max 5} ::sm/uuid ctn/schema:container])

(def schema:typographies
  [:map-of {:gen/max 2} ::sm/uuid cty/schema:typography])

(def schema:pages-index
  [:map-of {:gen/max 5} ::sm/uuid ctp/schema:page])

(def schema:options
  [:map {:title "FileOptions"}
   [:components-v2 {:optional true} ::sm/boolean]
   [:base-font-size {:optional true} :string]])

(def schema:data
  [:map {:title "FileData"}
   [:pages [:vector ::sm/uuid]]
   [:pages-index schema:pages-index]
   [:options {:optional true} schema:options]
   [:colors {:optional true} schema:colors]
   [:components {:optional true} schema:components]
   [:typographies {:optional true} schema:typographies]
   [:plugin-data {:optional true} schema:plugin-data]
   [:tokens-lib {:optional true} schema:tokens-lib]])

(def schema:file
  "A schema for validate a file data structure; data is optional
  because sometimes we want to validate file without the data."
  [:map {:title "file"}
   [:id ::sm/uuid]
   [:name :string]
   [:revn :int]
   [:vern {:optional true} :int]
   [:created-at ::ct/inst]
   [:modified-at ::ct/inst]
   [:deleted-at {:optional true} ::ct/inst]
   [:project-id {:optional true} ::sm/uuid]
   [:team-id {:optional true} ::sm/uuid]
   [:is-shared {:optional true} ::sm/boolean]
   [:has-media-trimmed {:optional true} ::sm/boolean]
   [:data {:optional true} schema:data]
   [:version :int]
   [:features ::cfeat/features]
   [:migrations {:optional true}
    [::sm/set {:ordered true} :string]]])

(sm/register! ::data schema:data)
(sm/register! ::file schema:file)
(sm/register! ::colors schema:colors)
(sm/register! ::typographies schema:typographies)

(def check-file
  (sm/check-fn schema:file :hint "invalid file"))

(def check-file-data
  (sm/check-fn schema:data :hint "invalid file data"))

(def check-file-media
  (sm/check-fn schema:media))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def empty-file-data
  {:pages []
   :pages-index {}})

(defn make-file-data
  ([file-id]
   (make-file-data file-id (uuid/next)))

  ([file-id page-id]
   (let [page (when (some? page-id)
                (ctp/make-empty-page {:id page-id :name "Page 1"}))]

     (cond-> (assoc empty-file-data :id file-id)
       (some? page)
       (ctpl/add-page page)

       :always
       (update :options merge {:components-v2 true
                               :base-font-size BASE-FONT-SIZE})))))

;; FIXME: we can't handle the "default" migrations for avoid providing
;; them all the time the file is created because we can't import file
;; migrations because of circular import issue; We need to split the
;; list of migrations and impl of migrations in separate namespaces

;; FIXME: refactor

(defn make-file
  [{:keys [id project-id name revn is-shared features migrations
           metadata backend ignore-sync-until created-at modified-at deleted-at]
    :as params}

   & {:keys [create-page with-data page-id]
      :or {create-page true with-data true}}]

  (let [id          (or id (uuid/next))
        created-at  (or created-at (ct/now))
        modified-at (or modified-at created-at)
        features    (d/nilv features #{})

        data
        (when with-data
          (if create-page
            (if page-id
              (make-file-data id page-id)
              (make-file-data id))
            (make-file-data id nil)))

        file
        (d/without-nils
         {:id id
          :project-id project-id
          :name name
          :revn (d/nilv revn 0)
          :vern 0
          :is-shared (d/nilv is-shared false)
          :version (:version params version)
          :data data
          :features features
          :migrations migrations
          :metadata metadata
          :backend backend
          :ignore-sync-until ignore-sync-until
          :created-at created-at
          :modified-at modified-at
          :deleted-at deleted-at})]

    (check-file file)))

;; Helpers

(defn file-data
  [file]
  (:data file))

(defn update-file-data
  [file f]
  (update file :data f))

(defn containers-seq
  "Generate a sequence of all pages and all components, wrapped as containers"
  [file-data]
  (concat (map #(ctn/make-container % :page) (ctpl/pages-seq file-data))
          (map #(ctn/make-container % :component) (ctkl/components-seq file-data))))

(defn object-containers-seq
  "Generate a sequence of all pages and all deleted components (all those components that have :objects), wrapped as containers"
  [file-data]
  (concat (map #(ctn/make-container % :page) (ctpl/pages-seq file-data))
          (map #(ctn/make-container % :component) (ctkl/deleted-components-seq file-data))))

(defn update-container
  "Update a container inside the file, it can be a page or a component"
  [file-data container f]
  (if (ctn/page? container)
    (ctpl/update-page file-data (:id container) f)
    (ctkl/update-component file-data (:id container) f)))

;; Asset helpers
(defn find-component-file
  [file libraries component-file]
  (if (and (some? file) (= component-file (:id file)))
    file
    (get libraries component-file)))

(defn get-component
  "Retrieve a component from a library."
  [libraries library-id component-id & {:keys [include-deleted?] :or {include-deleted? false}}]
  (ctkl/get-component (dm/get-in libraries [library-id :data]) component-id include-deleted?))

(defn resolve-component
  "Retrieve the referenced component, from the local file or from a library"
  [shape file libraries & {:keys [include-deleted?] :or {include-deleted? false}}]
  (if (= (:component-file shape) (:id file))
    (ctkl/get-component (:data file) (:component-id shape) include-deleted?)
    (get-component libraries (:component-file shape) (:component-id shape) :include-deleted? include-deleted?)))

(defn get-component-library
  "Retrieve the library the component belongs to."
  [libraries instance-root]
  (get libraries (:component-file instance-root)))

(defn get-component-page
  "Retrieve the page where the main instance of the component resides."
  [file-data component]
  (ctpl/get-page file-data (:main-instance-page component)))

(defn get-component-container
  "Retrieve the container that holds the component shapes (the page
   or the component itself on deleted component)."
  [file-data component]
  (if (not (:deleted component))
    (let [component-page (get-component-page file-data component)]
      (cfh/make-container component-page :page))
    (cfh/make-container component :component)))

(defn get-component-container-from-head
  [instance-head libraries & {:keys [include-deleted?] :or {include-deleted? true}}]
  (let [library-data   (-> (get-component-library libraries instance-head)
                           :data)
        component (ctkl/get-component library-data (:component-id instance-head) include-deleted?)]
    (get-component-container library-data component)))

(defn get-component-root
  "Retrieve the root shape of the component."
  [file-data component]
  (if (not (:deleted component))
    (-> file-data
        (get-component-page component)
        (ctn/get-shape (:main-instance-id component)))
    (ctk/get-deleted-component-root component)))

(defn get-component-shape
  "Retrieve one shape in the component by id. If with-context? is true, add the
   file and container where the shape resides in its metadata."
  [file-data component shape-id & {:keys [with-context?] :or {with-context? false}}]
  (if (not (:deleted component))
    (let [component-page (get-component-page file-data component)]
      (when component-page
        (let [child (cfh/get-child (:objects component-page)
                                   (:main-instance-id component)
                                   shape-id)]
          (cond-> child
            (and child with-context?)
            (with-meta {:file {:id (:id file-data)
                               :data file-data}
                        :container (ctn/make-container component-page :page)})))))

    (let [shape (dm/get-in component [:objects shape-id])]
      (cond-> shape
        (and shape with-context?)
        (with-meta {:file {:id (:id file-data)
                           :data file-data}
                    :container (ctn/make-container component :component)})))))

(defn get-ref-shape
  "Retrieve the shape in the component that is referenced by the instance shape."
  [file-data component shape & {:keys [with-context?] :or {with-context? false}}]
  (when (:shape-ref shape)
    (get-component-shape file-data component (:shape-ref shape) :with-context? with-context?)))

(defn get-shape-in-copy
  "Given a shape in the main component and the root of the copy component returns the equivalent
  shape inside the root copy that matches the main-shape"
  [file-data main-shape root-copy]
  (->> (gsts/get-children-seq (:id root-copy) (:objects file-data))
       (d/seek #(= (:shape-ref %) (:id main-shape)))))

(defn find-ref-shape
  "Locate the nearest component in the local file or libraries, and retrieve the shape
   referenced by the instance shape."
  [file container libraries shape & {:keys [include-deleted? with-context?] :or {include-deleted? false with-context? false}}]
  (let [find-ref-shape-in-head
        (fn [head-shape]
          (let [component-file (find-component-file file libraries (:component-file head-shape))
                component      (when (some? component-file)
                                 (ctkl/get-component (:data component-file) (:component-id head-shape) include-deleted?))]
            (when (some? component)
              (get-ref-shape (:data component-file) component shape :with-context? with-context?))))]
    (some find-ref-shape-in-head (ctn/get-parent-heads (:objects container) shape))))

(defn advance-shape-ref
  "Get the shape-ref of the near main of the shape, recursively repeated as many times
   as the given levels."
  [file container libraries shape levels & {:keys [include-deleted?] :or {include-deleted? false}}]
  (let [ref-shape (find-ref-shape file container libraries shape :include-deleted? include-deleted? :with-context? true)]
    (if (or (nil? (:shape-ref ref-shape)) (not (pos? levels)))
      (:id ref-shape)
      (advance-shape-ref file (:container (meta ref-shape)) libraries ref-shape (dec levels) :include-deleted? include-deleted?))))

(defn find-ref-component
  "Locate the nearest component in the local file or libraries that is referenced by the
   instance shape."
  [file page libraries shape & {:keys [include-deleted?] :or {include-deleted? false}}]
  (let [find-ref-component-in-head
        (fn [head-shape]
          (let [component-file (find-component-file file libraries (:component-file head-shape))
                component      (when (some? component-file)
                                 (ctkl/get-component (:data component-file)
                                                     (:component-id head-shape)
                                                     include-deleted?))]
            (when (some? component)
              (when (get-ref-shape (:data component-file) component shape)
                component))))]

    (some find-ref-component-in-head (ctn/get-parent-copy-heads (:objects page) shape))))

(defn find-remote-shape
  "Recursively go back by the :shape-ref of the shape until find the correct shape of the original component"
  [container libraries shape & {:keys [with-context?] :or {with-context? false}}]
  (let [top-instance        (ctn/get-component-shape (:objects container) shape)
        component-file      (get-in libraries [(:component-file top-instance) :data])
        component           (ctkl/get-component component-file (:component-id top-instance) true)
        remote-shape        (get-ref-shape component-file component shape)
        component-container (get-component-container component-file component)
        [remote-shape component-container]
        (if (some? remote-shape)
          [remote-shape component-container]
          ;; If not found, try the case of this being a fostered or swapped children
          (let [head-instance       (ctn/get-head-shape (:objects container) shape)
                component-file      (get-in libraries [(:component-file head-instance) :data])
                head-component      (ctkl/get-component component-file (:component-id head-instance) true)
                remote-shape'       (get-ref-shape component-file head-component shape)
                component-container (get-component-container component-file component)]
            [remote-shape' component-container]))]

    (if (nil? remote-shape)
      nil
      (if (nil? (:shape-ref remote-shape))
        (cond-> remote-shape
          (and remote-shape with-context?)
          (with-meta {:file {:id (:id file-data)
                             :data file-data}
                      :container component-container}))
        (find-remote-shape component-container libraries remote-shape :with-context? with-context?)))))

(defn direct-copy?
  "Check if the shape is in a direct copy of the component (i.e. the shape-ref points to shapes inside
   the component)."
  [shape component page file libraries]
  (let [ref-component (find-ref-component file page libraries shape :include-deleted? true)]
    (true? (= (:id component) (:id ref-component)))))

(defn find-swap-slot
  ([shape container file libraries]
   (find-swap-slot shape container file libraries #{}))
  ([shape container file libraries viewed-ids]
   (if (contains? viewed-ids (:id shape)) ;; prevent cycles
     nil
     (if-let [swap-slot (ctk/get-swap-slot shape)]
       swap-slot
       (let [ref-shape (find-ref-shape file
                                       container
                                       libraries
                                       shape
                                       :include-deleted? true
                                       :with-context? true)
             shape-meta (meta ref-shape)
             ref-file (:file shape-meta)
             ref-container (:container shape-meta)]
         (when ref-shape
           (if-let [swap-slot (ctk/get-swap-slot ref-shape)]
             swap-slot
             (if (ctk/main-instance? ref-shape)
               (:id shape)
               (find-swap-slot ref-shape ref-container ref-file libraries (conj viewed-ids (:id shape)))))))))))

(defn match-swap-slot?
  [shape-main shape-inst container-inst container-main file libraries]
  (let [slot-main (find-swap-slot shape-main container-main file libraries)
        slot-inst (find-swap-slot shape-inst container-inst file libraries)]
    (when (some? slot-inst)
      (or (= slot-main slot-inst)
          (= (:id shape-main) slot-inst)))))

(defn- find-next-related-swap-shape-id
  "Go up from the chain of references shapes that will eventually lead to the shape
   with swap-slot-id as id. Returns the next shape on the chain"
  [parent swap-slot-id libraries]
  (let [container         (get-component-container-from-head parent libraries)
        objects           (:objects container)

        children          (cfh/get-children objects (:id parent))
        original-shape-id (->> children
                               (filter #(= swap-slot-id (:id %)))
                               first
                               :id)]
    (if original-shape-id
      ;; Return the children which id is the swap-slot-id
      original-shape-id
      ;; No children with swap-slot-id as id, go up
      (let [referenced-shape (find-ref-shape nil container libraries parent)
            ;; Recursive call that will get the id of the next shape on
            ;; the chain that ends on a shape with swap-slot-id as id
            next-shape-id    (when referenced-shape
                               (find-next-related-swap-shape-id referenced-shape swap-slot-id libraries))]
        ;; Return the children which shape-ref points to the next-shape-id
        (->> children
             (filter #(= next-shape-id (:shape-ref %)))
             first
             :id)))))

(defn find-ref-id-for-swapped
  "When a shape has been swapped, find the original ref-id that the shape had
   before the swap"
  [shape container libraries]
  (let [swap-slot   (ctk/get-swap-slot shape)
        objects     (:objects container)

        parent      (get objects (:parent-id shape))
        parent-head (ctn/get-head-shape objects parent)
        parent-ref  (find-ref-shape nil container libraries parent-head)]

    (when (and swap-slot parent-ref)
      (find-next-related-swap-shape-id parent-ref swap-slot libraries))))

(defn get-component-shapes
  "Retrieve all shapes of the component"
  [file-data component]

  (if (not (:deleted component)) ;; the deleted components have its children in the :objects property
    (let [instance-page (get-component-page file-data component)]
      (cfh/get-children-with-self (:objects instance-page) (:main-instance-id component)))
    (vals (:objects component))))

;; Return true if the object is a component that exists on the file or its libraries (even a deleted one)
(defn is-main-of-known-component?
  [shape libraries]
  (let [main-instance?  (ctk/main-instance? shape)
        component-id    (:component-id shape)
        file-id         (:component-file shape)
        component       (ctkl/get-component (dm/get-in libraries [file-id :data]) component-id true)]
    (and main-instance?
         component)))

(defn load-component-objects
  "Add an :objects property to the component, with only the shapes that belong to it"
  ([file-data component]
   (load-component-objects file-data component (gpt/point 0 0)))
  ([file-data component delta]
   (if (and component (empty? (:objects component))) ;; This operation may be called twice, e.g. in an idempotent change
     (let [component-page (get-component-page file-data component)
           page-objects   (:objects component-page)
           objects        (->> (cons (:main-instance-id component)
                                     (cfh/get-children-ids page-objects (:main-instance-id component)))
                               (map #(get page-objects %))
                               ;; when it is an undo of a cut-paste, we need to undo the movement
                               ;; of the shapes so we need to move them delta
                               (map #(gsh/move % delta))
                               (d/index-by :id))]
       (assoc component :objects objects))
     component)))

(defn delete-component
  "Mark a component as deleted and store the main instance shapes iside it, to
  be able to be recovered later."
  [file-data component-id skip-undelete? delta]
  (let [delta         (or delta (gpt/point 0 0))]
    (if skip-undelete?
      (ctkl/delete-component file-data component-id)
      (-> file-data
          (ctkl/update-component component-id #(load-component-objects file-data % delta))
          (ctkl/mark-component-deleted component-id)))))

(defn restore-component
  "Recover a deleted component and all its shapes and put all this again in place."
  [file-data component-id page-id]
  (let [update-page?       (not (nil? page-id))
        component          (ctkl/get-component file-data component-id true)
        main-instance-page (or page-id (:main-instance-page component))
        main-instance      (dm/get-in file-data [:pages-index main-instance-page
                                                 :objects (:main-instance-id component)])]
    (cond-> file-data
      :always
      (->
       (ctkl/update-component component-id #(dissoc % :objects))
       (ctkl/mark-component-undeleted component-id))

      update-page?
      (ctkl/update-component component-id #(assoc % :main-instance-page page-id))

      (ctk/is-variant? component)
      (ctkl/update-component component-id #(assoc % :variant-id (:variant-id main-instance))))))

(defn purge-component
  "Remove permanently a component."
  [file-data component-id]
  (ctkl/delete-component file-data component-id))

(defmulti uses-asset?
  "Checks if a shape uses the given asset."
  (fn [asset-type _ _ _] asset-type))

(defmethod uses-asset? :component
  [_ shape library-id component]
  (ctk/instance-of? shape library-id (:id component)))

(defmethod uses-asset? :color
  [_ shape library-id color]
  (cts/uses-library-color? shape library-id (:id color)))

(defmethod uses-asset? :typography
  [_ shape library-id typography]
  (cty/uses-library-typography? shape library-id (:id typography)))

(defn find-asset-type-usages
  "Find all usages of an asset in a file (may be in pages or in the components
  of the local library).

  Returns a list ((asset ((container shapes) (container shapes)...))...)"
  [file-data library-data asset-type]
  (let [assets (case asset-type
                 :component  (ctkl/components-seq library-data)
                 :color      (vals (ctlb/get-colors library-data))
                 :typography (ctyl/typographies-seq library-data))

        find-usages-in-container
        (fn [container asset]
          (let [instances (filter #(uses-asset? asset-type % (:id library-data) asset)
                                  (ctn/shapes-seq container))]
            (when (d/not-empty? instances)
              [[container instances]])))

        find-asset-usages
        (fn [file-data asset]
          (mapcat #(find-usages-in-container % asset) (containers-seq file-data)))]

    (mapcat (fn [asset]
              (let [instances (find-asset-usages file-data asset)]
                (when (d/not-empty? instances)
                  [[asset instances]])))
            assets)))

(defn used-in?
  "Checks if a specific asset is used in a given file (by any shape in its pages or in
  the components of the local library)."
  [file-data library-id asset asset-type]
  (letfn [(used-in-shape? [shape]
            (uses-asset? asset-type shape library-id asset))

          (used-in-container? [container]
            (some used-in-shape? (ctn/shapes-seq container)))]

    (some used-in-container? (containers-seq file-data))))

(defn used-assets-changed-since
  "Get a lazy sequence of all assets in the library that are in use by the file and have
   been modified after the given date."
  [file-data library since-date]
  (letfn [(used-assets-shape [shape]
            (concat
             (ctkl/used-components-changed-since shape library since-date)
             (ctlb/used-colors-changed-since shape library since-date)
             (ctyl/used-typographies-changed-since shape library since-date)))

          (used-assets-container [container]
            (->> (ctn/shapes-seq container)
                 (mapcat used-assets-shape)
                 (map #(assoc % :container-id (:id container)))))]

    (mapcat used-assets-container (containers-seq file-data))))

(defn get-or-add-library-page
  "If exists a page named 'Main components', get the id and calculate the position to start
  adding new components. If not, create it and start at (0, 0)."
  [file-data grid-gap]
  (let [library-page (d/seek #(= (:name %) "Main components") (ctpl/pages-seq file-data))]
    (if (some? library-page)
      (let [compare-pos (fn [pos shape]
                          (let [bounds (gsh/bounding-box shape)]
                            (gpt/point (min (:x pos) (get bounds :x 0))
                                       (max (:y pos) (+ (get bounds :y 0)
                                                        (get bounds :height 0)
                                                        grid-gap)))))
            position (reduce compare-pos
                             (gpt/point 0 0)
                             (ctn/shapes-seq library-page))]
        [file-data (:id library-page) position])
      (let [library-page (ctp/make-empty-page {:id (uuid/next) :name "Main components"})]
        [(ctpl/add-page file-data library-page) (:id library-page) (gpt/point 0 0)]))))

(defn- absorb-components
  [file-data used-components library-data]
  (let [grid-gap 50

        ; Search for the library page. If not exists, create it.
        [file-data page-id start-pos]
        (get-or-add-library-page file-data grid-gap)

        absorb-component
        (fn [file-data [component instances] position]
          (let [page (ctpl/get-page file-data page-id)

                ; Make a new main instance for the component
                [main-instance-shape main-instance-shapes]
                (ctn/make-component-instance page
                                             component
                                             library-data
                                             position
                                             {:main-instance? true
                                              :keep-ids? true})

                main-instance-shapes
                (map #(cond-> %
                        (some? (:component-file %))
                        (assoc :component-file (:id file-data)))
                     main-instance-shapes)

                ; Add all shapes of the main instance to the library page
                add-main-instance-shapes
                (fn [page]
                  (reduce (fn [page shape]
                            (ctst/add-shape (:id shape)
                                            shape
                                            page
                                            (:frame-id shape)
                                            (:parent-id shape)
                                            nil     ; <- As shapes are ordered, we can safely add each
                                            true))  ;    one at the end of the parent's children list.
                          page
                          main-instance-shapes))

                ; Copy the component in the file local library
                copy-component
                (fn [file-data]
                  (ctkl/add-component file-data
                                      {:id (:id component)
                                       :name (:name component)
                                       :path (:path component)
                                       :main-instance-id (:id main-instance-shape)
                                       :main-instance-page page-id}))

                ; Change all existing instances to point to the local file
                remap-instances
                (fn [file-data [container shapes]]
                  (let [remap-instance #(assoc % :component-file (:id file-data))]
                    (update-container file-data
                                      container
                                      #(reduce (fn [container shape]
                                                 (ctn/update-shape container
                                                                   (:id shape)
                                                                   remap-instance))
                                               %
                                               shapes))))]

            (as-> file-data $
              (ctpl/update-page $ page-id add-main-instance-shapes)
              (copy-component $)
              (reduce remap-instances $ instances))))

        ; Absorb all used components into the local library. Position
        ; the main instances in a grid in the library page.
        add-component-grid
        (fn [data used-components]
          (let [position-seq (ctst/generate-shape-grid
                              (map #(get-component-root library-data (first %)) used-components)
                              start-pos
                              grid-gap)]
            (loop [data           data
                   components-seq (seq used-components)
                   position-seq   position-seq]
              (let [used-component (first components-seq)
                    position       (first position-seq)]
                (if (nil? used-component)
                  data
                  (recur (absorb-component data used-component position)
                         (rest components-seq)
                         (rest position-seq)))))))]

    (add-component-grid file-data (sort-by #(:name (first %)) used-components))))

;: FIXME: this can be moved to library
(defn- absorb-colors
  [file-data used-colors]
  (let [absorb-color
        (fn [file-data [color usages]]
          (let [remap-shape #(cts/remap-colors % (:id file-data) color)

                remap-shapes
                (fn [file-data [container shapes]]
                  (update-container file-data
                                    container
                                    #(reduce (fn [container shape]
                                               (ctn/update-shape container
                                                                 (:id shape)
                                                                 remap-shape))
                                             %
                                             shapes)))]
            (as-> file-data $
              (ctlb/add-color $ color)
              (reduce remap-shapes $ usages))))]

    (reduce absorb-color
            file-data
            used-colors)))

(defn- absorb-typographies
  [file-data used-typographies]
  (let [absorb-typography
        (fn [file-data [typography usages]]
          (let [remap-shape #(cty/remap-typographies % (:id file-data) typography)

                remap-shapes
                (fn [file-data [container shapes]]
                  (update-container file-data
                                    container
                                    #(reduce (fn [container shape]
                                               (ctn/update-shape container
                                                                 (:id shape)
                                                                 remap-shape))
                                             %
                                             shapes)))]
            (as-> file-data $
              (ctyl/add-typography $ typography)
              (reduce remap-shapes $ usages))))]

    (reduce absorb-typography
            file-data
            used-typographies)))

(defn absorb-assets
  "Find all assets of a library that are used in the file, and
  move them to the file local library."
  [file-data library-data]
  (let [used-components   (find-asset-type-usages file-data library-data :component)
        file-data         (cond-> file-data
                            (d/not-empty? used-components)
                            (absorb-components used-components library-data))
                            ;; Note that absorbed components may also be using colors
                            ;; and typographies. This is the reason of doing this first
                            ;; and accumulating file data for the next ones.

        used-colors       (find-asset-type-usages file-data library-data :color)
        file-data         (cond-> file-data
                            (d/not-empty? used-colors)
                            (absorb-colors used-colors))

        used-typographies (find-asset-type-usages file-data library-data :typography)
        file-data         (cond-> file-data
                            (d/not-empty? used-typographies)
                            (absorb-typographies used-typographies))]
    file-data))

;; Debug helpers

(declare dump-shape-component-info)

(defn dump-shape
  "Display a summary of a shape and its relationships, and recursively of all children."
  [shape-id level objects file libraries {:keys [show-ids show-touched] :as flags}]
  (let [shape (get objects shape-id)]
    (println (str/pad (str (str/repeat "  " level)
                           (when (:main-instance shape) "{")
                           (:name shape)
                           (when (:main-instance shape) "}")
                           (when (seq (:touched shape)) "*")
                           (when show-ids (str/format " %s" (:id shape))))
                      {:length 20
                       :type :right})
             (dump-shape-component-info shape objects file libraries flags))
    (when show-touched
      (when (seq (:touched shape))
        (println (str (str/repeat "  " level)
                      "    "
                      (str (:touched shape)))))
      (when (:remote-synced shape)
        (println (str (str/repeat "  " level)
                      "    (remote-synced)"))))
    (when (:shapes shape)
      (dorun (for [shape-id (:shapes shape)]
               (dump-shape shape-id
                           (inc level)
                           objects
                           file
                           libraries
                           flags))))))

(defn dump-shape-component-info
  "If the shape is inside a component, display the information of the relationship."
  [shape objects file libraries {:keys [show-ids]}]
  (if (nil? (:shape-ref shape))
    (if (:component-root shape)
      (str " #" (when show-ids (str/format " [Component %s]" (:component-id shape))))
      "")
    (let [root-shape        (ctn/get-component-shape objects shape)
          component-file-id (when root-shape (:component-file root-shape))
          component-file    (when component-file-id (get libraries component-file-id nil))
          component-shape   (find-ref-shape file
                                            {:objects objects}
                                            libraries
                                            shape
                                            :include-deleted? true)]

      (str/format " %s--> %s%s%s%s%s"
                  (cond (:component-root shape) "#"
                        (:component-id shape) "@"
                        :else "-")

                  (when (:component-file shape)
                    (str/format "<%s> "
                                (if component-file
                                  (if (= (:id component-file) (:id file))
                                    "local"
                                    (:name component-file))
                                  (if show-ids
                                    (str/format "¿%s?" (:component-file shape))
                                    "?"))))

                  (or (:name component-shape)
                      (if show-ids
                        (str/format "¿%s?" (:shape-ref shape))
                        "?"))

                  (when (and show-ids component-shape)
                    (str/format " %s" (:id component-shape)))

                  (if (or (:component-root shape)
                          (nil? (:component-id shape))
                          true)
                    ""
                    (let [component-id      (:component-id shape)
                          component-file-id (:component-file shape)
                          component-file    (when component-file-id (get libraries component-file-id nil))
                          component         (if component-file
                                              (ctkl/get-component (:data component-file) component-id true)
                                              (ctkl/get-component (:data file) component-id true))]
                      (str/format " (%s%s)"
                                  (when component-file (str/format "<%s> "
                                                                   (if (= (:id component-file) (:id file))
                                                                     "local"
                                                                     (:name component-file))))
                                  (:name component))))

                  (when (and show-ids (:component-id shape))
                    (str/format " [Component %s]" (:component-id shape)))))))

(defn dump-component
  "Display a summary of a component and the links to the main instance.
   If the component contains an :objects, display also all shapes inside."
  [component file libraries {:keys [show-ids show-modified] :as flags}]
  (println (str/format "[%sComponent: %s]%s%s"
                       (when (:deleted component) "DELETED ")
                       (:name component)
                       (when show-ids (str " " (:id component)))
                       (when show-modified (str " " (:modified-at component)))))
  (when (:main-instance-page component)
    (let [page (get-component-page (:data file) component)
          root (get-component-root (:data file) component)]
      (if-not show-ids
        (println (str "  --> [" (:name page) "] " (:name root)))
        (do
          (println (str "  " (:name page) (str/format " %s" (:id page))))
          (println (str "  " (:name root) (str/format " %s" (:id root))))))))

  (when (and (:main-instance-page component)
             (seq (:objects component)))
    (println))

  (when (seq (:objects component))
    (let [root (ctk/get-deleted-component-root component)]
      (dump-shape (:id root)
                  1
                  (:objects component)
                  file
                  libraries
                  flags))))

(defn dump-page
  "Display a summary of a page, and of all shapes inside."
  [page file libraries {:keys [show-ids root-id] :as flags
                        :or {root-id uuid/zero}}]
  (let [objects (:objects page)
        root    (get objects root-id)]
    (println (str/format "[Page: %s]%s"
                         (:name page)
                         (when show-ids (str " " (:id page)))))
    (dump-shape (:id root)
                1
                objects
                file
                libraries
                flags)))

(defn dump-library
  "Display a summary of a library, and of all components inside."
  [library file libraries {:keys [show-ids only include-deleted?] :as flags}]
  (let [lib-components (ctkl/components (:data library) {:include-deleted? include-deleted?})]
    (println)
    (println (str/format "========= %s%s"
                         (if (= (:id library) (:id file))
                           "Local library"
                           (str/format "Library %s" (:name library)))
                         (when show-ids
                           (str/format " %s" (:id library)))))

    (if (seq lib-components)
      (dorun (for [component (vals lib-components)]
               (when (or (nil? only) (only (:id component)))
                 (do
                   (println)
                   (dump-component component
                                   library
                                   libraries
                                   flags)))))
      (do
        (println)
        (println "(no components)")))))

(defn dump-tree
  "Display all shapes in the given page, and also all components of the local
   library and all linked libraries."
  [file page-id libraries flags]
  (let [page (ctpl/get-page (:data file) page-id)]

    (dump-page page file libraries flags)

    (dump-library file
                  file
                  libraries
                  flags)

    (dorun (for [library (vals libraries)]
             (dump-library library
                           file
                           libraries
                           flags)))
    (println)))

(defn dump-subtree
  "Display all shapes in the context of the given shape, and also the components
   used by any of the shape or children."
  [file page-id shape-id libraries flags]
  (let [libraries* (assoc libraries (:id file) file)]
    (letfn [(add-component
              [libs-to-show library-id component-id]
              ;; libs-to-show is a structure like {<lib1-id> #{<comp1-id> <comp2-id>}
              ;;                                   <lib2-id> #{<comp3-id>}
              (let [component-ids (conj (get libs-to-show library-id #{})
                                        component-id)]
                (assoc libs-to-show library-id component-ids)))

            (find-used-components
              [page root]
              (let [children (cfh/get-children-with-self (:objects page) (:id root))]
                (reduce (fn [libs-to-show shape]
                          (if (ctk/instance-head? shape)
                            (add-component libs-to-show (:component-file shape) (:component-id shape))
                            libs-to-show))
                        {}
                        children)))

            (find-used-components-cumulative
              [libs-to-show page root]
              (let [sublibs-to-show (find-used-components page root)]
                (reduce (fn [libs-to-show [library-id components]]
                          (reduce (fn [libs-to-show component-id]
                                    (let [library   (get libraries* library-id)
                                          component (get-component libraries* library-id component-id {:include-deleted? true})
                                          ;; page      (get-component-page (:data library) component)
                                          root      (when component
                                                      (get-component-root (:data library) component))]
                                      (if (nil? component)
                                        (do
                                          (println (str/format "(Cannot find component %s in library %s)"
                                                               component-id library-id))
                                          libs-to-show)
                                        (if (get-in libs-to-show [library-id (:id root)])
                                          libs-to-show
                                          (-> libs-to-show
                                              (add-component library-id component-id))))))
                                              ;; (find-used-components-cumulative page root)

                                  libs-to-show
                                  components))
                        libs-to-show
                        sublibs-to-show)))]

      (let [page (ctpl/get-page (:data file) page-id)
            shape (ctst/get-shape page shape-id)
            root (or (ctn/get-instance-root (:objects page) shape)
                     shape) ; If not in a component, start by the shape itself

            libs-to-show (find-used-components-cumulative {} page root)]

        (if (nil? root)
          (println (str "Cannot find shape " shape-id))
          (do
            (dump-page page file libraries* (assoc flags :root-id (:id root)))
            (dorun (for [[library-id component-ids] libs-to-show]
                     (let [library (get libraries* library-id)]
                       (dump-library library
                                     file
                                     libraries*
                                     (assoc flags
                                            :only component-ids
                                            :include-deleted? true))
                       (dorun (for [component-id component-ids]
                                (let [library   (get libraries* library-id)
                                      component (get-component libraries* library-id component-id {:include-deleted? true})
                                      page      (get-component-page (:data library) component)
                                      root      (get-component-root (:data library) component)]
                                  (when-not (:deleted component)
                                    (println)
                                    (dump-page page file libraries* (assoc flags :root-id (:id root))))))))))))))))

;; Export

(defn- get-component-ref-file
  [objects shape]

  (cond
    (contains? shape :component-file)
    (get shape :component-file)

    (contains? shape :shape-ref)
    (recur objects (get objects (:parent-id shape)))

    :else
    nil))

(defn detach-external-references
  [file file-id]
  (let [detach-text
        (fn [content]
          (->> content
               (txt/transform-nodes
                #(cond-> %
                   (not= file-id (:fill-color-ref-file %))
                   (dissoc :fill-color-ref-id :fill-color-ref-file)

                   (not= file-id (:typography-ref-file %))
                   (dissoc :typography-ref-id :typography-ref-file)))))

        detach-shape
        (fn [objects shape]
          (l/debug :hint "detach-shape"
                   :file-id file-id
                   :component-ref-file (get-component-ref-file objects shape)
                   ::l/sync? true)
          (cond-> shape
            (not= file-id (:fill-color-ref-file shape))
            (dissoc :fill-color-ref-id :fill-color-ref-file)

            (not= file-id (:stroke-color-ref-file shape))
            (dissoc :stroke-color-ref-id :stroke-color-ref-file)

            (not= file-id (get-component-ref-file objects shape))
            (dissoc :component-id :component-file :shape-ref :component-root)

            (= :text (:type shape))
            (update :content detach-text)))

        detach-objects
        (fn [objects]
          (update-vals objects #(detach-shape objects %)))

        detach-pages
        (fn [pages-index]
          (update-vals pages-index #(update % :objects detach-objects)))]

    (-> file
        (update-in [:data :pages-index] detach-pages))))

;; Base font size

(defn get-base-font-size
  "Retrieve the base font size value or token reference."
  [file-data]
  (get-in file-data [:options :base-font-size] BASE-FONT-SIZE))

(defn set-base-font-size
  [file-data base-font-size]
  (assoc-in file-data [:options :base-font-size] base-font-size))

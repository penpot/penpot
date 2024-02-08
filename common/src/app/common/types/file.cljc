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
   [app.common.text :as ct]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMA
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(sm/define! ::media-object
  [:map {:title "FileMediaObject"}
   [:id ::sm/uuid]
   [:name :string]
   [:width ::sm/safe-int]
   [:height ::sm/safe-int]
   [:mtype :string]
   [:path {:optional true} [:maybe :string]]])

(sm/define! ::data
  [:map {:title "FileData"}
   [:pages [:vector ::sm/uuid]]
   [:pages-index
    [:map-of {:gen/max 5} ::sm/uuid ::ctp/page]]
   [:colors {:optional true}
    [:map-of {:gen/max 5} ::sm/uuid ::ctc/color]]
   [:components {:optional true}
    [:map-of {:gen/max 5} ::sm/uuid ::ctn/container]]
   [:recent-colors {:optional true}
    [:vector {:gen/max 3} ::ctc/recent-color]]
   [:typographies {:optional true}
    [:map-of {:gen/max 2} ::sm/uuid ::cty/typography]]
   [:media {:optional true}
    [:map-of {:gen/max 5} ::sm/uuid ::media-object]]])

(def check-file-data!
  (sm/check-fn ::data))

(def check-media-object!
  (sm/check-fn ::media-object))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; INITIALIZATION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def empty-file-data
  {:version version
   :pages []
   :pages-index {}})

(defn make-file-data
  ([file-id]
   (make-file-data file-id (uuid/next)))

  ([file-id page-id]
   (let [page (when (some? page-id)
                (ctp/make-empty-page page-id "Page 1"))]

     (cond-> (assoc empty-file-data :id file-id :version version)
       (some? page-id)
       (ctpl/add-page page)

       (contains? cfeat/*current* "components/v2")
       (assoc-in [:options :components-v2] true)))))

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

(defn update-container
  "Update a container inside the file, it can be a page or a component"
  [file-data container f]
  (if (ctn/page? container)
    (ctpl/update-page file-data (:id container) f)
    (ctkl/update-component file-data (:id container) f)))

;; Asset helpers

(defn find-component
  "Retrieve a component from libraries, iterating over all of them."
  [libraries component-id & {:keys [include-deleted?] :or {include-deleted? false}}]
  (some #(ctkl/get-component (:data %) component-id include-deleted?) (vals libraries)))

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
  "Retrieve the container that holds the component shapes (the page in components-v2
   or the component itself in v1)"
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2 (not (:deleted component)))
      (let [component-page (get-component-page file-data component)]
        (cfh/make-container component-page :page))
      (cfh/make-container component :component))))

(defn get-component-root
  "Retrieve the root shape of the component."
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2 (not (:deleted component)))
      (-> file-data
          (get-component-page component)
          (ctn/get-shape (:main-instance-id component)))
      (ctk/get-component-root component))))

(defn get-component-shape
  "Retrieve one shape in the component by id."
  [file-data component shape-id]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2 (not (:deleted component)))
      (let [component-page (get-component-page file-data component)]
        (when component-page
          (cfh/get-child (:objects component-page)
                         (:main-instance-id component)
                         shape-id)))
      (dm/get-in component [:objects shape-id]))))

(defn get-ref-shape
  "Retrieve the shape in the component that is referenced by the instance shape."
  [file-data component shape]
  (when (:shape-ref shape)
    (get-component-shape file-data component (:shape-ref shape))))

(defn get-shape-in-copy
  "Given a shape in the main component and the root of the copy component returns the equivalent
  shape inside the root copy that matches the main-shape"
  [file-data main-shape root-copy]
  (->> (gsts/get-children-seq (:id root-copy) (:objects file-data))
       (d/seek #(= (:shape-ref %) (:id main-shape)))))

(defn find-ref-shape
  "Locate the near component in the local file or libraries, and retrieve the shape
   referenced by the instance shape."
  [file page libraries shape & {:keys [include-deleted?] :or {include-deleted? false}}]
  (let [root-shape     (ctn/get-copy-root (:objects page) shape)
        component-file (when root-shape
                         (if (and (some? file) (= (:component-file root-shape) (:id file)))
                           file
                           (get libraries (:component-file root-shape))))
        component      (when component-file
                         (ctkl/get-component (:data component-file) (:component-id root-shape) include-deleted?))
        ref-shape (when component
                    (get-ref-shape (:data component-file) component shape))]

    (if (some? ref-shape)  ; There is a case when we have a nested orphan copy. In this case there is no near
      ref-shape            ; component for this copy, so shape-ref points to the remote main.
      (let [head-shape     (ctn/get-head-shape (:objects page) shape)
            head-file (if (and (some? file) (= (:component-file head-shape) (:id file)))
                        file
                        (get libraries (:component-file head-shape)))
            head-component      (when (some? head-file)
                                  (ctkl/get-component (:data head-file) (:component-id head-shape) include-deleted?))]
        (when (some? head-component)
          (get-ref-shape (:data head-file) head-component shape))))))

(defn find-remote-shape
  "Recursively go back by the :shape-ref of the shape until find the correct shape of the original component"
  [container libraries shape]
  (let [top-instance        (ctn/get-component-shape (:objects container) shape)
        component-file      (get-in libraries [(:component-file top-instance) :data])
        component           (ctkl/get-component component-file (:component-id top-instance) true)
        remote-shape        (get-ref-shape component-file component shape)
        component-container (get-component-container component-file component)]
    (if (nil? remote-shape)
      shape
      (find-remote-shape component-container libraries remote-shape))))

(defn get-component-shapes
  "Retrieve all shapes of the component"
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2
             (not (:deleted component))) ;; the deleted components have its children in the :objects property
      (let [instance-page (get-component-page file-data component)]
        (cfh/get-children-with-self (:objects instance-page) (:main-instance-id component)))
      (vals (:objects component)))))

;; Return true if the object is a component that exists on the file or its libraries (even a deleted one)
(defn is-known-component?
  [shape libraries]
  (let [main-instance?  (ctk/main-instance? shape)
        component-id    (:component-id shape)
        file-id         (:component-file shape)
        component       (ctkl/get-component (dm/get-in libraries [file-id :data]) component-id true)]
    (and main-instance?
         component)))

(defn load-component-objects
  "Add an :objects property to the component, with only the shapes that belong to it"
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2 component (empty? (:objects component))) ;; This operation may be called twice, e.g. in an idempotent change
      (let [component-page (get-component-page file-data component)
            page-objects   (:objects component-page)
            objects        (->> (cons (:main-instance-id component)
                                      (cfh/get-children-ids page-objects (:main-instance-id component)))
                                (map #(get page-objects %))
                                (d/index-by :id))]
        (assoc component :objects objects))
      component)))

(defn delete-component
  "Mark a component as deleted and store the main instance shapes iside it, to
  be able to be recovered later."
  [file-data component-id skip-undelete? main-instance]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (or (not components-v2) skip-undelete?)
      (ctkl/delete-component file-data component-id)
      (let [set-main-instance ;; If there is a saved main-instance, restore it. This happens on the restore-component action
            #(if main-instance
               (assoc-in % [:objects (:main-instance-id %)] main-instance)
               %)]
        (-> file-data
            (ctkl/update-component component-id (partial load-component-objects file-data))
            (ctkl/update-component component-id set-main-instance)
            (ctkl/mark-component-deleted component-id))))))

(defn restore-component
  "Recover a deleted component and all its shapes and put all this again in place."
  [file-data component-id page-id]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])
        update-page? (and components-v2 (not (nil? page-id)))]
    (-> file-data
        (ctkl/update-component component-id #(dissoc % :objects))
        (ctkl/mark-component-undeleted component-id)
        (cond-> update-page?
          (ctkl/update-component component-id #(assoc % :main-instance-page page-id))))))

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
  (ctc/uses-library-color? shape library-id (:id color)))

(defmethod uses-asset? :typography
  [_ shape library-id typography]
  (cty/uses-library-typography? shape library-id (:id typography)))

(defn find-asset-type-usages
  "Find all usages of an asset in a file (may be in pages or in the components
  of the local library).

  Returns a list ((asset ((container shapes) (container shapes)...))...)"
  [file-data library-data asset-type]
  (let [assets-seq (case asset-type
                     :component (ctkl/components-seq library-data)
                     :color (ctcl/colors-seq library-data)
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
            assets-seq)))

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
             (ctcl/used-colors-changed-since shape library since-date)
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
      (let [library-page (ctp/make-empty-page (uuid/next) "Main components")]
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
                                             (dm/get-in file-data [:options :components-v2])
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
                                       :main-instance-page page-id
                                       :shapes (get-component-shapes library-data component)}))

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

(defn- absorb-colors
  [file-data used-colors]
  (let [absorb-color
        (fn [file-data [color usages]]
          (let [remap-shape #(ctc/remap-colors % (:id file-data) color)

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
              (ctcl/add-color $ color)
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
  (let [used-components (find-asset-type-usages file-data library-data :component)
        used-colors (find-asset-type-usages file-data library-data :color)
        used-typographies (find-asset-type-usages file-data library-data :typography)]

    (cond-> file-data
      (d/not-empty? used-components)
      (absorb-components used-components library-data)

      (d/not-empty? used-colors)
      (absorb-colors used-colors)

      (d/not-empty? used-typographies)
      (absorb-typographies used-typographies))))

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

                  (when (and (:component-file shape) component-file)
                    (str/format "<%s> "
                                (if (= (:id component-file) (:id file))
                                  "local"
                                  (:name component-file))))

                  (or (:name component-shape)
                      (str/format "?%s"
                                  (when show-ids
                                    (str " " (:shape-ref shape)))))

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
    (let [root (ctk/get-component-root component)]
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
               (ct/transform-nodes
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

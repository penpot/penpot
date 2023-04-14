;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.file
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.features :as ffeat]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.pages.common :refer [file-version]]
   [app.common.pages.helpers :as cph]
   [app.common.types.color :as ctc]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.component :as ctk]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.file.media-object :as ctfm]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
   [app.common.types.shape-tree :as ctst]
   [app.common.types.typographies-list :as ctyl]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

;; Specs

(s/def ::colors
  (s/map-of uuid? ::ctc/color))

(s/def ::recent-colors
  (s/coll-of ::ctc/recent-color :kind vector?))

(s/def ::typographies
  (s/map-of uuid? ::cty/typography))

(s/def ::pages
  (s/coll-of uuid? :kind vector?))

(s/def ::media
  (s/map-of uuid? ::ctfm/media-object))

(s/def ::pages-index
  (s/map-of uuid? ::ctp/page))

(s/def ::components
  (s/map-of uuid? ::ctn/container))

(s/def ::data
  (s/keys :req-un [::pages-index
                   ::pages]
          :opt-un [::colors
                   ::components
                   ::recent-colors
                   ::typographies
                   ::media]))

;; Initialization

(def empty-file-data
  {:version file-version
   :pages []
   :pages-index {}})

(defn make-file-data
  ([file-id]
   (make-file-data file-id (uuid/next)))

  ([file-id page-id]
   (let [page (when (some? page-id)
                (ctp/make-empty-page page-id "Page 1"))]
     (cond-> (-> empty-file-data
                 (assoc :id file-id))

       (some? page-id)
       (ctpl/add-page page)

       (contains? ffeat/*current* "components/v2")
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

(defn get-component
  "Retrieve a component from libraries, if no library-id is provided, we
  iterate over all libraries and find the component on it."
  ([libraries component-id]
   (some #(ctkl/get-component (:data %) component-id) (vals libraries)))
  ([libraries library-id component-id]
   (ctkl/get-component (dm/get-in libraries [library-id :data]) component-id)))

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
        (cph/make-container component-page :page))
      (cph/make-container component :component))))

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
        (ctn/get-shape component-page shape-id))
      (dm/get-in component [:objects shape-id]))))

(defn get-ref-shape
  "Retrieve the shape in the component that is referenced by the
  instance shape."
  [file-data component shape]
  (when (:shape-ref shape)
    (get-component-shape file-data component (:shape-ref shape))))

(defn get-component-shapes
  "Retrieve all shapes of the component"
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if components-v2
      (let [instance-page (get-component-page file-data component)]
        (cph/get-children-with-self (:objects instance-page) (:main-instance-id component)))
      (vals (:objects component)))))

(defn load-component-objects
  "Add an :objects property to the component, with only the shapes that belong to it"
  [file-data component]
  (let [components-v2 (dm/get-in file-data [:options :components-v2])]
    (if (and components-v2 component (nil? (:objects component))) ;; This operation may be called twice, e.g. in an idempotent change
      (let [component-page (get-component-page file-data component)
            page-objects   (:objects component-page)
            objects        (->> (cons (:main-instance-id component)
                                      (cph/get-children-ids page-objects (:main-instance-id component)))
                                (map #(get page-objects %))
                                (d/index-by :id))]
        (assoc component :objects objects))
      component)))

(defn delete-component
  "Mark a component as deleted and store the main instance shapes iside it, to
  be able to be recovered later."
  ([file-data component-id]
   (delete-component file-data component-id false))

  ([file-data component-id skip-undelete?]
   (let [components-v2 (dm/get-in file-data [:options :components-v2])]
     (if (or (not components-v2) skip-undelete?)
       (ctkl/delete-component file-data component-id)
       (-> file-data
           (ctkl/update-component component-id (partial load-component-objects file-data))
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

(defn get-or-add-library-page
  "If exists a page named 'Library backup', get the id and calculate the position to start
  adding new components. If not, create it and start at (0, 0)."
  [file-data grid-gap]
  (let [library-page (d/seek #(= (:name %) "Library backup") (ctpl/pages-seq file-data))]
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
      (let [library-page (ctp/make-empty-page (uuid/next) "Library backup")]
        [(ctpl/add-page file-data library-page) (:id library-page) (gpt/point 0 0)]))))

(defn migrate-to-components-v2
  "If there is any component in the file library, add a new 'Library backup', generate
  main instances for all components there and remove shapes from library components.
  Mark the file with the :components-v2 option."
  [file-data]
  (let [components (ctkl/components-seq file-data)]
    (if (or (empty? components)
            (dm/get-in file-data [:options :components-v2]))
      (assoc-in file-data [:options :components-v2] true)
      (let [grid-gap 50

            [file-data page-id start-pos]
            (get-or-add-library-page file-data grid-gap)

            add-main-instance
            (fn [file-data component position]
              (let [page (ctpl/get-page file-data page-id)

                    [new-shape new-shapes]
                    (ctn/make-component-instance page
                                                 component
                                                 file-data
                                                 position
                                                 false
                                                 {:main-instance? true})

                    add-shapes
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
                              new-shapes))

                    update-component
                    (fn [component]
                      (-> component
                          (assoc :main-instance-id (:id new-shape)
                                 :main-instance-page page-id)
                          (dissoc :objects)))]

                (-> file-data
                    (ctpl/update-page page-id add-shapes)
                    (ctkl/update-component (:id component) update-component))))

            add-instance-grid
            (fn [file-data components]
              (let [position-seq (ctst/generate-shape-grid
                                  (map (partial get-component-root file-data) components)
                                  start-pos
                                  grid-gap)]
                (loop [file-data      file-data
                       components-seq (seq components)
                       position-seq   position-seq]
                  (let [component (first components-seq)
                        position  (first position-seq)]
                    (if (nil? component)
                      file-data
                      (recur (add-main-instance file-data component position)
                             (rest components-seq)
                             (rest position-seq)))))))
            
            root-to-board
            (fn [shape]
              (cond-> shape
                (and (ctk/instance-root? shape)
                     (not= (:type shape) :frame))
                (assoc :type :frame
                       :fills []
                       :hide-in-viewer true
                       :rx 0
                       :ry 0)))

            roots-to-board
            (fn [page]
              (update page :objects update-vals root-to-board))]

        (-> file-data
            (add-instance-grid (sort-by :name components))
            (update :pages-index update-vals roots-to-board)
            (assoc-in [:options :components-v2] true))))))

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
                                             {:main-instance? true})

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

(defn dump-tree
  ([file-data page-id libraries]
   (dump-tree file-data page-id libraries false false))

  ([file-data page-id libraries show-ids]
   (dump-tree file-data page-id libraries show-ids false))

  ([file-data page-id libraries show-ids show-touched]
   (let [page       (ctpl/get-page file-data page-id)
         objects    (:objects page)
         components (ctkl/components file-data)
         root       (d/seek #(nil? (:parent-id %)) (vals objects))]

     (letfn [(show-shape [shape-id level objects]
                         (let [shape (get objects shape-id)]
                           (println (str/pad (str (str/repeat "  " level)
                                                  (when (:main-instance? shape) "{")
                                                  (:name shape)
                                                  (when (:main-instance? shape) "}")
                                                  (when (seq (:touched shape)) "*")
                                                  (when show-ids (str/format " <%s>" (:id shape))))
                                             {:length 20
                                              :type :right})
                                    (show-component-info shape objects))
                           (when show-touched
                             (when (seq (:touched shape))
                               (println (str (str/repeat "  " level)
                                             "    "
                                             (str (:touched shape)))))
                             (when (:remote-synced? shape)
                               (println (str (str/repeat "  " level)
                                             "    (remote-synced)"))))
                           (when (:shapes shape)
                             (dorun (for [shape-id (:shapes shape)]
                                      (show-shape shape-id (inc level) objects))))))

             (show-component-info [shape objects]
                                  (if (nil? (:shape-ref shape))
                                    (if (:component-root? shape) " #" "")
                                    (let [root-shape        (ctn/get-component-shape objects shape)
                                          component-id      (when root-shape (:component-id root-shape))
                                          component-file-id (when root-shape (:component-file root-shape))
                                          component-file    (when component-file-id (get libraries component-file-id nil))
                                          component         (when component-id
                                                              (if component-file
                                                                (ctkl/get-component (:data component-file) component-id)
                                                                (get components component-id)))
                                          component-shape   (when component
                                                              (if component-file
                                                                (get-ref-shape (:data component-file) component shape)
                                                                (get-ref-shape file-data component shape)))]

                                      (str/format " %s--> %s%s%s"
                                                  (cond (:component-root? shape) "#"
                                                        (:component-id shape) "@"
                                                        :else "-")
                                                  (when component-file (str/format "<%s> " (:name component-file)))
                                                  (or (:name component-shape) "?")
                                                  (if (or (:component-root? shape)
                                                          (nil? (:component-id shape))
                                                          true)
                                                    ""
                                                    (let [component-id      (:component-id shape)
                                                          component-file-id (:component-file shape)
                                                          component-file    (when component-file-id (get libraries component-file-id nil))
                                                          component         (if component-file
                                                                              (ctkl/get-component (:data component-file) component-id)
                                                                              (get components component-id))]
                                                      (str/format " (%s%s)"
                                                                  (when component-file (str/format "<%s> " (:name component-file)))
                                                                  (:name component))))))))

             (show-component-instance [component]
               (let [page (get-component-page file-data component)
                     root (get-component-root file-data component)]
                 (if-not show-ids
                   (println (str "  [" (:name page) "] / " (:name root)))
                   (do
                     (println (str "  " (:name page) (str/format " <%s>" (:id page))))
                     (println (str "  " (:name root) (str/format " <%s>" (:id root))))))))]

       (println (str "[Page: " (:name page) "]"))
       (show-shape (:id root) 0 objects)

       (dorun (for [component (vals components)]
                (do
                  (println)
                  (println (str/format "[%s]" (:name component)))
                  (when (:objects component)
                    (show-shape (:id component) 0 (:objects component)))
                  (when (:main-instance-page component)
                    (show-component-instance component)))))))))


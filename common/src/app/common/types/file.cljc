;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.file
  (:require
    [app.common.data :as d]
    [app.common.geom.point :as gpt]
    [app.common.geom.shapes :as gsh]
    [app.common.pages.common :refer [file-version]]
    [app.common.pages.helpers :as cph]
    [app.common.spec :as us]
    [app.common.types.color :as ctc]
    [app.common.types.colors-list :as ctcl]
    [app.common.types.component :as ctk]
    [app.common.types.components-list :as ctkl]
    [app.common.types.container :as ctn]
    [app.common.types.page :as ctp]
    [app.common.types.pages-list :as ctpl]
    [app.common.types.shape-tree :as ctst]
    [app.common.types.typography :as cty]
    [app.common.types.typographies-list :as ctyl]
    [app.common.uuid :as uuid]
    [clojure.spec.alpha :as s]
    [cuerdas.core :as str]))

;; Specs

(s/def :internal.media-object/name string?)
(s/def :internal.media-object/width ::us/safe-integer)
(s/def :internal.media-object/height ::us/safe-integer)
(s/def :internal.media-object/mtype string?)

;; NOTE: This is marked as nilable for backward compatibility, but
;; right now is just exists or not exists. We can thin in a gradual
;; migration and then mark it as not nilable.
(s/def :internal.media-object/path (s/nilable string?))

(s/def ::media-object
  (s/keys :req-un [::id
                   ::name
                   :internal.media-object/width
                   :internal.media-object/height
                   :internal.media-object/mtype]
          :opt-un [:internal.media-object/path]))

(s/def ::colors
  (s/map-of uuid? ::ctc/color))

(s/def ::recent-colors
  (s/coll-of ::ctc/recent-color :kind vector?))

(s/def ::typographies
  (s/map-of uuid? :ctst/typography))

(s/def ::pages
  (s/coll-of uuid? :kind vector?))

(s/def ::media
  (s/map-of uuid? ::media-object))

(s/def ::pages-index
  (s/map-of uuid? ::ctp/page))

(s/def ::components
  (s/map-of uuid? ::ctp/container))

(s/def ::data
  (s/keys :req-un [::pages-index
                   ::pages]
          :opt-un [::colors
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
   (let [page (ctp/make-empty-page page-id "Page-1")]
     (-> empty-file-data
         (assoc :id file-id)
         (ctpl/add-page page)))))

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
        (fn [file-data library-id asset-type asset]
          (mapcat #(find-usages-in-container % asset) (containers-seq file-data)))]

    (mapcat (fn [asset]
              (let [instances (find-asset-usages file-data (:id library-data) asset-type asset)]
                (when (d/not-empty? instances)
                  [[asset instances]])))
            assets-seq)))

(defn get-or-add-library-page
  [file-data grid-gap]
  "If exists a page named 'Library page', get the id and calculate the position to start
  adding new components. If not, create it and start at (0, 0)."
  (let [library-page (d/seek #(= (:name %) "Library page") (ctpl/pages-seq file-data))]
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
      (let [library-page (ctp/make-empty-page (uuid/next) "Library page")]
        [(ctpl/add-page file-data library-page) (:id library-page) (gpt/point 0 0)]))))

(defn- absorb-components
  [file-data library-data used-components]
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
                                             (:id file-data)
                                             position
                                             true)

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
                                      (:id component)
                                      (:name component)
                                      (:path component)
                                      (:id main-instance-shape)
                                      page-id
                                      (vals (:objects component))))

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
                               (map #(ctk/get-component-root (first %)) used-components)
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
  [file-data library-data used-colors]
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
  [file-data library-data used-typographies]
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
      (absorb-components library-data used-components)

      (d/not-empty? used-colors)
      (absorb-colors library-data used-colors)

      (d/not-empty? used-typographies)
      (absorb-typographies library-data used-typographies))))


;; Debug helpers

(defn dump-tree
  ([file-data page-id libraries]
   (dump-tree file-data page-id libraries false false))

  ([file-data page-id libraries show-ids]
   (dump-tree file-data page-id libraries show-ids false))

  ([file-data page-id libraries show-ids show-touched]
   (let [page       (ctpl/get-page file-data page-id)
         objects    (:objects page)
         components (:components file-data)
         root       (d/seek #(nil? (:parent-id %)) (vals objects))]

     (letfn [(show-shape [shape-id level objects]
               (let [shape (get objects shape-id)]
                 (println (str/pad (str (str/repeat "  " level)
                                        (:name shape)
                                        (when (seq (:touched shape)) "*")
                                        (when show-ids (str/format " <%s>" (:id shape))))
                                   {:length 20
                                    :type :right})
                          (show-component shape objects))
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

             (show-component [shape objects]
               (if (nil? (:shape-ref shape))
                 ""
                 (let [root-shape        (cph/get-component-shape objects shape)
                       component-id      (when root-shape (:component-id root-shape))
                       component-file-id (when root-shape (:component-file root-shape))
                       component-file    (when component-file-id (get libraries component-file-id nil))
                       component         (when component-id
                                           (if component-file
                                             (get-in component-file [:data :components component-id])
                                             (get components component-id)))
                       component-shape   (when (and component (:shape-ref shape))
                                           (get-in component [:objects (:shape-ref shape)]))]
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
                                                           (get-in component-file [:data :components component-id])
                                                           (get components component-id))]
                                   (str/format " (%s%s)"
                                               (when component-file (str/format "<%s> " (:name component-file)))
                                               (:name component))))))))]

       (println "[Page]")
       (show-shape (:id root) 0 objects)

       (dorun (for [component (vals components)]
                (do
                  (println)
                  (println (str/format "[%s]" (:name component))
                           (when show-ids
                             (str/format " (main: %s/%s)"
                                         (:main-instance-page component)
                                         (:main-instance-id component))))
                  (show-shape (:id component) 0 (:objects component)))))))))


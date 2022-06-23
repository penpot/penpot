;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.file
  (:require
   [app.common.data :as d]
   [app.common.pages.common :refer [file-version]]
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.common.types.color :as ctc]
   [app.common.types.components-list :as ctkl]
   [app.common.types.container :as ctn]
   [app.common.types.page :as ctp]
   [app.common.types.pages-list :as ctpl]
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

(defn update-file-data
  [file f]
  (update file :data f))

(defn containers-seq
  "Generate a sequence of all pages and all components, wrapped as containers"
  [file-data]
  (concat (map #(ctn/make-container % :page) (ctpl/pages-seq file-data))
          (map #(ctn/make-container % :component) (ctkl/components-seq file-data))))

(defn absorb-assets
  "Find all assets of a library that are used in the file, and
  move them to the file local library."
  [file-data library-data]
  (let [library-page-id (uuid/next)

        add-library-page
        (fn [file-data]
          (let [page (ctp/make-empty-page library-page-id "Library page")]
            (-> file-data
                (ctpl/add-page page))))

        find-instances-in-container
        (fn [container component]
          (let [instances (filter #(= (:component-id %) (:id component))
                                  (ctn/shapes-seq container))]
            (when (d/not-empty? instances)
              [[container instances]])))

        find-instances
        (fn [file-data component]
          (mapcat #(find-instances-in-container % component) (containers-seq file-data)))

        absorb-component
        (fn [file-data _component]
          ;; TODO: complete this
          file-data)

        used-components
        (mapcat (fn [component]
                  (let [instances (find-instances file-data component)]
                    (when instances
                      [[component instances]])))
                (ctkl/components-seq library-data))]

    (if (empty? used-components)
      file-data
      (as-> file-data $
        (add-library-page $)
        (reduce absorb-component
                $
                used-components)))))

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


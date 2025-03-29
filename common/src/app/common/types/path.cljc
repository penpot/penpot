;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.path
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.data :as d]
   [app.common.files.helpers :as cpf]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.svg.path :as svg.path]
   [app.common.transit :as t]
   [app.common.types.path.impl :as impl]
   [app.common.types.path.bool :as bool]
   [app.common.types.path.segment :as segment]
   [app.common.types.path.shape-to-path :as stp]
   [app.common.types.path.subpath :as subpath])
  (:import
   #?(:cljs [goog.string StringBuffer]
      :clj  [java.nio ByteBuffer])))

#?(:clj (set! *warn-on-reflection* true))

(defn path-data?
  [o]
  (impl/path-data? o))

(defn from-string
  [s]
  (impl/from-string s))

(defn content
  "Create an instance of PathData, returns itself if it is already
  PathData instance"
  [data]
  (cond
    (instance? PathData data)
    data

    (nil? data)
    (impl/from-plain [])

    (vector? data)
    (impl/from-plain data)

    :else
    (impl/from-bytes data)))

(def schema:content-like
  [:sequential impl/schema:path-segment])

(def check-content-like
  (sm/check-fn schema:content-like))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn apply-content-modifiers
  "Apply to content a map with point translations"
  [content modifiers]
  (assert (check-content-like content))

  (letfn [(apply-to-index [content [index params]]
            (if (contains? content index)
              (cond-> content
                (and
                 (or (:c1x params) (:c1y params) (:c2x params) (:c2y params))
                 (= :line-to (get-in content [index :command])))

                (-> (assoc-in [index :command] :curve-to)
                    (assoc-in [index :params]
                              (segment/make-curve-params
                               (get-in content [index :params])
                               (get-in content [(dec index) :params]))))

                (:x params) (update-in [index :params :x] + (:x params))
                (:y params) (update-in [index :params :y] + (:y params))

                (:c1x params) (update-in [index :params :c1x] + (:c1x params))
                (:c1y params) (update-in [index :params :c1y] + (:c1y params))

                (:c2x params) (update-in [index :params :c2x] + (:c2x params))
                (:c2y params) (update-in [index :params :c2y] + (:c2y params)))
              content))]
    (let [content (if (vector? content) content (into [] content))]
      (reduce apply-to-index content modifiers))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; FIXME: rename?
(defn calc-bool-content
  [shape objects]

  (let [extract-content-xf
        (comp (map (d/getf objects))
              (filter (comp not :hidden))
              (remove cpf/svg-raw-shape?)
              (map #(stp/convert-to-path % objects))
              (map :content))

        shapes-content
        (into [] extract-content-xf (:shapes shape))]

    (bool/content-bool (:bool-type shape) shapes-content)))

(defn shape-with-open-path?
  [shape]
  (let [svg? (contains? shape :svg-attrs)
        ;; No close subpaths for svgs imported
        maybe-close (if svg? identity subpath/close-subpaths)]
    (and (= :path (:type shape))
         (not (->> shape
                   :content
                   (maybe-close)
                   (subpath/get-subpaths)
                   (every? subpath/is-closed?))))))


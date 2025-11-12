;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.fills
  (:refer-clojure :exclude [assoc update])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.flags :as flags]
   [app.common.schema :as sm]
   [app.common.types.color :as types.color]
   [app.common.types.fills.impl :as impl]
   [clojure.core :as c]
   [clojure.set :as set]))

(def ^:const MAX-GRADIENT-STOPS impl/MAX-GRADIENT-STOPS)
(def ^:const MAX-FILLS impl/MAX-FILLS)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SCHEMAS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def schema:fill-attrs
  [:map {:title "FillAttrs" :closed true}
   [:fill-color-ref-file {:optional true} ::sm/uuid]
   [:fill-color-ref-id {:optional true} ::sm/uuid]
   [:fill-opacity {:optional true} [::sm/number {:min 0 :max 1}]]
   [:fill-color {:optional true} types.color/schema:hex-color]
   [:fill-color-gradient {:optional true} types.color/schema:gradient]
   [:fill-image {:optional true} types.color/schema:image]])

(def fill-attrs
  "A set of attrs that corresponds to fill data type"
  (sm/keys schema:fill-attrs))

(def valid-fill-attrs
  "A set used for proper check if color should contain only one of the
  attrs listed in this set."
  #{:fill-image :fill-color :fill-color-gradient})

(defn has-valid-fill-attrs?
  "Check if color has correct color attrs"
  [color]
  (let [attrs  (set (keys color))
        result (set/intersection attrs valid-fill-attrs)]
    (= 1 (count result))))

(def schema:fill
  [:and schema:fill-attrs
   [:fn has-valid-fill-attrs?]])

(def check-fill
  (sm/check-fn schema:fill))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONSTRUCTORS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn from-plain
  [o]
  (assert (every? check-fill o) "expected valid fills vector")
  (impl/from-plain o))

(defn fills?
  [o]
  (impl/fills? o))

(defn coerce
  [o]
  (cond
    (nil? o)
    (impl/from-plain [])

    (impl/fills? o)
    o

    (vector? o)
    (impl/from-plain o)

    :else
    (ex/raise :type :internal
              :code :invalid-type
              :hint (str "cannot coerce " (pr-str o) " to fills"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TYPE HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-image-ids
  [fills]
  (if (vector? fills)
    (into #{}
          (comp (keep :fill-image)
                (map :id))
          fills)
    (impl/-get-image-ids fills)))

(defn get-byte-size
  [fills]
  (impl/-get-byte-size fills))

(defn write-to
  [fills buffer offset]
  (impl/-write-to fills buffer offset))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRANSFORMATION & CREATION HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc
  [fills position fill]
  (if (contains? flags/*current* :frontend-binary-fills)
    (if (nil? fills)
      (impl/from-plain [fill])
      (-> (coerce fills)
          (c/assoc position fill)))
    (if (nil? fills)
      [fill]
      (-> fills
          (c/assoc position fill)))))

(defn update
  [fills f & args]
  (let [fills (vec fills)
        fills (apply f fills args)]
    (if (contains? flags/*current* :frontend-binary-fills)
      (impl/from-plain fills)
      (vec fills))))

(defn create
  [& elements]
  (let [fills (vec elements)]
    (if (contains? flags/*current* :frontend-binary-fills)
      (impl/from-plain fills)
      fills)))

(defn prepend
  "Prepend a fill to existing fills"
  [fills fill]
  (let [fills (into [fill] fills)]
    (if (contains? flags/*current* :frontend-binary-fills)
      (impl/from-plain fills)
      fills)))

(defn fill->color
  [fill]
  (d/without-nils
   {:color (:fill-color fill)
    :opacity (:fill-opacity fill)
    :gradient (:fill-color-gradient fill)
    :image (:fill-image fill)
    :ref-id (:fill-color-ref-id fill)
    :ref-file (:fill-color-ref-file fill)}))

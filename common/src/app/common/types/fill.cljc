;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.fill
  (:require
   [app.common.schema :as sm]
   [app.common.types.color :as types.color]
   [app.common.types.fill.impl :as impl]
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
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

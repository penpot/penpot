;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.features
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.schema.desc-js-like :as-alias smdj]
   [app.common.schema.generators :as smg]
   [clojure.set :as set]
   [cuerdas.core :as str]))

;; A set of enabled by default file features. Will be used in feature
;; negotiation on obtaining files from backend.

;; FIXME: don't know if is used or not
(def ^:dynamic *enabled* #{})

(def ^:dynamic *previous* #{})
(def ^:dynamic *current* #{})
(def ^:dynamic *wrap-with-objects-map-fn* identity)
(def ^:dynamic *wrap-with-pointer-map-fn* identity)

(def supported-features
  #{"storage/objects-map"
    "storage/pointer-map"
    "internal/shape-record"
    "internal/geom-record"
    "components/v2"
    "styles/v2"
    "layout/grid"})

;; A set of features enabled by default for each file
(def default-enabled-features
  #{"internal/shape-record"
    "internal/geom-record"})

;; Features that are mainly backend only or there are a proper
;; fallback when frontend reports no support for it
(def backend-only-features
  #{"storage/objects-map"
    "storage/pointer-map"})

;; Features that does not require any file data migrations and/or can
;; be enabled and disabled safelly
(def no-migration-features
  #{"styles/v2"})

(sm/def! ::features
  [:schema
   {:title "FileFeatures"
    ::smdj/inline true
    :gen/gen (smg/subseq supported-features)}
   ::sm/set-of-strings])

(defn- flag->feature
  [feature]
  (case feature
    :feature-components-v2 "components/v2"
    :feature-new-css-system "styles/v2"
    :feature-grid-layout "layout/grid"
    :feature-storage-object-map "storage/objects-map"
    :feature-storage-pointer-map "storage/pointer-map"
    nil))

(defn get-enabled-features
  ([flags]
   (get-enabled-features flags nil))
  ([flags team]
   (cond-> default-enabled-features

     ;; TEMPORAL BACKWARD COMPATIBILITY
     (contains? flags :fdata-storage-pointer-map)
     (conj "storage/pointer-map")

     (contains? flags :fdata-storage-objects-map)
     (conj "storage/objects-map")

     ;; add all team enabled features to the set
     :always
     (into (:features team #{}))

     ;; add globally enabled features to the set
     :always
     (into (keep flag->feature) flags))))

(defn check-client-features!
  "Function used for check feature compability between currently
  enabled features set on backend with the provided featured set by
  the frontend client"
  [features client-features]
  (when (some? client-features)
    (let [not-supported (-> features
                            (set/difference client-features)
                            (set/difference backend-only-features))]
      (when (seq not-supported)
        (ex/raise :type :restriction
                  :code :features-not-supported
                  :feature (first not-supported)
                  :hint (str/ffmt "client declares no support for '%' features"
                                  (str/join "," not-supported)))))

    (let [not-supported (set/difference client-features supported-features)]
      (when (seq not-supported)
        (ex/raise :type :restriction
                  :code :features-not-supported
                  :feature (first not-supported)
                  :hint (str/ffmt "backend does not support '%' features requested by client"
                                  (str/join "," not-supported))))))
  features)

(defn check-file-features!
  "Function used for check feature compability between currently
  enabled features set on backend with the provided featured set by
  the penpot file"
  [features file-features]
  (let [not-supported (-> features
                          (set/difference file-features)
                          ;; NOTE: we don't want to raise a feature-mismatch
                          ;; exception for features which don't require an
                          ;; explicit file migration process or has no real
                          ;; effect on file data structure
                          (set/difference no-migration-features))]
    (when (seq not-supported)
      (ex/raise :type :restriction
                 :code :features-mismatch
                 :feature (first not-supported)
                 :hint (str/ffmt "enabled features '%' not present in file (missing migration?)"
                                 (str/join "," not-supported)))))

  (let [not-supported (set/difference file-features supported-features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :features-mismatch
                :feature (first not-supported)
                :hint (str/ffmt "file features '%' not supported by this backend"
                                (str/join "," not-supported)))))

  (let [not-supported (set/difference file-features features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :features-mismatch
                :feature (first not-supported)
                :hint (str/ffmt "file features '%' not enabled by this backend"
                                (str/join "," not-supported)))))

  features)



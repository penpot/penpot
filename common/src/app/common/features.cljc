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

;; A set of supported features
(def supported-features
  #{"fdata/objects-map"
    "fdata/pointer-map"
    "fdata/shape-data-type"
    "components/v2"
    "styles/v2"
    "layout/grid"})

;; A set of features enabled by default for each file, they are
;; implicit and are enabled by default and can't be disabled
(def default-enabled-features
  #{"fdata/shape-data-type"})

;; A set of features which only affects on frontend and can be enabled
;; and disabled freely by the user any time. This features does not
;; persist on file features field but can be permanently enabled on
;; team feature field
(def frontend-only-features
  #{"styles/v2"
    "layout/grid"
    })

;; Features that are mainly backend only or there are a proper
;; fallback when frontend reports no support for it
(def backend-only-features
  #{"fdata/objects-map"
    "fdata/pointer-map"})

;; This is a set of features that does not require an explicit
;; migration like components/v2 or the migration is not mandatory to
;; be applied (per example backend can operate in both modes with or
;; without migration applied)
(def no-migration-features
  #{"fdata/objects-map"
    "fdata/pointer-map"
    "layout/grid"})

(def auto-enabling-features
  #{"layout/grid"})

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
    :feature-fdata-objects-map "fdata/objects-map"
    :feature-fdata-pointer-map "fdata/pointer-map"
    nil))

(defn get-enabled-features
  ([flags]
   (get-enabled-features flags nil))
  ([flags team]
   (-> default-enabled-features
       ;; add all team enabled features to the set
       (into (:features team #{}))

       ;; add globally enabled features to the set
       (into (keep flag->feature) flags))))

(defn check-client-features!
  "Function used for check feature compability between currently
  enabled features set on backend with the provided featured set by
  the frontend client"
  [enabled-features client-features]
  (when (some? client-features)
    (let [not-supported (-> enabled-features
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

  (set/union enabled-features client-features))

(defn check-file-features!
  "Function used for check feature compability between currently
  enabled features set on backend with the provided featured set by
  the penpot file"
  [enabled-features file-features]
  (let [not-supported (-> enabled-features
                          (set/difference file-features)
                          ;; NOTE: we don't want to raise a feature-mismatch
                          ;; exception for features which don't require an
                          ;; explicit file migration process or has no real
                          ;; effect on file data structure
                          (set/difference frontend-only-features)
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
                :hint (str/ffmt "file features '%' not supported"
                                (str/join "," not-supported)))))

  (let [not-supported (-> file-features
                          (set/difference enabled-features)
                          (set/difference frontend-only-features))]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :features-mismatch
                :feature (first not-supported)
                :hint (str/ffmt "file features '%' not enabled"
                                (str/join "," not-supported)))))

  enabled-features)



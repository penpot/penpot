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

;; The default behavior when a user interacts with penpot and runtime
;; and global features:
;;
;; - If user enables on runtime a frontend-only feature, this feature
;;   and creates and/or modifies files, the feature is only availble
;;   until next refresh (it is not persistent)
;;
;; - If user enables on runtime a non-migration feature, on modifying
;;   a file or creating a new one, the feature becomes persistent on
;;   the file and the team. All the other files of the team eventually
;;   will have that feature assigned (on file modification)
;;
;; - If user enables on runtime a migration feature, that feature will
;;   be ignored until a migration is explicitly executed or team
;;   explicitly marked with that feature.
;;
;; The features stored on the file works as metadata information about
;; features enabled on the file and for compatibility check when a
;; user opens the file. The features stored on global, runtime or team
;; works as activators.

(def ^:dynamic *previous* #{})
(def ^:dynamic *current* #{})
(def ^:dynamic *new* nil)

(def ^:dynamic *wrap-with-objects-map-fn* identity)
(def ^:dynamic *wrap-with-pointer-map-fn* identity)

;; A set of supported features
(def supported-features
  #{"fdata/objects-map"
    "fdata/pointer-map"
    "fdata/shape-data-type"
    "components/v2"
    "styles/v2"
    "layout/grid"
    "plugins/runtime"
    "design-tokens/v1"
    "text-editor/v2"
    "render-wasm/v1"})

;; A set of features enabled by default
(def default-features
  #{"fdata/shape-data-type"
    "styles/v2"
    "layout/grid"
    "components/v2"
    "plugins/runtime"})

;; A set of features which only affects on frontend and can be enabled
;; and disabled freely by the user any time. This features does not
;; persist on file features field but can be permanently enabled on
;; team feature field
(def frontend-only-features
  #{"styles/v2"
    "plugins/runtime"
    "text-editor/v2"
    "render-wasm/v1"})

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
  (-> #{"fdata/objects-map"
        "fdata/pointer-map"
        "layout/grid"
        "fdata/shape-data-type"
        "design-tokens/v1"}
      (into frontend-only-features)))

(sm/register!
 ^{::sm/type ::features}
 [:schema
  {:title "FileFeatures"
   ::smdj/inline true
   :gen/gen (smg/subseq supported-features)}
  [::sm/set :string]])

(defn- flag->feature
  "Translate a flag to a feature name"
  [flag]
  (case flag
    :feature-components-v2 "components/v2"
    :feature-styles-v2 "styles/v2"
    :feature-grid-layout "layout/grid"
    :feature-fdata-objects-map "fdata/objects-map"
    :feature-fdata-pointer-map "fdata/pointer-map"
    :feature-plugins "plugins/runtime"
    :feature-design-tokens "design-tokens/v1"
    :feature-text-editor-v2 "text-editor/v2"
    :feature-render-wasm "render-wasm/v1"
    nil))

(defn migrate-legacy-features
  "A helper that translates old feature names to new names"
  [features]
  (cond-> (or features #{})
    (contains? features "storage/pointer-map")
    (-> (conj "fdata/pointer-map")
        (disj "storage/pointer-map"))

    (contains? features "storage/objects-map")
    (-> (conj "fdata/objects-map")
        (disj "storage/objects-map"))

    (or (contains? features "internal/geom-record")
        (contains? features "internal/shape-record"))
    (-> (conj "fdata/shape-data-type")
        (disj "internal/geom-record")
        (disj "internal/shape-record"))))

(def xf-supported-features
  (filter (partial contains? supported-features)))

(def xf-remove-ephimeral
  (remove #(str/starts-with? % "ephimeral/")))

(def xf-flag-to-feature
  (keep flag->feature))

(defn get-enabled-features
  "Get the globally enabled features set."
  [flags]
  (into default-features xf-flag-to-feature flags))

(defn get-team-enabled-features
  "Get the team enabled features.

  Team features are defined as: all features found on team plus all
  no-migration features enabled globally."
  [flags team]
  (let [enabled-features (get-enabled-features flags)
        team-features    (into #{} xf-remove-ephimeral (:features team))]
    (-> enabled-features
        (set/intersection no-migration-features)
        (set/difference frontend-only-features)
        (set/union team-features))))

(defn check-client-features!
  "Function used for check feature compability between currently enabled
  features set on backend with the enabled featured set by the
  frontend client"
  [enabled-features client-features]
  (when (set? client-features)
    (let [not-supported (-> enabled-features
                            (set/difference client-features)
                            (set/difference frontend-only-features)
                            (set/difference backend-only-features))]
      (when (seq not-supported)
        (ex/raise :type :restriction
                  :code :feature-not-supported
                  :feature (first not-supported)
                  :hint (str/ffmt "client declares no support for '%' features"
                                  (str/join "," not-supported)))))

    (let [not-supported (set/difference client-features supported-features)]
      (when (seq not-supported)
        (ex/raise :type :restriction
                  :code :feature-not-supported
                  :feature (first not-supported)
                  :hint (str/ffmt "backend does not support '%' features requested by client"
                                  (str/join "," not-supported))))))

  enabled-features)

(defn check-supported-features!
  "Check if a given set of features are supported by this
  backend. Usually used for check if imported file features are
  supported by the current backend"
  [enabled-features]
  (let [not-supported (set/difference enabled-features supported-features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :feature-not-supported
                :feature (first not-supported)
                :hint (str/ffmt "features '%' not supported"
                                (str/join "," not-supported)))))
  enabled-features)

(defn check-file-features!
  "Function used for check feature compability between currently
  enabled features set on backend with the provided featured set by
  the penpot file"
  ([enabled-features file-features]
   (check-file-features! enabled-features file-features #{}))
  ([enabled-features file-features client-features]
   (let [file-features   (into #{} xf-remove-ephimeral file-features)
         ;; We should ignore all features that does not match with the
         ;; `no-migration-features` set because we can't enable them
         ;; as-is, because they probably need migrations
         client-features (set/intersection client-features no-migration-features)]
     (let [not-supported (-> enabled-features
                             (set/union client-features)
                             (set/difference file-features)
                             ;; NOTE: we don't want to raise a feature-mismatch
                             ;; exception for features which don't require an
                             ;; explicit file migration process or has no real
                             ;; effect on file data structure
                             (set/difference no-migration-features))]
       (when (seq not-supported)
         (ex/raise :type :restriction
                   :code :file-feature-mismatch
                   :feature (first not-supported)
                   :hint (str/ffmt "enabled features '%' not present in file (missing migration)"
                                   (str/join "," not-supported)))))

     (check-supported-features! file-features)

     (let [not-supported (-> file-features
                             (set/difference enabled-features)
                             (set/difference client-features)
                             (set/difference backend-only-features)
                             (set/difference frontend-only-features))]

       (when (seq not-supported)
         (ex/raise :type :restriction
                   :code :file-feature-mismatch
                   :feature (first not-supported)
                   :hint (str/ffmt "file features '%' not enabled"
                                   (str/join "," not-supported))))))

   enabled-features))

(defn check-teams-compatibility!
  [{source-features :features} {destination-features :features}]
  (when (contains? source-features "ephimeral/migration")
    (ex/raise :type :restriction
              :code :migration-in-progress
              :hint "the source team is in migration process"))

  (when (contains? destination-features "ephimeral/migration")
    (ex/raise :type :restriction
              :code :migration-in-progress
              :hint "the destination team is in migration process"))

  (let [not-supported (-> (or source-features #{})
                          (set/difference destination-features)
                          (set/difference no-migration-features)
                          (set/difference default-features)
                          (seq))]
    (when not-supported
      (ex/raise :type :restriction
                :code :team-feature-mismatch
                :feature (first not-supported)
                :hint (str/ffmt "the destination team does not have support '%' features"
                                (str/join "," not-supported)))))

  (let [not-supported (-> (or destination-features #{})
                          (set/difference source-features)
                          (set/difference no-migration-features)
                          (set/difference default-features)
                          (seq))]
    (when not-supported
      (ex/raise :type :restriction
                :code :team-feature-mismatch
                :feature (first not-supported)
                :hint (str/ffmt "the source team does not have support '%' features"
                                (str/join "," not-supported))))))


(defn check-paste-features!
  "Function used for check feature compability between currently enabled
  features set on the application with the provided featured set by
  the paste data (frontend clipboard)."
  [enabled-features paste-features]
  (let [not-supported (-> enabled-features
                          (set/difference paste-features)
                          ;; NOTE: we don't want to raise a feature-mismatch
                          ;; exception for features which don't require an
                          ;; explicit file migration process or has no real
                          ;; effect on file data structure
                          (set/difference no-migration-features))]

    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :missing-features-in-paste-content
                :feature (first not-supported)
                :hint (str/ffmt "expected features '%' not present in pasted content"
                                (str/join "," not-supported)))))

  (let [not-supported (set/difference enabled-features supported-features)]
    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :paste-feature-not-supported
                :feature (first not-supported)
                :hint (str/ffmt "features '%' not supported in the application"
                                (str/join "," not-supported)))))

  (let [not-supported (-> paste-features
                          (set/difference enabled-features)
                          (set/difference backend-only-features)
                          (set/difference frontend-only-features))]

    (when (seq not-supported)
      (ex/raise :type :restriction
                :code :paste-feature-not-enabled
                :feature (first not-supported)
                :hint (str/ffmt "paste features '%' not enabled on the application"
                                (str/join "," not-supported))))))

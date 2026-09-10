;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.storage.config
  "Configuration of the optional S3 storage targets and their per
  semantic-bucket routing.

  The routing is read from an external EDN file referenced by the
  `PENPOT_OBJECTS_STORAGE_S3_ROUTES_FILE` environment variable. The file
  declares additional S3 targets and, optionally, a map from Penpot
  semantic bucket to target id:

    {:targets
     {:temp {:bucket \"penpot-temp\"}
      :cold {:bucket \"penpot-cold\" :region :us-east-1}}
     :routes
     {\"tempfile\" :temp
      \"file-data\" :temp}}

  The implicit `:default` target always exists and is built from the
  existing `PENPOT_OBJECTS_STORAGE_S3_*` configuration, so the feature is
  fully backward compatible when the file is absent."
  (:refer-clojure :exclude [load])
  (:require
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.storage :as sto]
   [app.storage.s3 :as sto.s3]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private schema:target
  "The EDN declares targets with the same shape as the S3 backend, except
  the endpoint is accepted as a plain string and normalized to a URI by
  `normalize-target`."
  [:merge
   sto.s3/schema:target
   [:map
    [:endpoint {:optional true} [:or :string ::sm/uri]]]])

(def ^:private schema:file
  [:map {:title "storage-routes"}
   [:targets [:map-of :keyword schema:target]]
   [:routes {:optional true} [:map-of :string :keyword]]])

(def ^:private valid-file?
  (sm/validator schema:file))

(def ^:private explain-file
  (sm/explainer schema:file))

(defn- read-file
  [path]
  (try
    (-> (io/file path) slurp (edn/read-string))
    (catch Exception cause
      (ex/raise :type :validation
                :code :invalid-storage-routes-file
                :hint "unable to read storage routes file"
                :path (str path)
                :cause cause))))

(defn- assert-backend-s3!
  "The routing only makes sense on top of the S3 backend."
  []
  (let [backend (or (sto/get-legacy-backend)
                    (cf/get :objects-storage-backend)
                    :fs)]
    (when-not (= :s3 backend)
      (ex/raise :type :validation
                :code :invalid-storage-routes-backend
                :hint "storage routes file requires the :s3 backend"
                :backend (keyword backend)))))

(defn- validate!
  [data path]
  (when-not (valid-file? data)
    (ex/raise :type :validation
              :code :invalid-storage-routes
              :hint "invalid storage routes file"
              :path (str path)
              :explain (explain-file data)))

  (let [targets (:targets data)
        routes  (:routes data)]

    (when (contains? targets :default)
      (ex/raise :type :validation
                :code :reserved-storage-target
                :hint "`:default` is a reserved storage target id"
                :path (str path)))

    (doseq [[bucket target] routes]
      (when-not (contains? sto/valid-buckets bucket)
        (ex/raise :type :validation
                  :code :invalid-storage-routes-bucket
                  :hint "unknown semantic bucket in storage routes"
                  :bucket bucket
                  :path (str path)))

      (when-not (contains? targets target)
        (ex/raise :type :validation
                  :code :unknown-storage-target
                  :hint "storage route points to an undeclared target"
                  :bucket bucket
                  :target target
                  :path (str path))))

    data))

(defn- normalize-target
  [target]
  (cond-> target
    (string? (:endpoint target))
    (update :endpoint u/uri)))

(defn- normalize-targets
  [targets]
  (persistent!
   (reduce-kv (fn [acc id target]
                (assoc! acc id (normalize-target target)))
              (transient {})
              targets)))

(defn load
  "Reads and validates the optional S3 routing file.

  Returns a map with `:targets` (id -> target definition) and `:routes`
  (semantic bucket -> target id), or `{:targets nil :routes nil}` when the
  configuration key is unset."
  []
  (if-let [path (not-empty (cf/get :objects-storage-s3-routes-file))]
    (let [data (-> (read-file path) (validate! path))]
      (assert-backend-s3!)
      {:targets (normalize-targets (:targets data))
       :routes  (:routes data)})
    {:targets nil
     :routes  nil}))

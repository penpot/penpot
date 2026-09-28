;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

;; Discovery loads each scene and its transitive requires, including any
;; colocated operation functions. Stripping :run! only makes output plain
;; data; it cannot isolate browser dependencies. Keep this entire import
;; graph safe for Node. Browser adapters supply execution capabilities in
;; rtx; discovery must never invoke operation bodies. Ticket 06 must prove
;; collection with a real pan body present, not just metadata-only cases.

(ns benches.render-wasm.cases
  "Case collection for the renderer benchmarks.

  Loading this namespace registers the scene namespaces listed here. That
  require list is the discovery manifest. Nothing here should import browser
  or renderer code, so the Node runner and offline reports load scenes without
  renderer side effects.

  Usage:

    (cases/collect-cases {:master-seed 42})
    ;; =>
    [{:id :rects/load
      :scene :rects
      :scene-version 1
      :scene-description \"Seeded rectangles with translucent fills and centered strokes\"
      :scene-seed 2602546180
      :params {:count 1000 :width 1920 :height 1080 :min-size 20 :max-size 100}
      :view {:scale 1 :x 0 :y 0}
      :context :fresh
      :completion :render-full
      :batch-size 1
      :preparation {:version 1}}
     {:id :rects/pan ...}
     {:id :rects/zoom ...}]"
  (:require
   [app.common.schema :as sm]
   [benches.render-wasm.scenes.core :as core]
   benches.render-wasm.scenes.rects
   [clojure.string :as str]))

(defn- check-master-seed!
  "Collection requires an integer master seed in [0, 2^32)."
  [seed]
  (when-not (and (integer? seed) (<= 0 seed) (< seed 4294967296))
    (throw (ex-info "case collection requires an integer master seed in [0, 2^32)"
                    {:type ::invalid-master-seed
                     :seed seed}))))

(defn- as-invalid-case!
  "Runs `thunk`, translating core case-validation failures into this
  namespace's ::invalid-case so `collect-cases` has a single error
  contract. Registry mechanics (::duplicate-*, ::unknown-scene) and scene
  declarations (::invalid-scene) keep their own types."
  [thunk]
  (try
    (thunk)
    (catch :default cause
      (let [data (ex-data cause)]
        (if (contains? #{::core/invalid-case ::core/non-serializable-case} (:type data))
          (throw (ex-info (ex-message cause)
                          (assoc data :type ::invalid-case)
                          cause))
          (throw cause))))))

(defn- check-params!
  "Validates case params against the scene's closed schema."
  [case-id scene-id schema params]
  (when-not (sm/validate schema params)
    (throw (ex-info (str "invalid params for case " case-id)
                    {:type ::invalid-case
                     :id case-id
                     :scene scene-id
                     ::sm/explain (sm/explain schema params)}))))

(defn- validate-case!
  "Validates a registered case entry against its scene: unknown scene,
  case-namespace mismatch, declaration schema and closed params."
  [{:keys [id scene params] :as registered} scene-by-id]
  (let [entry (get scene-by-id scene)]
    (when (nil? entry)
      (throw (ex-info (str "case " id " names an unknown scene: " scene)
                      {:type ::unknown-scene
                       :id id
                       :scene scene})))
    (when-not (= (name scene) (namespace id))
      (throw (ex-info (str "case id " id " is not namespaced by its scene: " scene)
                      {:type ::invalid-case
                       :id id
                       :scene scene})))
    (as-invalid-case! #(core/check-registered-case! registered))
    (check-params! id scene (:params-schema entry) params)
    registered))

(defn- describe-case
  "Projects a validated case to its collected descriptor: scene
  version/description/seed attached, internal keys stripped, transit wire
  checked."
  [master-seed registered scene-entry]
  (let [projected (-> (core/project-case registered)
                      (assoc :scene-version (:version scene-entry)
                             :scene-description (:description scene-entry)
                             :scene-seed (core/derive-seed master-seed
                                                           (:scene registered)
                                                           (:params registered))))]
    (as-invalid-case! #(core/check-collected-case! projected))))

(defn scene-ids
  "Registered scene ids in declaration order."
  []
  (mapv :id (core/scenes)))

(defn case-ids
  "Registered case ids in declaration order."
  []
  (mapv :id (core/cases)))

(defn collect-cases
  "Selected benchmark cases as plain data.

  Validates scenes, cases and parameters before any browser work. `:filter`
  is a substring match on the case id; an empty selection throws ::no-cases."
  [{:keys [master-seed] :as options}]
  (check-master-seed! master-seed)
  (let [pattern       (:filter options)
        scene-by-id   (into {} (map (juxt :id identity)) (core/scenes))]
    (doseq [scene (core/scenes)]
      (core/check-registered-scene! scene))
    (let [selected (->> (core/cases)
                        (map #(validate-case! % scene-by-id))
                        (filter (fn [registered]
                                  (or (str/blank? pattern)
                                      (str/includes? (str (:id registered)) pattern))))
                        (mapv #(describe-case master-seed % (get scene-by-id (:scene %)))))]
      (when (empty? selected)
        (throw (ex-info "no benchmark cases match the selection"
                        {:type ::no-cases
                         :filter pattern})))
      selected)))

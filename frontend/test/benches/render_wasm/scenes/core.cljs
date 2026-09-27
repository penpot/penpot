;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.scenes.core
  "Registry and contract for renderer benchmark scenes and cases.

  Scene namespaces register themselves at load time through the `defscene`
  and `defcase` macros in `core.clj`. We don't import browser or renderer
  code here.

  - A scene declares an id, a version, a human description, a closed
    parameters schema and a one-argument build function.
  - A case declares an id namespaced by its scene, the scene, base parameters,
    a view, a context policy, a completion mode and an optional operation
    identity and run function.

  Collection validates every declaration, derives each scene seed and
  returns plain data"
  (:require
   [app.common.schema :as sm]
   [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Runtime contract
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; rtx is the case execution context. The driver builds one map per attempt,
;; calls the case body with it, and reads it back at completion:
;;
;;   :case    the collected case descriptor (id, scene, params, view,
;;            context, completion, batch-size, operation)
;;   :scene   the built scene snapshot {:objects ... :refs ...}
;;   :module  the WASM module handle, bound by the driver after init
;;   :slices  recorded render slices; helpers conj here
;;   :metrics recorded phase timings
;;   :now     zero-arg clock fn
;;   :frame   zero-arg promise of the next rAF timestamp
;;   :sleep   (fn [ms] ...) promise
;;   :check   zero-arg guard covering cancellation, deadline, context loss
;;
;; Ops take rtx first and return it, or a promise of it. Case bodies thread
;; rtx with `->`; the body's value is the rtx the driver collects. The
;; driver, the clock/scheduling helpers and the op vocabulary arrive with
;; tickets 05/06/14; this namespace fixes the binding name, the single
;; threaded value, and the body shape checked below.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-viewport
  "Viewport used when a case declares no `:viewport`: 1920x1080 at DPR 2.
  Per-case config; run-level CLI overrides belong to ticket 11."
  {:width 1920 :height 1080 :dpr 2})

(def schema:viewport
  [:map {:closed true}
   [:width {:optional true} ::sm/positive-safe-number]
   [:height {:optional true} ::sm/positive-safe-number]
   [:dpr {:optional true} ::sm/positive-safe-number]])

(def schema:view
  [:map {:closed true}
   [:scale [:and number? pos?]]
   [:x number?]
   [:y number?]
   [:viewport {:optional true} schema:viewport]])

(defn resolve-viewport
  "Merges `default-viewport` under a case view's declared `:viewport`.
  Collected descriptors keep the declared view; callers resolve at use so
  existing cases need no edits."
  [view]
  (merge default-viewport (:viewport view)))

(def schema:scene
  [:map {:closed true}
   [:id simple-keyword?]
   [:ns string?]
   [:version [:int {:min 1}]]
   [:description string?]
   [:params-schema vector?]
   [:build fn?]])

(def schema:registered-case
  [:map {:closed true}
   [:id qualified-keyword?]
   [:scene simple-keyword?]
   [:ns string?]
   [:params [:map]]
   [:view schema:view]
   [:context [:enum :fresh :reuse]]
   [:completion {:optional true} [:enum :call :await :render-full]]
   [:batch-size {:optional true} [:int {:min 1}]]
   [:operation {:optional true} [:map]]
   [:preparation {:optional true} [:map]]
   [:run! {:optional true} fn?]
   [:body-source {:optional true} [:fn seq?]]])

(def schema:collected-case
  [:map {:closed true}
   [:id qualified-keyword?]
   [:scene simple-keyword?]
   [:scene-version [:int {:min 1}]]
   [:scene-description string?]
   [:scene-seed [:int {:min 0 :max 4294967295}]]
   [:params [:map]]
   [:view schema:view]
   [:context [:enum :fresh :reuse]]
   [:completion [:enum :call :await :render-full]]
   [:batch-size [:int {:min 1}]]
   [:preparation [:map]]
   [:operation {:optional true} [:map]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce registry
  (atom {:scenes {}
         :scene-order []
         :cases {}
         :case-order []}))

(defn register-scene!
  "Adds `scene` to the registry. Re-registering the same id from the same
  namespace replaces the entry, so that namespace reload work.
  The same id from another namespace throws ::duplicate-scene."
  [{:keys [id ns] :as scene}]
  (let [existing (get-in @registry [:scenes id])]
    (when (and (some? existing) (not= ns (:ns existing)))
      (throw (ex-info (str "duplicate scene id: " id)
                      {:type ::duplicate-scene
                       :id id
                       :ns ns
                       :existing-ns (:ns existing)})))
    (swap! registry
           (fn [reg]
             (if (contains? (:scenes reg) id)
               (assoc-in reg [:scenes id] scene)
               (-> reg
                   (assoc-in [:scenes id] scene)
                   (update :scene-order conj id))))))
  id)

(defn- thread-form?
  [form]
  (and (seq? form)
       (contains? '#{-> clojure.core/->} (first form))
       (= 'rtx (second form))))

(defn- valid-tail?
  [form]
  (cond
    (thread-form? form)
    true

    (and (seq? form) (contains? '#{do let} (first form)))
    (valid-tail? (last form))

    :else
    false))

(defn check-body-source!
  "Validates a defcase body source and returns it unchanged. Every value
  the body can return threads the injected runtime: a `(-> rtx ...)`
  pipeline, optionally preceded by setup forms or wrapped in `let`/`do`.
  Anything else throws ::invalid-case. The checker covers shape only;
  review and fake-runtime unit tests cover sense."
  [id body-source]
  (when-not (and (seq body-source) (valid-tail? (last body-source)))
    (throw (ex-info (str "defcase " id " threads the injected runtime: "
                         "its value is a (-> rtx ...) pipeline, "
                         "optionally wrapped in let/do. Got: "
                         (pr-str body-source))
                    {:type ::invalid-case
                     :id id})))
  body-source)

(defn register-case!
  "Adds `bench-case` to the registry with the same replace/reject semantics
  as register-scene!. A declared body source is validated here, at load
  time, so a malformed pipeline fails before any browser work."
  [{:keys [id ns body-source] :as bench-case}]
  (when (some? body-source)
    (check-body-source! id body-source))
  (let [existing (get-in @registry [:cases id])]
    (when (and (some? existing) (not= ns (:ns existing)))
      (throw (ex-info (str "duplicate case id: " id)
                      {:type ::duplicate-case
                       :id id
                       :ns ns
                       :existing-ns (:ns existing)})))
    (swap! registry
           (fn [reg]
             (if (contains? (:cases reg) id)
               (assoc-in reg [:cases id] bench-case)
               (-> reg
                   (assoc-in [:cases id] bench-case)
                   (update :case-order conj id))))))
  id)

(defn unregister-scene!
  "Removes a scene registration. Tests use it to clean up synthetic scenes."
  [id]
  (swap! registry
         (fn [reg]
           (-> reg
               (update :scenes dissoc id)
               (update :scene-order #(vec (remove #{id} %))))))
  id)

(defn unregister-case!
  "Removes a case registration. Tests use it to clean up synthetic cases."
  [id]
  (swap! registry
         (fn [reg]
           (-> reg
               (update :cases dissoc id)
               (update :case-order #(vec (remove #{id} %))))))
  id)

(defn scenes
  "Registered scenes in declaration order."
  []
  (mapv (:scenes @registry) (:scene-order @registry)))

(defn cases
  "Registered cases in declaration order."
  []
  (mapv (:cases @registry) (:case-order @registry)))

(defn registered-scene
  "Full registered entry for a scene id."
  [id]
  (get-in @registry [:scenes id]))

(defn registered-case
  "Full registered entry for a case id, including the browser-side run
  function when the case declares one. The browser bridge resolves
  executions through this namespace; the Node runner only reads collected
  descriptors."
  [id]
  (get-in @registry [:cases id]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- check-params-schema!
  "Parses the scene's params schema with Penpot's registry, so a malformed
  schema fails here with a typed error instead of raw invalid-schema
  halfway through collection."
  [{:keys [id params-schema]}]
  (try
    (sm/schema params-schema)
    (catch :default cause
      (throw (ex-info (str "invalid params schema for scene " id)
                      {:type ::invalid-scene
                       :id id}
                      cause)))))

(defn check-registered-scene!
  "Validates a scene declaration and returns it unchanged."
  [scene]
  (check-params-schema! scene)
  (when-not (sm/validate schema:scene scene)
    (throw (ex-info (str "invalid scene declaration: " (:id scene))
                    {:type ::invalid-scene
                     :id (:id scene)
                     ::sm/explain (sm/explain schema:scene scene)})))
  scene)

(defn check-registered-case!
  "Validates a registered case declaration and returns it unchanged."
  [bench-case]
  (when-not (sm/validate schema:registered-case bench-case)
    (throw (ex-info (str "invalid case declaration: " (:id bench-case))
                    {:type ::invalid-case
                     :id (:id bench-case)
                     ::sm/explain (sm/explain schema:registered-case bench-case)})))
  bench-case)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seed derivation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- check-param-keys!
  "Rejects params keys that `canonical-params` cannot tell apart: only
  simple keywords render distinctly by name."
  [scene-id params]
  (letfn [(check [value path]
            (cond
              (map? value)
              (doseq [[k v] value]
                (when-not (simple-keyword? k)
                  (throw (ex-info (str "seed identity requires simple keyword keys at " path)
                                  {:type ::invalid-params
                                   :scene scene-id
                                   :path path
                                   :key k})))
                (check v (str path "/" (name k))))

              (vector? value)
              (dorun (map-indexed (fn [index v] (check v (str path "[" index "]"))) value))))]
    (check params "")))

(defn- canonical-params
  "Stable textual identity of a params map: keys render sorted by name,
  vectors keep their order, nested values render the same way. Map key
  order is an accident of construction, so sorting keeps the identity on
  the parameters themselves: equal parameters give the same seed whatever
  their literal order. Keys render by name, so params maps use simple
  keyword keys; derive-seed rejects anything else."
  [params]
  (letfn [(render [value]
            (cond
              (map? value)
              (str "{"
                   (str/join ","
                             (map (fn [[k v]] (str (name k) ":" (render v)))
                                  (sort-by (comp name key) value)))
                   "}")

              (vector? value)
              (str "[" (str/join "," (map render value)) "]")

              :else
              (pr-str value)))]
    (render params)))

(defn derive-seed
  "FNV-1a 32-bit keyed hash of a case's parameter identity.

  Fowler-Noll-Vo, 32-bit, offset basis 2166136261, prime 16777619, hashed
  over `<master-seed>\\0<scene-id>/<canonical params>`. A keyed hash, not
  the scene PRNG: the scene's mulberry32 generator is seeded with the
  result, and a sequential draw cannot substitute because it would couple
  cases through collection order. Keyed by scene and params only, so the
  seed does not depend on case name, collection order, operation body,
  attempts or A/B feature selection. Params maps use simple keyword keys;
  namespaced keywords and string keys cannot be told apart by name and make
  derive-seed throw ::invalid-params."
  [master-seed scene-id params]
  (check-param-keys! scene-id params)
  (let [text (str master-seed "\u0000" (name scene-id) "/" (canonical-params params))]
    (loop [index 0
           seed  2166136261]
      (if (< index (count text))
        (recur (inc index)
               (unsigned-bit-shift-right
                (js/Math.imul (bit-xor seed (.charCodeAt text index)) 16777619)
                0))
        seed))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn serializable?
  "True when `value` is plain data the runner can hand to the browser and
  store in results: nil, booleans, numbers, strings, keywords, and maps and
  vectors thereof. Records fail: a record reaches the runner as a host
  object, not plain data. Functions, atoms and host objects fail."
  [value]
  (cond
    (or (nil? value) (boolean? value) (number? value)
        (string? value) (keyword? value))
    true

    (and (map? value) (not (record? value)))
    (every? (fn [[k v]] (and (serializable? k) (serializable? v))) value)

    (vector? value)
    (every? serializable? value)

    :else
    false))

(defn project-case
  "Plain-data projection of a registered case: internal keys (`:ns`, `:run!`,
  `:body-source`) stripped, collection defaults applied."
  [bench-case]
  (-> bench-case
      (select-keys [:id :scene :params :view :context :completion :batch-size
                    :operation :preparation])
      (update :completion #(or % :render-full))
      (update :batch-size #(or % 1))
      (update :preparation #(or % {:version 1}))))

(defn check-collected-case!
  "Validates a collected case descriptor and returns it unchanged. Rejects
  anything that is not plain data: browser-side run functions must never
  reach the runner."
  [projected]
  (when-not (sm/validate schema:collected-case projected)
    (throw (ex-info (str "invalid collected case: " (:id projected))
                    {:type ::invalid-case
                     :id (:id projected)
                     ::sm/explain (sm/explain schema:collected-case projected)})))
  (when-not (serializable? projected)
    (throw (ex-info (str "collected case is not plain data: " (:id projected))
                    {:type ::non-serializable-case
                     :id (:id projected)})))
  projected)

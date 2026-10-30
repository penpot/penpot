;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns benches.render-wasm.declarations
  "Declarations, registry and contracts for renderer benchmark scenes and cases.

  A scene declares an id, description, parameters schema and build function.
  A case declares an id namespaced by its scene, parameters, view, context
  policy, completion mode and optional operation identity and run function.

  Ordinary :require loads the declaration macros and runtime helpers.
  Collection validates declarations and produces plain descriptors."
  #?(:cljs (:require-macros
            [app.common.exceptions :as ex]
            [benches.render-wasm.declarations]))
  #?(:clj (:require [app.common.schema :as-alias sm]))
  #?(:cljs
     (:require
      [app.common.schema :as sm]
      [app.common.transit :as t]
      [clojure.string :as str])))

#?(:clj
   (do
     (defn- single-arg-vector?
       "True when `form` is a vector holding one plain symbol."
       [form]
       (and (vector? form)
            (= 1 (count form))
            (symbol? (first form))
            (not= '& (first form))))

     (defn- check-arg-vector!
       [macro id argv]
       (when-not (single-arg-vector? argv)
         (throw (ex-info (str macro " " id " requires one argument vector with one symbol")
                         {:type ::invalid-run-function
                          :id id
                          :form argv}))))

     (defmacro defscene
       "Registers a scene and returns its id.

       `opts` supplies :description and :params-schema. `argv` names one scene
       parameter symbol, and `body` builds the snapshot.

       Example:

         (defscene :rects opts [params] (scene params))"
       [id opts argv & body]
       (check-arg-vector! "defscene" id argv)
       `(benches.render-wasm.declarations/register-scene!
         (assoc ~opts :id ~id :ns ~(str *ns*) :build (fn ~argv ~@body))))

     (defn- case-registration-form
       [id scene opts run-fn]
       (let [options (gensym "options")]
         `(let [~options ~opts]
            (when (contains? ~options :run!)
              (throw (ex-info (str "defcase " ~id " requires its run function as the fourth argument")
                              {:type :benches.render-wasm.declarations/invalid-run-function
                               :id ~id})))
            (when (nil? ~scene)
              (throw (ex-info (str "defcase " ~id " requires a :scene/case id")
                              {:type :benches.render-wasm.declarations/invalid-run-function
                               :id ~id
                               :form ~id})))
            (benches.render-wasm.declarations/register-case!
             (assoc ~options :id ~id :scene ~scene :ns ~(str *ns*)
                    ~@(when run-fn [:run! run-fn]))))))

     (defn- scene-from-id
       "Returns the scene keyword from a qualified case id, or nil."
       [id]
       (when-let [scene-ns (namespace id)]
         (keyword scene-ns)))

     (defmacro defcase
       "Registers a case and returns its id.

       The scene comes from the namespace part of the :scene/case id. An
       optional argument vector and body supply the run function, which accepts
       one execution context. Collection produces plain case descriptors.

       Example:

         (defcase :rects/pan options [context] (pan! context))"
       ([id opts]
        (case-registration-form id (scene-from-id id) opts nil))
       ([id opts argv & body]
        (check-arg-vector! "defcase" id argv)
        (case-registration-form id (scene-from-id id) opts `(fn ~argv ~@body))))))

#?(:cljs
   (do
     ;; Case execution context:
     ;; :case   - collected descriptor, including operation and completion
     ;; :scene  - scene snapshot {:objects ... :refs ...}
     ;; :module - WASM module handle
     ;; :view   - prepared camera view
     ;; :hooks  - clock, scheduling, guard and renderer operations
     ;; Case bodies return a measurement or a Promise of one.

     ;; Declarations and their transitive imports load in Node and the browser.
     ;; Browser capabilities enter through the execution context; resource
     ;; acquisition belongs to untimed preparation. Scenes keep their imports
     ;; safe for Node discovery.

     (def default-viewport
       "Default viewport dimensions and device pixel ratio for a case."
       {:width 1920 :height 1080 :dpr 2})

     (def schema:viewport
       [:map {:closed true}
        [:width {:optional true} ::sm/positive-safe-number]
        [:height {:optional true} ::sm/positive-safe-number]
        [:dpr {:optional true} ::sm/positive-safe-number]])

     (def schema:view
       "Camera placement with finite coordinates, positive scale and optional
       viewport overrides."
       [:map {:closed true}
        [:scale ::sm/positive-safe-number]
        [:x ::sm/safe-number]
        [:y ::sm/safe-number]
        [:viewport {:optional true} schema:viewport]])

     (defn resolve-viewport
       "Resolves a view's viewport overrides against the default viewport."
       [view]
       (merge default-viewport (:viewport view)))

     (def schema:scene
       [:map {:closed true}
        [:id simple-keyword?]
        [:ns string?]
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
        [:run! {:optional true} fn?]])

     (def schema:collected-case
       [:map {:closed true}
        [:id qualified-keyword?]
        [:scene simple-keyword?]
        [:scene-description string?]
        [:scene-seed [:int {:min 0 :max 4294967295}]]
        [:params [:map]]
        [:view schema:view]
        [:context [:enum :fresh :reuse]]
        [:completion [:enum :call :await :render-full]]
        [:batch-size [:int {:min 1}]]
        [:preparation [:map]]
        [:operation {:optional true} [:map]]])

     (defonce registry
       (atom {:scenes {}
              :scene-order []
              :cases {}
              :case-order []}))

     (defn- register-entry
       [entries-key order-key kind duplicate-type {:keys [id ns] :as entry}]
       (let [existing (get-in @registry [entries-key id])]
         (when (and (some? existing) (not= ns (:ns existing)))
           (throw (ex-info (str "duplicate " kind " id: " id)
                           {:type duplicate-type
                            :id id
                            :ns ns
                            :existing-ns (:ns existing)})))
         (swap! registry
                (fn [reg]
                  (cond-> (assoc-in reg [entries-key id] entry)
                    (not (contains? (get reg entries-key) id))
                    (update order-key conj id)))))
       id)

     (defn register-scene!
       "Registers a scene. The same id from the same namespace replaces the
       entry in declaration order. Another namespace raises ::duplicate-scene."
       [scene]
       (register-entry :scenes :scene-order "scene" ::duplicate-scene scene))

     (defn register-case!
       "Registers a case. The same id from the same namespace replaces the
       entry in declaration order. Another namespace raises ::duplicate-case."
       [bench-case]
       (register-entry :cases :case-order "case" ::duplicate-case bench-case))

     (defn- unregister-entry
       [entries-key order-key id]
       (swap! registry
              (fn [reg]
                (-> reg
                    (update entries-key dissoc id)
                    (update order-key #(vec (remove #{id} %))))))
       id)

     (defn unregister-scene!
       "Removes a scene registration and returns its id."
       [id]
       (unregister-entry :scenes :scene-order id))

     (defn unregister-case!
       "Removes a case registration and returns its id."
       [id]
       (unregister-entry :cases :case-order id))

     (defn scenes
       "Returns registered scenes in declaration order."
       []
       (mapv (:scenes @registry) (:scene-order @registry)))

     (defn cases
       "Returns registered cases in declaration order."
       []
       (mapv (:cases @registry) (:case-order @registry)))

     (defn registered-scene
       "Returns the full registration for a scene id."
       [id]
       (get-in @registry [:scenes id]))

     (defn registered-case
       "Returns the full registration for a case id, including its run function."
       [id]
       (get-in @registry [:cases id]))

     (defn- check-params-schema!
       [{:keys [id params-schema]}]
       (try
         (sm/schema params-schema)
         (catch :default cause
           (throw (ex-info (str "invalid params schema for scene " id)
                           {:type ::invalid-scene :id id}
                           cause)))))

     (defn- check-declaration
       [schema declaration message error-type]
       (when-not (sm/validate schema declaration)
         (throw (ex-info (str message (:id declaration))
                         {:type error-type
                          :id (:id declaration)
                          ::sm/explain (sm/explain schema declaration)})))
       declaration)

     (defn check-registered-scene!
       "Validates a scene declaration and returns it unchanged."
       [scene]
       (check-params-schema! scene)
       (check-declaration schema:scene scene "invalid scene declaration: " ::invalid-scene))

     (defn check-registered-case!
       "Validates a registered case declaration and returns it unchanged."
       [bench-case]
       (check-declaration schema:registered-case bench-case "invalid case declaration: " ::invalid-case))

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

     (defn transit-round-trips?
       "True when `value` survives Transit encoding and decoding with the same
       wire representation, including NaN and infinities."
       [value]
       (true? (ex/ignoring
               (let [encoded (t/encode-str value)]
                 (= encoded (-> encoded t/decode-str t/encode-str))))))

     (defn project-case
       "Returns the case's descriptor fields with defaults for completion,
       batch size and preparation."
       [bench-case]
       (-> bench-case
           (select-keys [:id :scene :params :view :context :completion :batch-size
                         :operation :preparation])
           (update :completion #(or % :render-full))
           (update :batch-size #(or % 1))
           (update :preparation #(or % {}))))

     (defn check-collected-case
       "Validates a collected case descriptor and its Transit representation,
       returning the descriptor unchanged."
       [projected]
       (check-declaration schema:collected-case projected "invalid collected case: " ::invalid-case)
       (when-not (transit-round-trips? projected)
         (throw (ex-info (str "collected case does not survive the transit wire: " (:id projected))
                         {:type ::non-serializable-case
                          :id (:id projected)})))
       projected)))

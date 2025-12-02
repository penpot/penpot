;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

#_:clj-kondo/ignore
(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! new get merge clone contains? array? into-array reify class])
  #?(:cljs (:require-macros [app.util.object]))
  (:require
   [app.common.json :as json]
   [app.common.schema :as sm]
   [clojure.core :as c]
   [cuerdas.core :as str]
   [rumext.v2.util :as mfu]))

#?(:cljs
   (defn array?
     [o]
     (.isArray js/Array o)))

#?(:cljs
   (defn into-array
     [o]
     (js/Array.from o)))

#?(:cljs
   (defn create [] #js {}))

#?(:cljs
   (defn get
     ([obj k]
      (when (some? obj)
        (unchecked-get obj k)))
     ([obj k default]
      (let [result (get obj k)]
        (if (undefined? result) default result)))))

#?(:cljs
   (defn contains?
     [obj k]
     (when (some? obj)
       (js/Object.hasOwn obj k))))

#?(:cljs
   (defn clone
     [a]
     (js/Object.assign #js {} a)))

#?(:cljs
   (defn merge!
     ([a b]
      (js/Object.assign a b))
     ([a b & more]
      (reduce merge! (merge! a b) more))))

#?(:cljs
   (defn merge
     ([a b]
      (js/Object.assign #js {} a b))
     ([a b & more]
      (reduce merge! (merge a b) more))))

#?(:cljs
   (defn set!
     [obj key value]
     (unchecked-set obj key value)
     obj))

#?(:cljs
   (defn unset!
     [obj key]
     (js-delete obj key)
     obj))

#?(:cljs
   (def ^:private not-found-sym
     (js/Symbol "not-found")))

#?(:cljs
   (defn update!
     [obj key f & args]
     (let [found (c/get obj key not-found-sym)]
       (when-not ^boolean (identical? found not-found-sym)
         (unchecked-set obj key (apply f found args)))
       obj)))

#?(:cljs
   (defn ^boolean in?
     [obj prop]
     (js* "~{} in ~{}" prop obj)))

#?(:cljs
   (defn without-empty
     [^js obj]
     (when (some? obj)
       (js* "Object.entries(~{}).reduce((a, [k,v]) => (v == null ? a : (a[k]=v, a)), {}) " obj))))

#?(:cljs
   (defn plain-object?
     ^boolean
     [o]
     (and (some? o)
          (identical? (.getPrototypeOf js/Object o)
                      (.-prototype js/Object)))))

;; EXPERIMENTAL: unsafe, does not checks and not validates the input,
;; should be improved over time, for now it works for define a class
;; extending js/Error that is more than enought for a first, quick and
;; dirty macro impl for generating classes.

(defmacro class
  "Create a class instance"
  [& {:keys [name extends constructor]}]

  (let [params
        (if (and constructor (= 'fn (first constructor)))
          (into [] (drop 1) (second constructor))
          [])

        constructor-sym
        (symbol name)

        constructor
        (if constructor
          constructor
          `(fn ~name [~'this]
             (.call ~extends ~'this)))]

    `(let [konstructor# ~constructor
           extends# ~extends
           ~constructor-sym
           (fn ~constructor-sym ~params
             (cljs.core/this-as ~'this
                                (konstructor# ~'this ~@params)))]

       (set! (.-prototype ~constructor-sym)
             (js/Object.create (.-prototype extends#)))
       (set! (.-constructor (.-prototype ~constructor-sym))
             konstructor#)

       ~constructor-sym)))

#?(:clj
   (defmacro add-properties!
     "Adds properties to an object using `.defineProperty`"
     [rsym & properties]
     (let [rsym       (with-meta rsym {:tag 'js})

           this-sym   (with-meta (gensym (str rsym "-this-")) {:tag 'js})
           target-sym (with-meta (gensym (str rsym "-target-")) {:tag 'js})

           make-sym
           (fn [pname prefix]
             (-> (gensym (str "prop-" prefix "-" (str/slug pname) "-"))
                 (with-meta {:tag 'js})))

           make-sym
           (memoize make-sym)

           bindings
           (->> properties
                (mapcat (fn [params]
                          (let [pname    (c/get params :name)
                                get-expr (c/get params :get)
                                set-expr (c/get params :set)
                                fn-expr  (c/get params :fn)
                                schema-n (c/get params :schema)
                                wrap     (c/get params :wrap)
                                schema-1 (c/get params :schema-1)
                                this?    (c/get params :this false)

                                fn-sym
                                (-> (gensym (str "internal-fn-" (str/slug pname) "-"))
                                    (with-meta {:tag 'function}))

                                coercer-sym
                                (-> (gensym (str "coercer-fn-" (str/slug pname) "-"))
                                    (with-meta {:tag 'function}))

                                wrap-sym
                                (-> (gensym (str "wrap-fn-" (str/slug pname) "-"))
                                    (with-meta {:tag 'function}))]

                            (concat
                             (when wrap
                               [wrap-sym wrap])

                             (when get-expr
                               [(make-sym pname "get-fn")
                                (if this?
                                  `(fn []
                                     (let [~this-sym (~'js* "this")
                                           ~fn-sym ~get-expr]
                                       (.call ~fn-sym ~this-sym ~this-sym)))
                                  get-expr)])

                             (when set-expr
                               [(make-sym pname "set-fn")
                                (if this?
                                  `(fn [v#]
                                     (let [~this-sym (~'js* "this")
                                           ~fn-sym ~set-expr]
                                       (.call ~fn-sym ~this-sym ~this-sym v#)))
                                  set-expr)])

                             (when fn-expr
                               (concat
                                (when schema-1
                                  [coercer-sym `(sm/coercer ~schema-1)])
                                (when schema-n
                                  [coercer-sym `(sm/coercer ~schema-n)])

                                [(make-sym pname "get-fn")
                                 `(fn []
                                    (let [~this-sym (~'js* "this")
                                          ~fn-sym   ~fn-expr
                                          ~fn-sym   ~(if this?
                                                       `(.bind ~fn-sym ~this-sym ~this-sym)
                                                       `(.bind ~fn-sym ~this-sym))

                                          ~@(if schema-1
                                              [fn-sym `(fn* [param#]
                                                            (let [param# (json/->clj param#)
                                                                  param# (~coercer-sym param#)]
                                                              (~fn-sym param#)))]
                                              [])
                                          ~@(if schema-n
                                              [fn-sym `(fn* []
                                                            (let [params# (into-array (cljs.core/js-arguments))
                                                                  params# (mfu/bean params#)
                                                                  params# (~coercer-sym params#)]
                                                              (apply ~fn-sym params#)))]
                                              [])
                                          ~@(if wrap
                                              [fn-sym `(~wrap-sym ~fn-sym)]
                                              [])]

                                      ~fn-sym))])))))))]

       `(let [~target-sym ~rsym
              ~@bindings]
          ;; Creates the `.defineProperty` per property
          ~@(for [params properties
                  :let [pname    (c/get params :name)
                        get-expr (c/get params :get)
                        set-expr (c/get params :set)
                        fn-expr  (c/get params :fn)
                        enum?    (c/get params :enumerable true)
                        conf?    (c/get params :configurable)
                        writ?    (c/get params :writable)]]
              `(.defineProperty
                js/Object
                ~target-sym
                ~pname
                (cljs.core/js-obj
                 ~@(concat
                    ["enumerable" (boolean enum?)]

                    (when conf?
                      ["configurable" true])

                    (when (some? writ?)
                      ["writable" true])

                    (when (or get-expr)
                      ["get" (make-sym pname "get-fn")])

                    (when fn-expr
                      ["get" (make-sym pname "get-fn")])

                    (when set-expr
                      ["set" (make-sym pname "set-fn")])))))

          ;; Returns the object
          ~target-sym))))

(defn- collect-properties
  [params]
  (let [[tmeta params] (if (map? (first params))
                         [(first params) (rest params)]
                         [{} params])]
    (loop [params (seq params)
           props  []
           defs   {}
           curr   :start
           ckey   nil]
      (cond
        (= curr :start)
        (let [candidate (first params)]
          (cond
            (keyword? candidate)
            (recur (rest params) props defs :property candidate)

            (nil? candidate)
            (recur (rest params) props defs :end nil)

            :else
            (recur (rest params) props defs :definition candidate)))

        (= :end curr)
        [tmeta props defs]

        (= :property curr)
        (let [definition (first params)]
          (if (some? definition)
            (let [definition (if (map? definition)
                               (c/merge {:wrap (:wrap tmeta)} definition)
                               (-> {:enumerable false}
                                   (c/merge (meta definition))
                                   (assoc :wrap (:wrap tmeta))
                                   (assoc :fn definition)
                                   (dissoc :get :set)))
                  definition (assoc definition :name (name ckey))]

              (recur (rest params)
                     (conj props definition)
                     defs
                     :start
                     nil))

            (let [hint (str "expected property definition for: " curr)]
              (throw (ex-info hint {:key curr})))))

        (= :definition curr)
        (let [[params props defs curr ckey]
              (loop [params params
                     defs   (update defs ckey #(or % []))]
                (let [candidate (first params)
                      params    (rest params)]
                  (cond
                    (nil? candidate)
                    [params props defs :end]

                    (keyword? candidate)
                    [params props defs :property candidate]

                    (symbol? candidate)
                    [params props defs :definition candidate]

                    :else
                    (recur params (update defs ckey conj candidate)))))]
          (recur params props defs curr ckey))

        :else
        (throw (ex-info "invalid params" {}))))))

#?(:cljs
   (def type-symbol
     (js/Symbol.for "penpot.reify:type")))

#?(:cljs
   (defn type-of?
     [o t]
     (let [o (get o type-symbol)]
       (= o t))))

(defmacro reify
  "A domain specific variation of reify that creates anonymous objects
  on demand with the ability to assign protocol implementations and
  custom properties"
  [& params]
  (let [[tmeta properties definitions]
        (collect-properties params)

        f-sym
        (gensym "to-string-")

        type-name
        (or (c/get tmeta :name) (str (gensym "anonymous")))

        obj-sym
        (gensym "obj-")]

    `(let [~obj-sym (cljs.core/js-obj)
           ~f-sym   (fn [] ~type-name)]
       (add-properties! ~obj-sym
                        {:name ~'js/Symbol.toStringTag
                         :enumerable false
                         :get ~f-sym}
                        {:name (js/Symbol.for "penpot.reify:type")
                         :enumerable false
                         :get ~f-sym}
                        ~@properties)

       ~(if-let [definitions (seq definitions)]
          `(cljs.core/specify! ~obj-sym
             ~@(mapcat (fn [[k v]] (cons k v)) definitions))
          obj-sym))))

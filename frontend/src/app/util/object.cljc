;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.object
  "A collection of helpers for work with javascript objects."
  (:refer-clojure :exclude [set! new get merge clone contains? array? into-array reify])
  #?(:cljs (:require-macros [app.util.object]))
  (:require
   [clojure.core :as c]))

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

(defmacro add-properties!
  "Adds properties to an object using `.defineProperty`"
  [rsym & properties]
  (let [rsym       (with-meta rsym {:tag 'js})
        getf-sym   (with-meta (gensym (str rsym "-get-fn-")) {:tag 'js})
        setf-sym   (with-meta (gensym (str rsym "-set-fn-")) {:tag 'js})
        this-sym   (with-meta (gensym (str rsym "-this-")) {:tag 'js})
        target-sym (with-meta (gensym (str rsym "-target-")) {:tag 'js})]
    `(let [~target-sym ~rsym]
       ;; Creates the `.defineProperty` per property
       ~@(for [params properties
               :let [pname    (c/get params :name)
                     get-expr (c/get params :get)
                     set-expr (c/get params :set)
                     this?    (c/get params :this true)
                     enum?    (c/get params :enumerable true)
                     conf?    (c/get params :configurable)
                     writ?    (c/get params :writable)]]
           `(let [~@(concat
                     (when get-expr
                       [getf-sym get-expr])
                     (when set-expr
                       [setf-sym set-expr]))]
              (.defineProperty
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

                   (when get-expr
                     (if this?
                       ["get" `(fn [] (cljs.core/this-as ~this-sym (~getf-sym ~this-sym)))]
                       ["get" getf-sym]))

                   (when set-expr
                     (if this?
                       ["set" `(fn [v#] (cljs.core/this-as ~this-sym (~setf-sym ~this-sym v#)))]
                       ["set" setf-sym])))))))

       ;; Returns the object
       ~target-sym)))

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
                               (c/merge {:this false} (assoc definition :name (name ckey)))
                               (-> {:enumerable false}
                                   (c/merge (meta definition))
                                   (assoc :name (name ckey))
                                   (assoc :this false)
                                   (assoc :get `(fn [] ~definition))))]
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
  (let [[tmeta properties definitions] (collect-properties params)
        obj-sym    (gensym "obj-")]
    `(let [~obj-sym (cljs.core/js-obj)]
       (add-properties! ~obj-sym
                        ~@(when-let [tname (:name tmeta)]
                            [`{:name ~'js/Symbol.toStringTag
                               :this false
                               :enumerable false
                               :get (fn [] ~tname)}
                             `{:name type-symbol
                               :this false
                               :enumerable false
                               :get (fn [] ~tname)}])
                        ~@properties)
       (let [~obj-sym ~(if-let [definitions (seq definitions)]
                         `(cljs.core/specify! ~obj-sym
                            ~@(mapcat (fn [[k v]] (cons k v)) definitions))
                         obj-sym)]

         (cljs.core/specify! ~obj-sym)))))

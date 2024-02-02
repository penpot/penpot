;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.record
  "A collection of helpers and macros for defien a penpot customized record types."
  (:refer-clojure :exclude [defrecord assoc! clone])
  #?(:cljs (:require-macros [app.common.record]))
  #?(:clj
     (:import java.util.Map$Entry)))

#_:clj-kondo/ignore
(defmacro caching-hash
  [coll hash-fn hash-key]
  `(let [h# ~hash-key]
     (if-not (nil? h#)
       h#
       (let [h# (~hash-fn ~coll)]
         (set! ~hash-key h#)
         h#))))

#?(:clj
   (defn- property-symbol
     [sym]
     (symbol (str "-" (name sym)))))

#?(:clj
   (defn- generate-field-access
     [this-sym val-sym fields]
     (map (fn [field]
            (cond
              (nil? field) nil
              (identical? field val-sym) val-sym
              :else `(. ~this-sym ~(property-symbol field))))
          fields)))


(defprotocol ICustomRecordEquiv
  (-equiv-with-exceptions [_ other exceptions]))

#?(:clj
   (defn emit-impl-js
     [tagname base-fields]
     (let [fields   (conj base-fields '$meta '$extmap (with-meta '$hash {:mutable true}))
           key-sym  (gensym "key-")
           val-sym  (gensym "val-")
           othr-sym (with-meta 'other {:tag tagname})
           this-sym (with-meta 'this {:tag tagname})]
       ['cljs.core/IRecord
        'cljs.core/ICloneable
        `(~'-clone [~this-sym]
                   (new ~tagname ~@(generate-field-access this-sym val-sym fields)))

        'cljs.core/IHash
        `(~'-hash [~this-sym]
                  (caching-hash ~this-sym
                                (fn [coll#]
                                  (bit-xor
                                   ~(hash (str tagname))
                                   (cljs.core/hash-unordered-coll coll#)))
                                (. ~this-sym ~'-$hash)))

        'cljs.core/IEquiv
        `(~'-equiv [~this-sym ~othr-sym]
                   (or (identical? ~this-sym ~othr-sym)
                       (and (some? ~othr-sym)
                            (identical? (.-constructor ~this-sym)
                                        (.-constructor ~othr-sym))
                            ~@(map (fn [field]
                                     `(= (.. ~this-sym ~(property-symbol field))
                                         (.. ~(with-meta othr-sym {:tag tagname}) ~(property-symbol field))))
                                   base-fields)

                            (= (. ~this-sym ~'-$extmap)
                               (. ~(with-meta othr-sym {:tag tagname}) ~'-$extmap)))))

        `ICustomRecordEquiv
        `(~'-equiv-with-exceptions [~this-sym ~othr-sym ~'exceptions]
                                   (or (identical? ~this-sym ~othr-sym)
                                       (and (some? ~othr-sym)
                                            (identical? (.-constructor ~this-sym)
                                                        (.-constructor ~othr-sym))
                                            (and ~@(->> base-fields
                                                        (map (fn [field]
                                                               `(= (.. ~this-sym ~(property-symbol field))
                                                                   (.. ~(with-meta othr-sym {:tag tagname}) ~(property-symbol field))))))
                                                 (== (count (. ~this-sym ~'-$extmap))
                                                     (count (. ~othr-sym ~'-$extmap))))

                                            (reduce-kv (fn [~'_ ~'k ~'v]
                                                         (if (contains? ~'exceptions ~'k)
                                                           true
                                                           (if (= (get (. ~this-sym ~'-$extmap) ~'k ::not-exists) ~'v)
                                                             true
                                                             (reduced false))))
                                                       true
                                                       (. ~othr-sym ~'-$extmap)))))


        'cljs.core/IMeta
        `(~'-meta [~this-sym] (. ~this-sym ~'-$meta))

        'cljs.core/IWithMeta
        `(~'-with-meta [~this-sym ~val-sym]
                       (new ~tagname ~@(->> (replace {'$meta val-sym} fields)
                                            (generate-field-access this-sym val-sym))))

        'cljs.core/ILookup
        `(~'-lookup [~this-sym k#]
                    (cljs.core/-lookup ~this-sym k# nil))

        `(~'-lookup [~this-sym ~key-sym else#]
                    (case ~key-sym
                      ~@(mapcat (fn [f] [(keyword f) `(. ~this-sym ~(property-symbol f))])
                                base-fields)
                      (cljs.core/get (. ~this-sym ~'-$extmap) ~key-sym else#)))

        'cljs.core/ICounted
        `(~'-count [~this-sym]
                   (+ ~(count base-fields) (count (. ~this-sym ~'-$extmap))))

        'cljs.core/ICollection
        `(~'-conj [~this-sym ~val-sym]
                  (if (vector? ~val-sym)
                    (cljs.core/-assoc ~this-sym (cljs.core/-nth ~val-sym 0) (cljs.core/-nth ~val-sym 1))
                    (reduce cljs.core/-conj ~this-sym ~val-sym)))

        'cljs.core/IAssociative
        `(~'-contains-key? [~this-sym ~key-sym]
                           ~(if (seq base-fields)
                              `(case ~key-sym
                                 (~@(map keyword base-fields)) true
                                 (contains? (. ~this-sym ~'-$extmap) ~key-sym))
                              `(contains? (. ~this-sym ~'-$extmap) ~key-sym)))

        `(~'-assoc [~this-sym ~key-sym ~val-sym]
                   (case ~key-sym
                     ~@(mapcat (fn [fld]
                                 [(keyword fld) `(new ~tagname ~@(->> (replace {fld val-sym '$hash nil} fields)
                                                                      (generate-field-access this-sym val-sym)))])
                               base-fields)
                     (new ~tagname ~@(->> (remove #{'$extmap '$hash} fields)
                                          (generate-field-access this-sym val-sym))
                          (assoc (. ~this-sym ~'-$extmap) ~key-sym ~val-sym) nil)))

        'cljs.core/ITransientAssociative
        `(~'-assoc! [~this-sym ~key-sym ~val-sym]
                    (let [key# (if (keyword? ~key-sym)
                                 (.-fqn ~(with-meta key-sym {:tag `cljs.core/Keyword}))
                                 ~key-sym)]
                      (case ~key-sym
                        ~@(mapcat
                           (fn [f]
                             [(keyword f) `(set! (. ~this-sym ~(property-symbol f)) ~val-sym)])
                           base-fields)

                        (set! (. ~this-sym ~'-$extmap) (cljs.core/assoc (. ~this-sym ~'-$extmap) ~key-sym ~val-sym)))

                      ~this-sym))

        'cljs.core/IMap
        `(~'-dissoc [~this-sym ~key-sym]
                    (case ~key-sym
                      (~@(map keyword base-fields))
                      (cljs.core/-assoc ~this-sym ~key-sym nil)

                      (let [extmap1# (. ~this-sym ~'-$extmap)
                            extmap2# (dissoc extmap1# ~key-sym)]
                        (if (identical? extmap1# extmap2#)
                          ~this-sym
                          (new ~tagname ~@(->> (remove #{'$extmap '$hash} fields)
                                               (generate-field-access this-sym val-sym))
                               (not-empty extmap2#)
                               nil)))))

        'cljs.core/ISeqable
        `(~'-seq [~this-sym]
                 (seq (concat [~@(map (fn [f]
                                        `(cljs.core/MapEntry.
                                          ~(keyword f)
                                          (. ~this-sym ~(property-symbol f))
                                          nil))
                                      base-fields)]
                              (. ~this-sym ~'-$extmap))))

        'cljs.core/IIterable
        `(~'-iterator [~this-sym]
                      (cljs.core/RecordIter. 0 ~this-sym ~(count base-fields)
                                             [~@(map keyword base-fields)]
                                             (if (. ~this-sym ~'-$extmap)
                                               (cljs.core/-iterator (. ~this-sym ~'-$extmap))
                                               (cljs.core/nil-iter))))

        'cljs.core/IKVReduce
        `(~'-kv-reduce [~this-sym f# init#]
                       (reduce (fn [ret# [~key-sym v#]] (f# ret# ~key-sym v#)) init# ~this-sym))])))

#?(:clj
   (defn emit-impl-jvm
     [tagname base-fields]
     (let [fields   (conj base-fields '$meta '$extmap (with-meta '$hash {:unsynchronized-mutable true}))
           key-sym  'key
           val-sym  'val
           this-sym (with-meta 'this {:tag tagname})]

       ['clojure.lang.IRecord
        'clojure.lang.IPersistentMap
        `(~'equiv [~this-sym ~val-sym]
                  (and (some? ~val-sym)
                       (instance? ~tagname ~val-sym)
                       ~@(map (fn [field]
                                `(= (.. ~this-sym ~(property-symbol field))
                                    (.. ~(with-meta val-sym {:tag tagname}) ~(property-symbol field))))
                              base-fields)
                       (= (. ~this-sym ~'-$extmap)
                          (. ~(with-meta val-sym {:tag tagname}) ~'-$extmap))))

        `(~'entryAt [~this-sym ~key-sym]
                    (let [v# (.valAt ~this-sym ~key-sym ::not-found)]
                      (when (not= v# ::not-found)
                        (clojure.lang.MapEntry. ~key-sym v#))))

        `(~'valAt [~this-sym ~key-sym]
                  (.valAt ~this-sym ~key-sym nil))

        `(~'valAt [~this-sym ~key-sym ~'not-found]
                  (case ~key-sym
                    ~@(mapcat (fn [f] [(keyword f) `(. ~this-sym ~(property-symbol f))]) base-fields)
                    (clojure.core/get (. ~this-sym ~'-$extmap) ~key-sym ~'not-found)))

        `(~'count [~this-sym]
                  (+ ~(count base-fields) (count (. ~this-sym ~'-$extmap))))


        `(~'empty [~this-sym]
                  (new ~tagname ~@(->> (remove #{'$extmap '$hash} fields)
                                       (generate-field-access this-sym nil))
                       nil nil))

        `(~'cons [~this-sym ~val-sym]
                 (if (instance? java.util.Map$Entry ~val-sym)
                   (let [^Map$Entry e# ~val-sym]
                     (.assoc ~this-sym (.getKey e#) (.getValue e#)))
                   (if (instance? clojure.lang.IPersistentVector ~val-sym)
                     (if (= 2 (count ~val-sym))
                       (.assoc ~this-sym (nth ~val-sym 0) (nth ~val-sym 1))
                       (throw (IllegalArgumentException.
                               "Vector arg to map conj must be a pair")))
                     (reduce (fn [^clojure.lang.IPersistentMap m#
                                  ^java.util.Map$Entry e#]
                               (.assoc m# (.getKey e#) (.getValue e#)))
                             ~this-sym
                             ~val-sym))))

        `(~'assoc [~this-sym ~key-sym ~val-sym]
                  (case ~key-sym
                    ~@(mapcat (fn [fld]
                                [(keyword fld) `(new ~tagname ~@(->> (replace {fld val-sym '$hash nil} fields)
                                                                     (generate-field-access this-sym val-sym)))])
                              base-fields)
                    (new ~tagname ~@(->> (remove #{'$extmap '$hash} fields)
                                         (generate-field-access this-sym val-sym))
                         (assoc (. ~this-sym ~'-$extmap) ~key-sym ~val-sym)
                         nil)))

        `(~'without [~this-sym ~key-sym]
                    (case ~key-sym
                      (~@(map keyword base-fields))
                      (.assoc ~this-sym ~key-sym nil)

                      (if-let [extmap1# (. ~this-sym ~'-$extmap)]
                        (let [extmap2# (.without ^clojure.lang.IPersistentMap extmap1# ~key-sym)]
                          (if (identical? extmap1# extmap2#)
                            ~this-sym
                            (new ~tagname ~@(->> (remove #{'$extmap '$hash} fields)
                                                 (generate-field-access this-sym val-sym))
                                 (not-empty extmap2#)
                                 nil)))
                        ~this-sym)))

        `(~'seq [~this-sym]
                (seq (concat [~@(map (fn [f]
                                       `(clojure.lang.MapEntry/create
                                         ~(keyword f)
                                         (. ~this-sym ~(property-symbol f))))
                                     base-fields)]
                             (. ~this-sym ~'-$extmap))))

        `(~'iterator [~this-sym]
                     (clojure.lang.SeqIterator. (.seq ~this-sym)))

        'clojure.lang.IFn
        `(~'invoke [~this-sym ~key-sym]
                   (.valAt ~this-sym ~key-sym))

        `(~'invoke [~this-sym ~key-sym ~'not-found]
                   (.valAt ~this-sym ~key-sym ~'not-found))

        'java.util.Map
        `(~'size [~this-sym]
                 (clojure.core/count ~this-sym))

        `(~'containsKey [~this-sym ~key-sym]
                        ~(if (seq base-fields)
                           `(case ~key-sym
                              (~@(map keyword base-fields)) true
                              (contains? (. ~this-sym ~'-$extmap) ~key-sym))
                           `(contains? (. ~this-sym ~'-$extmap) ~key-sym)))

        `(~'isEmpty [~this-sym]
                    (zero? (count ~this-sym)))

        `(~'keySet [~this-sym]
                   (throw (UnsupportedOperationException. "not implemented")))

        `(~'entrySet [~this-sym]
                     (throw (UnsupportedOperationException. "not implemented")))

        `(~'get [~this-sym ~key-sym]
                (.valAt ~this-sym ~key-sym))

        `(~'containsValue [~this-sym ~val-sym]
                          (throw (UnsupportedOperationException. "not implemented")))

        `(~'values [~this-sym]
                   (map val (.seq ~this-sym)))

        'java.lang.Object
        `(~'equals [~this-sym other#]
                   (.equiv ~this-sym other#))

        `(~'hashCode [~this-sym]
                     (clojure.lang.APersistentMap/mapHash ~this-sym))

        'clojure.lang.IHashEq
        `(~'hasheq [~this-sym]
                   (clojure.core/hash-unordered-coll ~this-sym))

        'clojure.lang.IObj
        `(~'meta [~this-sym]
                 (. ~this-sym ~'-$meta))

        `(~'withMeta [~this-sym ~val-sym]
                     (new ~tagname ~@(->> (replace {'$meta val-sym} fields)
                                          (generate-field-access this-sym val-sym))))])))

(defmacro defrecord
  [rsym fields & impls]
  (let [param   (gensym "param-")
        ks      (map keyword fields)
        fields' (mapv #(with-meta % nil) fields)
        nsname  (if (:ns &env)
                  (-> &env :ns :name)
                  (str *ns*))
        ident   (str "#" nsname "." (name rsym))]

    `(do
       (deftype ~rsym ~(into fields ['$meta '$extmap '$hash])
         ~@(if (:ns &env)
             (emit-impl-js rsym fields')
             (emit-impl-jvm rsym fields'))
         ~@impls

         ~@(when (:ns &env)
             ['cljs.core/IPrintWithWriter
              `(~'-pr-writer [~'this writer# opts#]
                             (let [pr-pair# (fn [keyval#]
                                              (cljs.core/pr-sequential-writer writer# (~'js* "cljs.core.pr_writer")
                                                                              "" " " "" opts# keyval#))]
                               (cljs.core/pr-sequential-writer
                                writer# pr-pair# ~(str ident "{") ", " "}" opts#
                                (concat [~@(for [f fields']
                                             `(vector ~(keyword f) (. ~'this ~(property-symbol f))))]
                                        (. ~'this ~'-$extmap)))))]))

       ~@(when-not (:ns &env)
           [`(defmethod print-method ~rsym [o# ^java.io.Writer w#]
               (.write w# ~(str "#" nsname "." (name rsym)))
               (print-method (into {} o#) w#))])

       (defn ~(with-meta (symbol (str "pos->" rsym))
                (assoc (meta rsym) :factory :positional))
         [~@fields']
         (new ~rsym ~@(conj fields nil nil nil)))

       (defn ~(with-meta (symbol (str 'map-> rsym))
                (assoc (meta rsym) :factory :map))
         [~param]
         (let [exclude# #{~@ks}
               extmap#  (reduce-kv (fn [acc# k# v#]
                                     (if (contains? exclude# k#)
                                       acc#
                                       (assoc acc# k# v#)))
                                   {}
                                   ~param)]
           (new ~rsym
                ~@(for [k ks]
                    `(get ~param ~k))
                nil
                (not-empty extmap#)
                nil)))
       ~rsym)))

(defmacro clone
  [ssym]
  (if (:ns &env)
    `(cljs.core/clone ~ssym)
    ssym))

(defmacro assoc!
  "A record specific update operation"
  [ssym & pairs]
  (if (:ns &env)
    (let [pairs (partition-all 2 pairs)]
      `(-> ~ssym
           ~@(map (fn [[ks vs]]
                    `(cljs.core/-assoc! ~ks ~vs))
                  pairs)))
    `(assoc ~ssym ~@pairs)))

(defmacro update!
  "A record specific update operation"
  [ssym ksym f & params]
  (if (:ns &env)
    (let [ssym (with-meta ssym {:tag 'js})]
      `(cljs.core/assoc! ~ssym ~ksym (~f (. ~ssym ~(property-symbol ksym)) ~@params)))
    `(update ~ssym ~ksym ~f ~@params)))

(defmacro define-properties!
  [rsym & properties]
  (let [rsym       (with-meta rsym {:tag 'js})
        self-sym   (gensym "self-")
        get-fn-sym (gensym "get-fn-")
        set-fn-sym (gensym "set-fn-")
        params-sym (gensym "params-")
        args-sym   (gensym "args-")]
    `(do
       ~@(for [params properties
               :let [pname  (get params :name)
                     get-fn (get params :get)
                     set-fn (get params :set)]]
           `(.defineProperty js/Object (.-prototype ~rsym) ~pname
                             (cljs.core/js-obj
                              "enumerable" true
                              "configurable" true
                              ~@(concat
                                 (when get-fn
                                   ["get" get-fn])
                                 (when set-fn
                                   ["set" set-fn]))))))))

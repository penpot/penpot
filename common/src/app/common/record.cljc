;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.record
  "A collection of helpers and macros for defien a penpot customized record types."
  (:refer-clojure :exclude [defrecord assoc! clone])
  #?(:cljs (:require-macros [app.common.record])))

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

#?(:clj
   (defn emit-extend
     [env tagname fields impls]
     (let [base-fields   (mapv #(with-meta % nil) fields)
           fields        (conj base-fields '$meta '$extmap (with-meta '$hash {:mutable true}))
           key-sym       (gensym "key-")
           val-sym       (gensym "val-")
           this-sym      (with-meta (gensym "this-") {:tag tagname})
           other-sym     (gensym "other-")
           pr-open       (str "#" (-> env :ns :name) "." (name tagname) "{")]
       (concat impls
               ['cljs.core/ICloneable
                `(~'-clone [~this-sym]
                  (new ~tagname ~@(generate-field-access this-sym val-sym fields)))

                'IHash
                `(~'-hash [~this-sym]
                  (caching-hash ~this-sym
                                (fn [coll#]
                                  (bit-xor
                                   ~(hash (str tagname))
                                   (cljs.core/hash-unordered-coll coll#)))
                                (. ~this-sym ~'-$hash)))

                'cljs.core/IEquiv
                `(~'-equiv [~this-sym ~other-sym]
                  (and (some? ~other-sym)
                       (identical? (.-constructor ~this-sym)
                                   (.-constructor ~other-sym))
                       ~@(map (fn [field]
                                `(= (.. ~this-sym ~(property-symbol field))
                                    (.. ~(with-meta other-sym {:tag tagname}) ~(property-symbol field))))
                              base-fields)
                       (= (. ~this-sym ~'-$extmap)
                          (. ~(with-meta other-sym {:tag tagname}) ~'-$extmap))))

                'cljs.core/IMeta
                `(~'-meta [~this-sym] (. ~this-sym ~'-$meta))

                'cljs.core/IWithMeta
                `(~'-with-meta [~this-sym ~val-sym]
                  (new ~tagname ~@(->> (replace {'$meta val-sym} fields)
                                       (generate-field-access this-sym val-sym))))

                'cljs.core/ILookup
                `(~'-lookup
                  ([~this-sym k#]
                   (cljs.core/-lookup ~this-sym k# nil))
                  ([~this-sym ~key-sym else#]
                   (case ~key-sym
                     ~@(mapcat (fn [f] [(keyword f) `(. ~this-sym ~(property-symbol f))])
                               base-fields)
                     (cljs.core/get (. ~this-sym ~'-$extmap) ~key-sym else#))))

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
                         base-fields))

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
                  (reduce (fn [ret# [~key-sym v#]] (f# ret# ~key-sym v#)) init# ~this-sym))

                'cljs.core/IPrintWithWriter
                `(~'-pr-writer [~this-sym writer# opts#]
                  (let [pr-pair# (fn [keyval#]
                                   (cljs.core/pr-sequential-writer writer# (~'js* "cljs.core.pr_writer")
                                                           "" " " "" opts# keyval#))]
                    (cljs.core/pr-sequential-writer
                     writer# pr-pair# ~pr-open ", " "}" opts#
                     (concat [~@(for [f base-fields]
                                  `(vector ~(keyword f) (. ~this-sym ~(property-symbol f))))]
                             (. ~this-sym ~'-$extmap)))))

                ]))))

(defmacro defrecord
  [rsym fields & impls]
  (let [param (gensym "param-")
        ks    (map keyword fields)]
    (if (:ns &env)
      `(do
         (deftype ~rsym ~(into fields ['$meta '$extmap '$hash]))
         (extend-type ~rsym ~@(emit-extend &env rsym fields impls))

         (defn ~(with-meta (symbol (str "pos->" rsym))
                  (assoc (meta rsym) :factory :positional))
           [~@fields]
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
         ~rsym)

      `(do
         (clojure.core/defrecord ~rsym ~fields ~@impls)
         (defn ~(with-meta (symbol (str "pos->" rsym))
                  (assoc (meta rsym) :factory :positional))
           [~@(map (fn [f] (vary-meta f dissoc :tag)) fields)]
           (new ~rsym ~@(conj fields nil nil)))))))

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
    `(cljs.core/-assoc! ~ssym ~ksym (~f (. ~ssym ~(property-symbol ksym)) ~@params))
    `(update ~ssym ~ksym ~f ~@params)))

(defmacro define-properties!
  [rsym & properties]
  (let [rsym (with-meta rsym {:tag 'js})]
    `(do
       ~@(for [params properties
               :let [pname  (get params :name)
                     get-fn (get params :get)
                     set-fn (get params :set)]]
           `(.defineProperty js/Object
                             (.-prototype ~rsym)
                             ~pname
                             (cljs.core/js-obj
                              "enumerable" true
                              "configurable" true
                              ~@(concat
                                 (when get-fn
                                   ["get" get-fn])
                                 (when set-fn
                                   ["set" set-fn]))))))))


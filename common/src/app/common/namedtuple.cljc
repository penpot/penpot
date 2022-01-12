(ns app.common.namedtuple
  "General purpose small immutable colections backed by native arrays."
  (:require
   #?(:cljs [cljs.core :as c]
      :clj  [clojure.core :as c])
   [clojure.string :as str]))

(defmacro deftuple
  [nsym fields]
  (let [sbuff   (symbol "buff")
        sfields (symbol "fields")
        smeta   (symbol "meta")
        params  (into [] (comp (map name)
                               (map gensym))
                      fields)
        tmpbuf  (gensym "buff")
        f1name  (symbol (str "make-" (str/lower-case (name nsym))))]
    `(do
       (deftype ~nsym [~sbuff ~smeta]
         ;; Object
         ;; (toString [_#] (pr-str ~sbuff))
         ;; (equiv [_# other#] false)

         c/ICloneable
         (~'-clone [_#] (new ~nsym (aclone ~sbuff) ~smeta))

         c/IWithMeta
         (~'-with-meta [this# new-meta#]
          (if (identical? new-meta# ~smeta)
            this#
            (new ~nsym ~sbuff new-meta#)))

         c/IMeta
         (~'-meta [_#] ~smeta)

         c/ICollection
         (~'-conj [_# entry#]
          (conj (vec ~sbuff) entry#))

         c/IEmptyableCollection
         (~'-empty [_#]
          (let [len#  (alength ~sbuff)
                buff# (make-array len#)]
            (dotimes [i# len#]
              (aset buff# i# nil))
            (new ~nsym buff# ~smeta)))

         c/ISeqable
         (~'-seq [_#] (seq ~sbuff))

         c/IAssociative
         (~'-assoc [this# k# v#]
          (let [index# (case k#
                         ~@(mapcat (fn [i n] [i n])
                                   fields
                                   (range (count fields)))
                         (throw (ex-info "invalid key" {})))
                buff#  (aclone ~sbuff)]
            (aset buff# index# v#)
            (new ~nsym buff# ~smeta)))

         (~'-contains-key? [_# k#]
          (case k#
            ~@(mapcat (fn [n] [n true]) fields)
            false))

         c/ICounted
         (~'-count [_#]
          (alength ~sbuff))

         c/ILookup
         (~'-lookup [_# k#]
          (case k#
            ~@(mapcat (fn [n i] [n `(aget ~sbuff ~i)])
                      fields
                      (range (count fields)))
            nil))

         (~'-lookup [this# k# not-found#]
          (or (case k#
                ~@(mapcat (fn [n i] [n `(aget ~sbuff ~i)])
                          fields
                          (range (count fields)))
                nil)
              not-found#))

         c/IPrintWithWriter
         (~'-pr-writer [this# writer# _#]
          (c/-write writer# (str "#penpot/namedtuple"
                                 (pr-str (into {} [~@(map (fn [k i]
                                                            [k `(aget ~sbuff ~i)])
                                                          fields
                                                          (range (count fields)))])))))


         c/IFn
         (~'-invoke [this# k#]
          (c/-lookup this# k#))

         (~'-invoke [this# k# not-found#]
          (c/-lookup this# k# not-found#)))

       (defn ~f1name ~params
         (let [~tmpbuf (js/Float64Array. 6)]
           ~@(map-indexed (fn [i x] `(aset ~tmpbuf ~i ~x)) params)
           ;; (js/console.log buff#)
           (new ~nsym ~tmpbuf nil)))

       )))



;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.pointer-map
  "Implements a map-like data structure that provides an entry point for
  store its content in a separated blob storage.

  By default it is not coupled with any storage mode and this is
  externalized using specified entry points for inspect the current
  available objects and hook the load data function.

  Each object is identified by an UUID and metadata hashmap which can
  be used to locate it in the storage layer. The hash code of the
  object is always the hash of the UUID. And it always performs
  equality by reference (I mean, you can't deep compare two pointers
  map without completelly loads its contents and compare the content.

  Each time you pass from not-modified to modified state, the id will
  be regenerated, and is resposability of the hashmap user to properly
  garbage collect the unused/unreferenced objects.

  For properly work it requires some dynamic vars binded to
  appropriate values:

  - *load-fn*: when you instantatiate an object by ID, on first access
    to the contents of the hash-map will try to load the real data by
    calling the function binded to that dynamic var.

  - *tracked*: when you instantiate an object or modify it, it is updated
    on the atom (holding a hashmap) binded to this var; if no binding is
    available, no tracking is performed. Tracking is needed for finally
    persist to external storage all modified objects.
  "

  (:require
   [app.common.fressian :as fres]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [clojure.core :as c]
   [clojure.data.json :as json])
  (:import
   clojure.lang.Counted
   clojure.lang.IDeref
   clojure.lang.IHashEq
   clojure.lang.IObj
   clojure.lang.IPersistentCollection
   clojure.lang.IPersistentMap
   clojure.lang.PersistentArrayMap
   clojure.lang.PersistentHashMap
   clojure.lang.Seqable
   java.util.List))

(def ^:dynamic *load-fn* nil)
(def ^:dynamic *tracked* nil)
(def ^:dynamic *metadata* {})

(declare create)

(defn create-tracked
  [& {:keys [inherit]}]
  (if inherit
    (atom (if *tracked* @*tracked* {}))
    (atom {})))

(defprotocol IPointerMap
  (get-id [_])
  (load! [_])
  (modified? [_])
  (loaded? [_])
  (clone [_]))

(deftype PointerMap [id mdata
                     ^:unsynchronized-mutable odata
                     ^:unsynchronized-mutable modified?
                     ^:unsynchronized-mutable loaded?]

  json/JSONWriter
  (-write [this writter options]
    (json/-write {:type "pointer"
                  :id (get-id this)
                  :meta (meta this)}
                 writter
                 options))

  IPointerMap
  (load! [_]
    (when-not *load-fn*
      (throw (UnsupportedOperationException. "load is not supported when *load-fn* is not bind")))

    (when-let [data (*load-fn* id)]
      (set! odata data))

    (set! loaded? true)

    (or odata {}))

  (modified? [_] modified?)
  (loaded? [_] loaded?)
  (get-id [_] id)

  (clone [this]
    (when-not loaded? (load! this))
    (let [mdata (assoc mdata :created-at (ct/now))
          id    (uuid/next)
          pmap  (PointerMap. id
                             mdata
                             odata
                             true
                             true)]
      (some-> *tracked* (swap! assoc id pmap))
      pmap))

  IDeref
  (deref [this]
    (when-not loaded? (load! this))
    (or odata {}))

  ;; We don't need to load the data for calculate hash because this
  ;; map has specific behavior
  IHashEq
  (hasheq [_] (hash id))

  Object
  (hashCode [this]
    (.hasheq ^IHashEq this))

  IObj
  (meta [_]
    (or mdata {}))

  (withMeta [_ mdata']
    (let [pmap (PointerMap. id mdata' odata (not= mdata mdata') loaded?)]
      (some-> *tracked* (swap! assoc id pmap))
      pmap))

  Seqable
  (seq [this]
    (when-not loaded? (load! this))
    (.seq ^Seqable odata))

  IPersistentCollection
  (equiv [this other]
    (identical? this other))

  IPersistentMap
  (cons [this o]
    (when-not loaded? (load! this))
    (if (map-entry? o)
      (assoc this (key o) (val o))
      (if (vector? o)
        (assoc this (nth o 0) (nth o 1))
        (throw (UnsupportedOperationException. "invalid arguments to cons")))))

  (empty [_]
    (create))

  (containsKey [this key]
    (when-not loaded? (load! this))
    (contains? odata key))

  (entryAt [this key]
    (when-not loaded? (load! this))
    (.entryAt ^IPersistentMap odata key))

  (valAt [this key]
    (when-not loaded? (load! this))
    (.valAt ^IPersistentMap odata key))

  (valAt [this key not-found]
    (when-not loaded? (load! this))
    (.valAt ^IPersistentMap odata key not-found))

  (assoc [this key val]
    (when-not loaded? (load! this))
    (let [odata' (assoc odata key val)]
      (if (identical? odata odata')
        this
        (let [mdata (assoc mdata :created-at (ct/now))
              id    (if modified? id (uuid/next))
              pmap  (PointerMap. id
                                 mdata
                                 odata'
                                 true
                                 true)]
          (some-> *tracked* (swap! assoc id pmap))
          pmap))))

  (assocEx [_ _ _]
    (throw (UnsupportedOperationException. "method not implemented")))

  (without [this key]
    (when-not loaded? (load! this))
    (let [odata' (dissoc odata key)]
      (if (identical? odata odata')
        this
        (let [mdata (assoc mdata :created-at (ct/now))
              id    (if modified? id (uuid/next))
              pmap  (PointerMap. id
                                 mdata
                                 odata'
                                 true
                                 true)]
          (some-> *tracked* (swap! assoc id pmap))
          pmap))))

  Counted
  (count [this]
    (when-not loaded? (load! this))
    (count odata))

  Iterable
  (iterator [this]
    (when-not loaded? (load! this))
    (.iterator ^Iterable odata)))

(defn create
  ([]
   (let [id    (uuid/next)
         mdata (assoc *metadata* :created-at (ct/now))
         pmap  (PointerMap. id mdata {} true true)]
     (some-> *tracked* (swap! assoc id pmap))
     pmap))
  ([id mdata]
   (let [pmap (PointerMap. id mdata {} false false)]
     (some-> *tracked* (swap! assoc id pmap))
     pmap)))

(defn pointer-map?
  [o]
  (instance? PointerMap o))

(defn wrap
  [data]
  (if (pointer-map? data)
    (do
      (some-> *tracked* (swap! assoc (get-id data) data))
      data)
    (let [mdata (assoc (meta data) :created-at (ct/now))
          id    (uuid/next)
          pmap  (PointerMap. id
                             mdata
                             data
                             true
                             true)]
      (some-> *tracked* (swap! assoc id pmap))
      pmap)))

(fres/add-handlers!
 {:name "penpot/pointer-map/v1"
  :class PointerMap
  :wfn (fn [n w o]
         (fres/write-tag! w n 3)
         (let [id (get-id o)]
           (fres/write-int! w (uuid/get-word-high id))
           (fres/write-int! w (uuid/get-word-low id)))
         (fres/begin-closed-list! w)
         (loop [items (-> o meta seq)]
           (when-let [^clojure.lang.MapEntry item (first items)]
             (fres/write-object! w (.key item) true)
             (fres/write-object! w (.val item))
             (recur (rest items))))
         (fres/end-list! w))
  :rfn (fn [r]
         (let [msb (fres/read-object! r)
               lsb (fres/read-object! r)
               kvs (fres/read-object! r)]
           (create (uuid/custom msb lsb)
                   (if (< (.size ^List kvs) 16)
                     (PersistentArrayMap. (.toArray ^List kvs))
                     (PersistentHashMap/create (seq kvs))))))})

(t/add-handlers!
 {:id "penpot/pointer"
  :class PointerMap
  :wfn (fn [val]
         [(get-id val) (meta val)])})



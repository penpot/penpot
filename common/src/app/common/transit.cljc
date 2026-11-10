;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.transit
  (:require
   #?(:clj  [datoteka.fs :as fs])
   [app.common.data :as d]
   [app.common.uri :as uri]
   [cognitect.transit :as t]
   [lambdaisland.uri :as luri]
   [linked.map :as lkm]
   [linked.set :as lks])
  #?(:clj
     (:import
      java.io.ByteArrayInputStream
      java.io.ByteArrayOutputStream
      java.io.File
      java.nio.file.Path
      java.time.Duration
      java.time.Instant
      java.time.OffsetDateTime
      lambdaisland.uri.URI
      linked.map.LinkedMap
      linked.set.LinkedSet)))

(def write-handlers (atom nil))
(def read-handlers (atom nil))
(def write-handler-map (atom nil))
(def read-handler-map (atom nil))

;; Readers are built lazily on first use and reused afterwards. Building a
;; reader on every call is expensive: transit merges the handlers, allocates
;; a read cache and prepares the marshaller machinery. A reader captures the
;; handler maps it was created with, so `add-handlers!` replaces them.
#?(:cljs
   (def ^:private ^:dynamic *json-reader* nil))

#?(:cljs
   (def ^:private ^:dynamic *json-verbose-reader* nil))

#?(:cljs
   (defn- rebuild-readers
     []
     (set! *json-reader*
           (delay (t/reader :json {:handlers @read-handler-map})))
     (set! *json-verbose-reader*
           (delay (t/reader :json-verbose {:handlers @read-handler-map})))))

;; A generic pointer; mainly used for deserialize backend pointer-map
;; instances that serializes to pointer but may in other ways.
(deftype Pointer [id metadata]
  #?@(:clj
      [clojure.lang.IObj
       (meta [_] metadata)
       (withMeta [_ meta] (Pointer. id meta))
       clojure.lang.IDeref
       (deref [_] id)]
      :cljs
      [cljs.core/IMeta
       (-meta [_] metadata)
       cljs.core/IWithMeta
       (-with-meta [_ meta] (Pointer. id meta))
       cljs.core/IDeref
       (-deref [_] id)]))

(defn pointer?
  [o]
  (instance? Pointer o))

;; --- HELPERS

#?(:clj
   (defn str->bytes
     ([^String s]
      (str->bytes s "UTF-8"))
     ([^String s, ^String encoding]
      (.getBytes s encoding))))

#?(:clj
   (defn- bytes->str
     ([^bytes data]
      (bytes->str data "UTF-8"))
     ([^bytes data, ^String encoding]
      (String. data encoding))))

(defn add-handlers!
  [& handlers]
  (letfn [(adapt-write-handler [{:keys [id class wfn]}]
            [class (t/write-handler (constantly id) wfn)])

          (adapt-read-handler [{:keys [id rfn]}]
            [id (t/read-handler rfn)])

          (merge-and-clean [m1 m2]
            (-> (merge m1 m2)
                (d/without-nils)))]

    (let [rhs (into {}
                    (comp
                     (filter :rfn)
                     (map adapt-read-handler))
                    handlers)
          whs (into {}
                    (comp
                     (filter :wfn)
                     (map adapt-write-handler))
                    handlers)
          cwh (swap! write-handlers merge-and-clean whs)
          crh (swap! read-handlers merge-and-clean rhs)]

      (reset! write-handler-map #?(:clj (t/write-handler-map cwh) :cljs cwh))
      (reset! read-handler-map #?(:clj (t/read-handler-map crh) :cljs crh))
      ;; A reader captures the handler maps, so build new ones from the maps
      ;; just stored. A handler registered after a read then applies on the
      ;; next read.
      #?(:cljs (rebuild-readers))
      nil)))

;; --- HANDLERS

#?(:cljs
   (defn- keyword-reader
     "Return a reader that gives the same keyword instance for each name."
     []
     (let [cache (js/Map.)]
       (fn [value]
         (or (.get cache value)
             (let [kw (keyword value)]
               (.set cache value kw)
               kw))))))

#?(:cljs
   (def ^:private uuid-cache-size
     "Twice the ids of a very large page (~60k shapes), so decoding one
     page does not rotate the cache."
     131072))

#?(:cljs (def ^:private recent-uuids (js/Map.)))
#?(:cljs (def ^:private previous-uuids (js/Map.)))

#?(:cljs
   (defn clear-uuid-cache
     "Forget the cached uuids, so the ids of a closed file can be freed."
     []
     (set! recent-uuids (js/Map.))
     (set! previous-uuids (js/Map.))))

#?(:cljs
   (defn- read-uuid
     "Give the same uuid instance for each value. Entries live in two
     generations: when the recent one is full it becomes the previous one
     and the older one is dropped, so the cache stays bounded."
     [value]
     (or (.get recent-uuids value)
         (let [id (or (.get previous-uuids value)
                      (parse-uuid value))]
           (when (>= (.-size recent-uuids) uuid-cache-size)
             (set! previous-uuids recent-uuids)
             (set! recent-uuids (js/Map.)))
           (.set recent-uuids value id)
           id))))

(add-handlers!
 #?@(:clj
     [{:id "file"
       :class File
       :wfn str
       :rfn identity}
      {:id "path"
       :class Path
       :wfn str
       :rfn fs/path}])

 #?(:cljs
    {:id "n"
     :rfn (fn [value]
            (js/parseInt value 10))})

 #?(:cljs
    {:id "u"
     :rfn read-uuid})

 #?(:cljs
    {:id ":"
     :rfn (keyword-reader)})

 {:id "ordered-map"
  :class #?(:clj LinkedMap :cljs lkm/LinkedMap)
  :wfn vec
  :rfn #(into lkm/empty-linked-map %)}

 {:id "ordered-set"
  :class #?(:clj LinkedSet :cljs lks/LinkedSet)
  :wfn vec
  :rfn #(into lks/empty-linked-set %)}

 #?(:clj
    {:id "duration"
     :class Duration
     :rfn (fn [v] (Duration/ofMillis v))
     :wfn inst-ms})

 {:id "m"
  :class #?(:clj Instant :cljs js/Date)
  :rfn (fn [v]
         #?(:clj (-> (Long/parseLong v)
                     (Instant/ofEpochMilli))
            :cljs (new js/Date (js/parseInt v 10))))
  :wfn (comp str inst-ms)}

 {:id "penpot/pointer"
  :class Pointer
  :rfn (fn [[id meta]]
         (Pointer. id meta))}

 #?(:clj
    {:id "m"
     :class OffsetDateTime
     :wfn (comp str inst-ms)})

 {:id "uri"
  :class #?(:clj URI :cljs luri/URI)
  :rfn uri/uri
  :wfn str})

;; --- Low-Level Api

#?(:clj
   (defn reader
     ([istream]
      (reader istream nil))
     ([istream {:keys [type] :or {type :json}}]
      (t/reader istream type {:handlers @read-handler-map}))))

#?(:clj
   (defn writer
     ([ostream]
      (writer ostream nil))
     ([ostream {:keys [type] :or {type :json}}]
      (t/writer ostream type {:handlers @write-handler-map}))))

#?(:clj
   (defn read!
     [reader]
     (t/read reader)))

#?(:clj
   (defn write!
     [writer data]
     (t/write writer data)))

;; --- High-Level Api

#?(:clj
   (defn encode
     ([data] (encode data nil))
     ([data opts]
      (with-open [out (ByteArrayOutputStream.)]
        (t/write (writer out opts) data)
        (.toByteArray out)))))

#?(:clj
   (defn decode
     ([data] (decode data nil))
     ([data opts]
      (with-open [input (ByteArrayInputStream. ^bytes data)]
        (t/read (reader input opts))))))

#?(:cljs
   (defn- is-date-like?
     [obj]
     (and ^boolean (some? obj)
          ^boolean (fn? (.-getTime obj))
          ^boolean (some? (.getTime obj)))))

#_:clj-kondo/ignore
(def ^:private date-write-handler
  (t/write-handler (constantly "m")
                   (comp str inst-ms)))

(defn encode-str
  ([data] (encode-str data nil))
  ([data opts]
   #?(:cljs
      (let [type   (:type opts :json)
            params {:handlers @write-handler-map
                    ;; NOTE: this is necessary because the plugin
                    ;; secure context alters the js/Date constructor
                    :handlerForForeign (fn [x _]
                                         (if (is-date-like? x)
                                           date-write-handler
                                           nil))}
            params (if (:with-meta opts)
                     (assoc params :transform t/write-meta)
                     params)
            writer (t/writer type params)]
        (t/write writer data))
      :clj
      (->> (encode data opts)
           (bytes->str)))))

(defn decode-str
  ([data] (decode-str data nil))
  ([data opts]
   #?(:cljs
      (let [reader (case (:type opts :json)
                     :json         @*json-reader*
                     :json-verbose @*json-verbose-reader*)]
        (try
          (t/read reader data)
          (catch :default cause
            ;; The reader clears its internal read cache after a successful
            ;; read but not after a failed one, so a reused reader would
            ;; resolve cache codes against the failed read. Clear it before
            ;; propagating the error.
            (when-let [cache (.-cache reader)]
              (.clear cache))
            (throw cause))))
      :clj
      (-> (str->bytes data)
          (decode opts)))))

(defn transit?
  "Checks if a string can be decoded with transit"
  [v]
  (try
    (-> v decode-str nil? not)
    (catch #?(:cljs js/SyntaxError :clj Exception) _e
      false)))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.transit
  (:require
   #?(:clj  [datoteka.fs :as fs])
   #?(:cljs ["luxon" :as lxn])
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
      nil)))

;; --- HANDLERS

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
     :rfn parse-uuid})

 {:id "ordered-map"
  :class #?(:clj LinkedMap :cljs lkm/LinkedMap)
  :wfn vec
  :rfn #(into lkm/empty-linked-map %)}

 {:id "ordered-set"
  :class #?(:clj LinkedSet :cljs lks/LinkedSet)
  :wfn vec
  :rfn #(into lks/empty-linked-set %)}

 {:id "duration"
  :class #?(:clj Duration :cljs lxn/Duration)
  :rfn (fn [v]
         #?(:clj  (Duration/ofMillis v)
            :cljs (.fromMillis ^js lxn/Duration v)))
  :wfn inst-ms}

 {:id "m"
  :class #?(:clj Instant :cljs lxn/DateTime)
  :rfn (fn [v]
         #?(:clj  (-> (Long/parseLong v)
                      (Instant/ofEpochMilli))
            :cljs (let [ms (js/parseInt v 10)]
                    (.fromMillis ^js lxn/DateTime ms))))
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

(defn encode-str
  ([data] (encode-str data nil))
  ([data opts]
   #?(:cljs
      (let [type   (:type opts :json)
            params {:handlers @write-handler-map}
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
      (let [type   (:type opts :json)
            params {:handlers @read-handler-map}
            reader (t/reader type params)]
        (t/read reader data))
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

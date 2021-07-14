;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.transit
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [cognitect.transit :as t]
   [linked.core :as lk]
   [linked.set :as lks]
   #?(:cljs ["luxon" :as lxn]))
  #?(:clj
     (:import
      app.common.geom.matrix.Matrix
      app.common.geom.point.Point
      java.io.ByteArrayInputStream
      java.io.ByteArrayOutputStream
      java.io.File
      java.time.Instant
      java.time.Duration
      java.time.OffsetDateTime
      linked.set.LinkedSet)))

;; --- MISC

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

#?(:clj
   (def ^:private file-write-handler
     (t/write-handler
      (constantly "file")
      (fn [v] (str v)))))

#?(:cljs
   (def bigint-read-handler
     (t/read-handler
      (fn [value]
        (js/parseInt value 10)))))

#?(:cljs
   (def uuid-read-handler
     (t/read-handler uuid)))

;; --- GEOM

(def point-write-handler
  (t/write-handler
   (constantly "point")
   (fn [v] (into {} v))))

(def point-read-handler
  (t/read-handler gpt/map->Point))

(def matrix-write-handler
  (t/write-handler
   (constantly "matrix")
   (fn [v] (into {} v))))

(def matrix-read-handler
  (t/read-handler gmt/map->Matrix))

;; --- ORDERED SET

(def ordered-set-write-handler
  (t/write-handler
   (constantly "ordered-set")
   (fn [v] (vec v))))

(def ordered-set-read-handler
  (t/read-handler #(into (lk/set) %)))

;; --- DURATION

(def duration-read-handler
  #?(:cljs (t/read-handler #(.fromMillis ^js lxn/Duration %))
     :clj  (t/read-handler #(Duration/ofMillis %))))

(def duration-write-handler
  (t/write-handler
   (constantly "duration")
   (fn [v] (inst-ms v))))

;; --- TIME

(def ^:private instant-read-handler
  #?(:clj
     (t/read-handler
      (fn [v] (-> (Long/parseLong v)
                  (Instant/ofEpochMilli))))
     :cljs
     (t/read-handler
      (fn [v]
        (let [ms (js/parseInt v 10)]
          (.fromMillis ^js lxn/DateTime ms))))))

(def ^:private instant-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (inst-ms v)))))

;; --- HANDLERS

(def +read-handlers+
  {"matrix"      matrix-read-handler
   "ordered-set" ordered-set-read-handler
   "point"       point-read-handler
   "duration"    duration-read-handler
   "m"           instant-read-handler
   #?@(:cljs ["n" bigint-read-handler
              "u" uuid-read-handler])
   })

(def +write-handlers+
  #?(:clj
     {Matrix         matrix-write-handler
      Point          point-write-handler
      Instant        instant-write-handler
      LinkedSet      ordered-set-write-handler

      File           file-write-handler
      OffsetDateTime instant-write-handler}
     :cljs
     {gmt/Matrix     matrix-write-handler
      gpt/Point      point-write-handler
      lxn/DateTime   instant-write-handler
      lxn/Duration   duration-write-handler
      lks/LinkedSet  ordered-set-write-handler}
     ))

;; --- Low-Level Api

#?(:clj
   (defn reader
     ([istream]
      (reader istream nil))
     ([istream {:keys [type] :or {type :json}}]
      (t/reader istream type {:handlers +read-handlers+}))))

#?(:clj
   (defn writer
     ([ostream]
      (writer ostream nil))
     ([ostream {:keys [type] :or {type :json}}]
      (t/writer ostream type {:handlers +write-handlers+}))))
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
      (let [t (:type opts :json)
            w (t/writer t {:handlers +write-handlers+})]
        (t/write w data))
      :clj
      (->> (encode data opts)
           (bytes->str)))))

(defn decode-str
  ([data] (decode-str data nil))
  ([data opts]
   #?(:cljs
      (let [t (:type opts :json)
            r (t/reader t {:handlers +read-handlers+})]
        (t/read r data))
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

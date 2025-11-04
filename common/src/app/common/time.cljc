;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

#_{:clj-kondo/ignore [:unused-namespace]}
(ns app.common.time
  "Minimal cross-platoform date time api for specific use cases on types
  definition and other common code."
  (:refer-clojure :exclude [inst?])
  (:require
   #?@(:cljs [["date-fns/format$default" :as dfn-format]
              ["date-fns/formatISO$default" :as dfn-format-iso]
              ["date-fns/setDefaultOptions$default" :as dfn-set-default-options]
              ["date-fns/differenceInMilliseconds$default" :as dfn-diff]
              ["date-fns/formatDistanceToNowStrict$default" :as dfn-distance-to-now]
              ["date-fns/add$default" :as dfn-add]
              ["date-fns/sub$default" :as dfn-sub]
              ["date-fns/parseISO$default" :as dfn-parse-iso]
              ["date-fns/locale/ar-SA$default" :as dfn-ar]
              ["date-fns/locale/ca$default" :as dfn-ca]
              ["date-fns/locale/cs$default" :as dfn-cs]
              ["date-fns/locale/de$default" :as dfn-de]
              ["date-fns/locale/el$default" :as dfn-el]
              ["date-fns/locale/en-US$default" :as df-en-us]
              ["date-fns/locale/es$default" :as dfn-es]
              ["date-fns/locale/eu$default" :as dfn-eu]
              ["date-fns/locale/fa-IR$default" :as dfn-fa-ir]
              ["date-fns/locale/fr$default" :as dfn-fr]
              ["date-fns/locale/gl$default" :as dfn-gl]
              ["date-fns/locale/he$default" :as dfn-he]
              ["date-fns/locale/hr$default" :as dfn-hr]
              ["date-fns/locale/id$default" :as dfn-id]
              ["date-fns/locale/it$default" :as dfn-it]
              ["date-fns/locale/ja$default" :as dfn-ja]
              ["date-fns/locale/ko$default" :as dfn-ko]
              ["date-fns/locale/lv$default" :as dfn-lv]
              ["date-fns/locale/nb$default" :as dfn-nb]
              ["date-fns/locale/nl$default" :as dfn-nl]
              ["date-fns/locale/pl$default" :as dfn-pl]
              ["date-fns/locale/pt$default" :as dfn-pt]
              ["date-fns/locale/pt-BR$default" :as dfn-pt-br]
              ["date-fns/locale/ro$default" :as dfn-ro]
              ["date-fns/locale/ru$default" :as dfn-ru]
              ["date-fns/locale/tr$default" :as dfn-tr]
              ["date-fns/locale/uk$default" :as dfn-uk]
              ["date-fns/locale/zh-CN$default" :as dfn-zh-cn]])
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.openapi :as-alias oapi]
   [cuerdas.core :as str])
  #?(:clj
     (:import
      java.time.Clock
      java.time.Duration
      java.time.Instant
      java.time.OffsetDateTime
      java.time.ZoneId
      java.time.ZonedDateTime
      java.time.format.DateTimeFormatter
      java.time.temporal.ChronoUnit
      java.time.temporal.Temporal
      java.time.temporal.TemporalAmount
      java.time.temporal.TemporalUnit)))

#?(:clj (def ^:dynamic *clock* (Clock/systemDefaultZone)))

(defn now
  []
  #?(:clj (Instant/now *clock*)
     :cljs (new js/Date)))

;; --- DURATION

(defn- resolve-temporal-unit
  [o]
  (case o
    (:nanos :nano)
    #?(:clj ChronoUnit/NANOS
       :cljs (throw (js/Error. "not supported nanos")))

    (:micros :microsecond :micro)
    #?(:clj ChronoUnit/MICROS
       :cljs (throw (js/Error. "not supported nanos")))

    (:millis :millisecond :milli)
    #?(:clj ChronoUnit/MILLIS
       :cljs "millisecond")

    (:seconds :second)
    #?(:clj ChronoUnit/SECONDS
       :cljs "second")

    (:minutes :minute)
    #?(:clj ChronoUnit/MINUTES
       :cljs "minute")

    (:hours :hour)
    #?(:clj ChronoUnit/HOURS
       :cljs "hour")

    (:days :day)
    #?(:clj ChronoUnit/DAYS
       :cljs "day")))

(defn temporal-unit
  [o]
  #?(:clj (if (instance? TemporalUnit o) o (resolve-temporal-unit o))
     :cljs (resolve-temporal-unit o)))

#?(:clj
   (defn- obj->duration
     [params]
     (reduce-kv (fn [o k v]
                  (.plus ^Duration o ^long v ^TemporalUnit (temporal-unit k)))
                (Duration/ofMillis 0)
                params)))

#?(:clj
   (defn duration?
     [o]
     (instance? Duration o)))

#?(:clj
   (defn duration
     [ms-or-obj]
     (cond
       (string? ms-or-obj)
       (Duration/parse (str "PT" ms-or-obj))

       (duration? ms-or-obj)
       ms-or-obj

       (integer? ms-or-obj)
       (Duration/ofMillis ms-or-obj)

       :else
       (obj->duration ms-or-obj))))

#?(:clj
   (defn parse-duration
     [s]
     (duration s)))

#?(:clj
   (defn format-duration
     [o]
     (str/lower (subs (str o) 2))))

;; --- INSTNANT & DATETIME

(defn is-after?
  "Analgous to: da > db"
  [da db]
  (let [result (compare da db)]
    (cond
      (neg? result) false
      (zero? result) false
      :else true)))

(defn is-before?
  [da db]
  (let [result (compare da db)]
    (cond
      (neg? result)   true
      (zero? result)  false
      :else false)))

(defn inst?
  [o]
  #?(:clj (instance? Instant o)
     :cljs (instance? js/Date o)))

(defn seconds
  [d]
  (-> d inst-ms (/ 1000) int))

(defn format-inst
  ([v] (format-inst v :iso))
  ([v fmt]
   (case fmt
     (:iso :iso8601)
     #?(:clj (.format DateTimeFormatter/ISO_INSTANT ^Instant v)
        :cljs (dfn-format-iso v))

     :iso-date
     #?(:clj (.format DateTimeFormatter/ISO_LOCAL_DATE
                      ^ZonedDateTime (ZonedDateTime/ofInstant v (ZoneId/of "UTC")))
        :cljs (dfn-format-iso v #js {:representation "date"}))

     (:rfc1123 :http)
     #?(:clj (.format DateTimeFormatter/RFC_1123_DATE_TIME
                      ^ZonedDateTime (ZonedDateTime/ofInstant v (ZoneId/of "UTC")))
        :cljs (dfn-format v "EEE, dd LLL yyyy HH:mm:ss 'GMT'"))

     #?@(:cljs [:time-24-simple
                (dfn-format v "HH:mm")

                ;; DEPRECATED
                :date-full
                (dfn-format v "PPP")

                :localized-date
                (dfn-format v "PPP")

                :localized-time
                (dfn-format v "p")

                :localized-date-time
                (dfn-format v "PPPp")

                (if (string? fmt)
                  (dfn-format v fmt)
                  (throw (js/Error. "unpexted format")))]))))

#?(:cljs
   (def locales
     #js {:ar dfn-ar
          :ca dfn-ca
          :de dfn-de
          :el dfn-el
          :en df-en-us
          :en_us df-en-us
          :es dfn-es
          :es_es dfn-es
          :fa dfn-fa-ir
          :fa_ir dfn-fa-ir
          :fr dfn-fr
          :he dfn-he
          :pt dfn-pt
          :pt_pt dfn-pt
          :pt_br dfn-pt-br
          :ro dfn-ro
          :ru dfn-ru
          :tr dfn-tr
          :zh-cn dfn-zh-cn
          :nl dfn-nl
          :eu dfn-eu
          :gl dfn-gl
          :hr dfn-hr
          :it dfn-it
          :nb dfn-nb
          :nb_no dfn-nb
          :pl dfn-pl
          :id dfn-id
          :uk dfn-uk
          :cs dfn-cs
          :lv dfn-lv
          :ko dfn-ko
          :ja dfn-ja
          :ja_jp dfn-ja}))

#?(:cljs
   (defn timeago
     [v]
     (when v
       (dfn-distance-to-now v #js {:includeSeconds true
                                   :addSuffix true}))))

(defn inst
  [s]
  (cond
    (nil? s)
    s

    (inst? s)
    s

    (int? s)
    #?(:clj (Instant/ofEpochMilli s)
       :cljs (new js/Date s))

    (string? s)
    #?(:clj (Instant/from (.parse DateTimeFormatter/ISO_DATE_TIME ^String s))
       :cljs (dfn-parse-iso s))

    :else
    (throw (ex-info "invalid parameters" {}))))

#?(:clj
   (defn truncate
     [o unit]
     (let [unit (temporal-unit unit)]
       (cond
         (inst? o)
         (.truncatedTo ^Instant o ^TemporalUnit unit)

         (instance? Duration o)
         (.truncatedTo ^Duration o ^TemporalUnit unit)

         :else
         (throw (IllegalArgumentException. "only instant and duration allowed"))))))

(defn plus
  [d ta]
  (let [ta #?(:clj (duration ta) :cljs ta)]
    (cond
      #?@(:clj [(duration? d) (.plus ^Duration d ^TemporalAmount ta)])

      #?(:cljs (inst? d)
         :clj (instance? Temporal d))
      #?(:cljs (dfn-add d ta)
         :clj  (.plus ^Temporal d ^Duration ta))

      :else
      (throw #?(:clj (UnsupportedOperationException. "unsupported type")
                :cljs (js/Error. "unsupported type"))))))

(defn minus
  [d ta]
  (let [ta #?(:clj (duration ta) :cljs ta)]
    (cond
      #?@(:clj [(duration? d) (.minus ^Duration d ^TemporalAmount ta)])

      #?(:cljs (inst? d) :clj (instance? Temporal d))
      #?(:cljs (dfn-sub d ta)
         :clj  (.minus ^Temporal d ^Duration ta))

      :else
      (throw #?(:clj (UnsupportedOperationException. "unsupported type")
                :cljs (js/Error. "unsupported type"))))))

(defn in-future
  [v]
  (plus (now) v))

(defn in-past
  [v]
  (minus (now) v))

#?(:clj
   (defn diff
     [t1 t2]
     (Duration/between t1 t2)))

#?(:cljs
   (defn diff-ms
     [t1 t2]
     (dfn-diff t2 t1)))

#?(:cljs
   (defn set-default-locale!
     [locale]
     (when-let [locale (unchecked-get locales locale)]
       (dfn-set-default-options #js {:locale locale}))))

;; --- HELPERS

#?(:clj
   (defn tpoint
     "Create a measurement checkpoint for time measurement of potentially
     asynchronous flow."
     []
     (let [p1 (System/nanoTime)]
       #(duration {:nanos (- (System/nanoTime) p1)}))))

#?(:cljs
   (defn tpoint-ms
     "Create a measurement checkpoint for time measurement of potentially
     asynchronous flow."
     []
     (let [p1 (.now js/performance)]
       #(- (.now js/performance) p1))))

;; --- EXTENSIONS

#?(:clj
   (extend-protocol clojure.core/Inst
     Duration
     (inst-ms* [v] (.toMillis ^Duration v))

     java.nio.file.attribute.FileTime
     (inst-ms* [v] (.toMillis ^java.nio.file.attribute.FileTime v))

     OffsetDateTime
     (inst-ms* [v] (.toEpochMilli (.toInstant ^OffsetDateTime v)))

     Instant
     (inst-ms* [v] (.toEpochMilli ^Instant v))))

#?(:clj
   (defmethod print-method Duration
     [o w]
     (print-dup o w)))

#?(:clj
   (defmethod print-dup Duration
     [mv ^java.io.Writer writer]
     (.write writer (str "#penpot/duration \"" (str/lower (subs (str mv) 2)) "\""))))

#?(:clj
   (defmethod print-method Instant
     [o w]
     (print-dup o w)))

#?(:clj
   (defmethod print-dup Instant
     [mv ^java.io.Writer writer]
     (.write writer (str "#penpot/inst \"" (format-inst mv) "\""))))

(def schema:inst
  (sm/register!
   {:type ::inst
    :pred inst?
    :type-properties
    {:error/message "should be an instant"
     :title "instant"
     :decode/string inst
     :encode/string format-inst
     :decode/json inst
     :encode/json format-inst
     :gen/gen (->> (sg/small-int :min 0)
                   (sg/fmap (fn [i] (in-past i))))
     ::oapi/type "string"
     ::oapi/format "iso"}}))

#?(:clj
   (def schema:duration
     (sm/register!
      {:type ::duration
       :pred duration?
       :type-properties
       {:error/message "should be a duration"
        :gen/gen (->> (sg/small-int :min 0)
                      (sg/fmap duration))
        :title "duration"
        :decode/string parse-duration
        :encode/string format-duration
        :decode/json parse-duration
        :encode/json format-duration
        ::oapi/type "string"
        ::oapi/format "duration"}})))

#?(:cljs
   (extend-protocol cljs.core/IEncodeJS
     js/Date
     (-clj->js [x] x)))

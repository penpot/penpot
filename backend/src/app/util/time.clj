;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.time
  (:require
   [app.common.exceptions :as ex]
   [clojure.spec.alpha :as s]
   [cognitect.transit :as t])
  (:import
   java.time.Instant
   java.time.OffsetDateTime
   java.time.Duration
   java.util.Date
   java.time.temporal.TemporalAmount
   org.apache.logging.log4j.core.util.CronExpression))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instant & Duration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn from-string
  [s]
  {:pre [(string? s)]}
  (Instant/parse s))

(defn instant?
  [v]
  (instance? Instant v))

(defn is-after?
  [da db]
  (.isAfter ^Instant da ^Instant db))

(defn is-before?
  [da db]
  (.isBefore ^Instant da ^Instant db))

(defn plus
  [d ta]
  (.plus d ^TemporalAmount ta))

(defn minus
  [d ta]
  (.minus d ^TemporalAmount ta))

(defn- obj->duration
  [{:keys [days minutes seconds hours nanos millis]}]
  (cond-> (Duration/ofMillis (if (int? millis) ^long millis 0))
    (int? days) (.plusDays ^long days)
    (int? hours) (.plusHours ^long hours)
    (int? minutes) (.plusMinutes ^long minutes)
    (int? seconds) (.plusSeconds ^long seconds)
    (int? nanos) (.plusNanos ^long nanos)))

(defn duration?
  [v]
  (instance? Duration v))

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
    (obj->duration ms-or-obj)))

(defn now
  []
  (Instant/now))

(defn in-future
  [v]
  (plus (now) (duration v)))

(defn in-past
  [v]
  (minus (now) (duration v)))

(defn duration-between
  [t1 t2]
  (Duration/between t1 t2))

(defn instant
  [ms]
  (Instant/ofEpochMilli ms))

(defn parse-duration
  [s]
  (Duration/parse s))

(extend-protocol clojure.core/Inst
  java.time.Duration
  (inst-ms* [v] (.toMillis ^Duration v)))

(defmethod print-method Duration
  [mv ^java.io.Writer writer]
  (.write writer (str "#app/duration \"" (subs (str mv) 2) "\"")))

(defmethod print-dup Duration [o w]
  (print-method o w))

(letfn [(conformer [v]
          (cond
            (duration? v) v

            (string? v)
            (try
              (duration v)
              (catch java.time.format.DateTimeParseException _e
                ::s/invalid))

            :else
            ::s/invalid))
        (unformer [v]
          (subs (str v) 2))]
  (s/def ::duration (s/conformer conformer unformer)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cron Expression
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Cron expressions are comprised of 6 required fields and one
;; optional field separated by white space. The fields respectively
;; are described as follows:
;;
;; Field Name          Allowed Values	 	Allowed Special Characters
;; Seconds                 0-59                    , - * /
;; Minutes                 0-59                    , - * /
;; Hours                   0-23                    , - * /
;; Day-of-month	           1-31                  , - * ? / L W
;; Month               0-11 or JAN-DEC             , - * /
;; Day-of-Week          1-7 or SUN-SAT           , - * ? / L #
;; Year (Optional)    empty, 1970-2199             , - * /
;;
;; The '*' character is used to specify all values. For example, "*"
;; in the minute field means "every minute".
;;
;; The '?' character is allowed for the day-of-month and day-of-week
;; fields. It is used to specify 'no specific value'. This is useful
;; when you need to specify something in one of the two fields, but
;; not the other.
;;
;; The '-' character is used to specify ranges For example "10-12" in
;; the hour field means "the hours 10, 11 and 12".
;;
;; The ',' character is used to specify additional values. For
;; example "MON,WED,FRI" in the day-of-week field means "the days
;; Monday, Wednesday, and Friday".
;;
;; The '/' character is used to specify increments. For example "0/15"
;; in the seconds field means "the seconds 0, 15, 30, and
;; 45". And "5/15" in the seconds field means "the seconds 5, 20, 35,
;; and 50". Specifying '*' before the '/' is equivalent to specifying
;; 0 is the value to start with. Essentially, for each field in the
;; expression, there is a set of numbers that can be turned on or
;; off. For seconds and minutes, the numbers range from 0 to 59. For
;; hours 0 to 23, for days of the month 0 to 31, and for months 0 to
;; 11 (JAN to DEC). The "/" character simply helps you turn on
;; every "nth" value in the given set. Thus "7/6" in the month field
;; only turns on month "7", it does NOT mean every 6th month, please
;; note that subtlety.
;;
;; The 'L' character is allowed for the day-of-month and day-of-week
;; fields. This character is short-hand for "last", but it has
;; different meaning in each of the two fields. For example, the
;; value "L" in the day-of-month field means "the last day of the
;; month" - day 31 for January, day 28 for February on non-leap
;; years. If used in the day-of-week field by itself, it simply
;; means "7" or "SAT". But if used in the day-of-week field after
;; another value, it means "the last xxx day of the month" - for
;; example "6L" means "the last friday of the month". You can also
;; specify an offset from the last day of the month, such as "L-3"
;; which would mean the third-to-last day of the calendar month. When
;; using the 'L' option, it is important not to specify lists, or
;; ranges of values, as you'll get confusing/unexpected results.
;;
;; The 'W' character is allowed for the day-of-month field. This
;; character is used to specify the weekday (Monday-Friday) nearest
;; the given day. As an example, if you were to specify "15W" as the
;; value for the day-of-month field, the meaning is: "the nearest
;; weekday to the 15th of the month". So if the 15th is a Saturday,
;; the trigger will fire on Friday the 14th. If the 15th is a Sunday,
;; the trigger will fire on Monday the 16th. If the 15th is a Tuesday,
;; then it will fire on Tuesday the 15th. However if you specify "1W"
;; as the value for day-of-month, and the 1st is a Saturday, the
;; trigger will fire on Monday the 3rd, as it will not 'jump' over the
;; boundary of a month's days. The 'W' character can only be specified
;; when the day-of-month is a single day, not a range or list of days.
;;
;; The 'L' and 'W' characters can also be combined for the
;; day-of-month expression to yield 'LW', which translates to "last
;; weekday of the month".
;;
;; The '#' character is allowed for the day-of-week field. This
;; character is used to specify "the nth" XXX day of the month. For
;; example, the value of "6#3" in the day-of-week field means the
;; third Friday of the month (day 6 = Friday and "#3" = the 3rd one in
;; the month). Other examples: "2#1" = the first Monday of the month
;; and "4#5" = the fifth Wednesday of the month. Note that if you
;; specify "#5" and there is not 5 of the given day-of-week in the
;; month, then no firing will occur that month. If the '#' character
;; is used, there can only be one expression in the day-of-week
;; field ("3#1,6#3" is not valid, since there are two expressions).
;;
;; The legal characters and the names of months and days of the week
;; are not case sensitive.

(defn cron
  "Creates an instance of CronExpression from string."
  [s]
  (try
    (CronExpression. s)
    (catch java.text.ParseException e
      (ex/raise :type :parse
                :code :invalid-cron-expression
                :cause e
                :context {:expr s}))))

(defn cron?
  [v]
  (instance? CronExpression v))

(defn next-valid-instant-from
  [^CronExpression cron ^Instant now]
  (s/assert cron? cron)
  (.toInstant (.getNextValidTimeAfter cron (Date/from now))))

(defmethod print-method CronExpression
  [mv ^java.io.Writer writer]
  (.write writer (str "#app/cron \"" (.toString ^CronExpression mv) "\"")))

(defmethod print-dup CronExpression
  [o w]
  (print-ctor o (fn [o w] (print-dup (.toString ^CronExpression o) w)) w))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Serialization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare from-string)

(def ^:private instant-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli ^Instant v)))))

(def ^:private offset-datetime-write-handler
  (t/write-handler
   (constantly "m")
   (fn [v] (str (.toEpochMilli (.toInstant ^OffsetDateTime v))))))

(def ^:private read-handler
  (t/read-handler
   (fn [v] (-> (Long/parseLong v)
               (Instant/ofEpochMilli)))))

(def +read-handlers+
  {"m" read-handler})

(def +write-handlers+
  {Instant instant-write-handler
   OffsetDateTime offset-datetime-write-handler})

(defmethod print-method Instant
  [mv ^java.io.Writer writer]
  (.write writer (str "#app/instant \"" (.toString ^Instant mv) "\"")))

(defmethod print-dup Instant [o w]
  (print-method o w))



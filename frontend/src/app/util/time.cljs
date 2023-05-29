;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.time
  (:require
   ["date-fns/format" :default dateFnsFormat]
   ["date-fns/formatDistanceToNowStrict" :default dateFnsFormatDistanceToNowStrict]
   ["date-fns/locale/ar-SA" :default dateFnsLocalesAr]
   ["date-fns/locale/ca" :default dateFnsLocalesCa]
   ["date-fns/locale/de" :default dateFnsLocalesDe]
   ["date-fns/locale/el" :default dateFnsLocalesEl]
   ["date-fns/locale/en-US" :default dateFnsLocalesEnUs]
   ["date-fns/locale/es" :default dateFnsLocalesEs]
   ["date-fns/locale/fa-IR" :default dateFnsLocalesFa]
   ["date-fns/locale/fr" :default dateFnsLocalesFr]
   ["date-fns/locale/he" :default dateFnsLocalesHe]
   ["date-fns/locale/pt-BR" :default dateFnsLocalesPtBr]
   ["date-fns/locale/ro" :default dateFnsLocalesRo]
   ["date-fns/locale/ru" :default dateFnsLocalesRu]
   ["date-fns/locale/tr" :default dateFnsLocalesTr]
   ["date-fns/locale/zh-CN" :default dateFnsLocalesZhCn]
   [app.common.data.macros :as dm]
   [app.common.time :as common-time]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(dm/export common-time/DateTime)
(dm/export common-time/Duration)

(defprotocol ITimeMath
  (plus [_ o])
  (minus [_ o]))

(defprotocol ITimeFormat
  (format [_ fmt]))

(defn duration?
  [o]
  (instance? Duration o))

(defn datetime?
  [o]
  (instance? DateTime o))

(defn duration
  [o]
  (cond
    (number? o)   (.fromMillis Duration o)
    (duration? o) o
    (string? o)   (.fromISO Duration o)
    (map? o)      (.fromObject Duration (clj->js o))
    :else         (throw (js/Error. "unexpected arguments"))))

(defn datetime
  ([s] (datetime s nil))
  ([s {:keys [zone force-zone] :or {zone "local" force-zone false}}]
   (cond
     (integer? s)
     (.fromMillis ^js DateTime s #js {:zone zone :setZone force-zone})

     (map? s)
     (.fromObject ^js DateTime (-> (clj->js s)
                                   (obj/set! "zone" zone)
                                   (obj/set! "setZone" force-zone)))

     :else
     (throw (js/Error. "invalid arguments")))))

(defn epoch->datetime
  ([seconds] (epoch->datetime seconds nil))
  ([seconds {:keys [zone force-zone] :or {zone "local" force-zone false}}]
   (.fromSeconds ^js DateTime seconds #js {:zone zone :setZone force-zone})))

(defn iso->datetime
  "A faster option for transit date parsing."
  [s]
  (.fromISO ^js DateTime s #js {:zone "local"}))

(defn parse-datetime
  ([s] (parse-datetime s :iso nil))
  ([s fmt] (parse-datetime s fmt nil))
  ([s fmt {:keys [zone force-zone] :or {zone "local" force-zone false}}]
   (if (string? fmt)
     (.fromFormat ^js DateTime s fmt #js {:zone zone :setZone force-zone})
     (case fmt
       :iso     (.fromISO ^js DateTime s #js {:zone zone :setZone force-zone})
       :rfc2822 (.fromRFC2822 ^js DateTime s #js {:zone zone :setZone force-zone})
       :http    (.fromHTTP ^js DateTime s #js {:zone zone :setZone force-zone})))))

(dm/export common-time/now)

(defn utc-now
  []
  (.utc ^js DateTime))

(defn ->utc
  [dt]
  (.toUTC ^js dt))

(defn diff
  [dt1 dt2]
  (.diff ^js dt1 dt2))

(extend-protocol IEquiv
  DateTime
  (-equiv [it other]
    (if other
      (.equals it other)
      false))

  Duration
  (-equiv [it other]
    (if other
      (.equals it other)
      false)))

(extend-protocol Inst
  DateTime
  (inst-ms* [inst] (.toMillis ^js inst))

  Duration
  (inst-ms* [inst] (.toMillis ^js inst)))

(extend-protocol IComparable
  DateTime
  (-compare [it other]
    (if ^boolean (.equals it other)
      0
      (if (< (inst-ms it) (inst-ms other)) -1 1)))

  Duration
  (-compare [it other]
    (if ^boolean (.equals it other)
      0
      (if (< (inst-ms it) (inst-ms other)) -1 1))))

(extend-protocol ITimeMath
  DateTime
  (plus [it o]
    (if (map? o)
      (.plus ^js it (clj->js o))
      (.plus ^js it o)))

  (minus [it o]
    (if (map? o)
      (.minus ^js it (clj->js o))
      (.minus ^js it o)))

  Duration
  (plus [it o]
    (if (map? o)
      (.plus ^js it (clj->js o))
      (.plus ^js it o)))

  (minus [it o]
    (if (map? o)
      (.minus ^js it (clj->js o))
      (.minus ^js it o))))

(extend-protocol IPrintWithWriter
  DateTime
  (-pr-writer [p writer _]
    (-write writer (str/fmt "#app/instant \"%s\"" (format p :iso))))

  Duration
  (-pr-writer [p writer _]
    (-write writer (str/fmt "#app/duration \"%s\"" (format p :iso)))))

(defn- resolve-format
  [v]
  (case v
    :time-24-simple        (.-TIME_24_SIMPLE ^js DateTime)
    :datetime-short        (.-DATETIME_SHORT ^js DateTime)
    :datetime-med          (.-DATETIME_MED ^js DateTime)
    :datetime-full         (.-DATETIME_FULL ^js DateTime)
    :date-full             (.-DATE_FULL ^js DateTime)
    :date-med-with-weekday (.-DATE_MED_WITH_WEEKDAY ^js DateTime)
    v))

(defn- format-datetime
  [dt fmt]
  (case fmt
    :iso     (.toISO ^js dt)
    :rfc2822 (.toRFC2822 ^js dt)
    :http    (.toHTTP ^js dt)
    :json    (.toJSON ^js dt)
    :date    (.toJSDate ^js dt)
    :epoch   (js/Math.floor (.toSeconds ^js dt))
    :millis  (.toMillis ^js dt)
    (let [f (resolve-format fmt)]
      (if (string? f)
        (.toFormat ^js dt f)
        (.toLocaleString ^js dt f)))))

(extend-protocol ITimeFormat
  DateTime
  (format [it fmt]
    (format-datetime it fmt))

  Duration
  (format [it fmt]
    (case fmt
      :iso (.toISO it)
      :json (.toJSON it)
      (.toFormat ^js it fmt))))

(def ^:private locales
  #js {:en dateFnsLocalesEnUs
       :ar dateFnsLocalesAr
       :he dateFnsLocalesHe
       :fr dateFnsLocalesFr
       :tr dateFnsLocalesTr
       :es dateFnsLocalesEs
       :ca dateFnsLocalesCa
       :el dateFnsLocalesEl
       :ru dateFnsLocalesRu
       :ro dateFnsLocalesRo
       :de dateFnsLocalesDe
       :fa dateFnsLocalesFa
       :pt_br dateFnsLocalesPtBr
       :zh_cn dateFnsLocalesZhCn})

(defn timeago
  ([v] (timeago v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (let [v (if (datetime? v) (format v :date) v)]
       (->> #js {:includeSeconds true
                 :addSuffix true
                 :locale (obj/get locales locale)}
            (dateFnsFormatDistanceToNowStrict v))))))

(defn format-date-locale
  ([v] (format-date-locale v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (let [v (if (datetime? v) (format v :date) v)
           locale (obj/get locales locale)
           f (.date (.-formatLong ^js locale) v)]
       (->> #js {:locale locale}
            (dateFnsFormat v f))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Measurement Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tpoint
  "Create a measurement checkpoint for time measurement of potentially
  asynchronous flow."
  []
  (let [p1 (.now js/performance)]
    #(duration (- (.now js/performance) p1))))

(defn tpoint-ms
  "Create a measurement checkpoint for time measurement of potentially
  asynchronous flow."
  []
  (let [p1 (.now js/performance)]
    #(- (.now js/performance) p1)))

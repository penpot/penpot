;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.time
  (:require
   ["date-fns/format$default" :as dfn-format]
   ["date-fns/formatDistanceToNowStrict$default" :as dfn-distance-to-now]
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
   ["date-fns/locale/zh-CN$default" :as dfn-zh-cn]
   [app.common.data.macros :as dm]
   [app.common.time :as common-time]
   [app.util.object :as obj]
   [cuerdas.core :as str]))

(dm/export common-time/DateTime)
(dm/export common-time/Duration)

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
       :ja_jp dfn-ja})

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

(defn timeago
  ([v] (timeago v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (let [v (if (datetime? v) (format v :date) v)]
       (->> #js {:includeSeconds true
                 :addSuffix true
                 :locale (obj/get locales locale)}
            (dfn-distance-to-now v))))))

(defn format-date-locale
  ([v] (format-date-locale v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (let [v (if (datetime? v) (format v :date) v)
           locale (obj/get locales locale)
           f (.date (.-formatLong ^js locale) v)]
       (->> #js {:locale locale}
            (dfn-format v f))))))

(defn format-date-locale-short
  ([v] (format-date-locale-short v nil))
  ([v {:keys [locale] :or {locale "en"}}]
   (when v
     (let [locale-obj (obj/get locales locale)
           format-str "MMMM do, yyyy"]
       (dfn-format (js/Date. v) format-str #js {:locale locale-obj})))))

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

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.i18n
  "A i18n foundation."
  (:require
   [app.common.data :as d]
   [app.common.logging :as log]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.util.globals :as globals]
   [app.util.modules :as mod]
   [app.util.storage :as storage]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [okulary.core :as l]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(log/set-level! :info)

(def supported-locales
  [{:label "English" :value "en"}
   {:label "Español" :value "es"}
   {:label "Català" :value "ca"}
   {:label "Deutsch (community)" :value "de"}
   {:label "Dutch (community)" :value "nl"}
   {:label "Euskera (community)" :value "eu"}
   {:label "Français (community)" :value "fr"}
   {:label "Gallego (Community)" :value "gl"}
   {:label "Hausa (Community)" :value "ha"}
   {:label "Hrvatski (Community)" :value "hr"}
   {:label "Italiano (community)" :value "it"}
   {:label "Norsk - Bokmål (community)" :value "nb_no"}
   {:label "Polski (community)" :value "pl"}
   {:label "Portuguese - Brazil (community)" :value "pt_br"}
   {:label "Portuguese - Portugal (community)" :value "pt_pt"}
   {:label "Bahasa Indonesia (community)" :value "id"}
   {:label "Rumanian (community)" :value "ro"}
   {:label "Türkçe (community)" :value "tr"}
   {:label "Ελληνική γλώσσα (community)" :value "el"}
   {:label "Русский (community)" :value "ru"}
   {:label "Украї́нська мо́ва (community)" :value "uk"}
   {:label "Český jazyk (community)" :value "cs"}
   {:label "Latviešu valoda (community)" :value "lv"}
   {:label "Српски (community)" :value "sr"}
   {:label "Føroyskt mál (community)" :value "fo"}
   {:label "Korean (community)" :value "ko"}
   {:label "עִבְרִית (community)" :value "he"}
   {:label "عربي/عربى (community)" :value "ar"}
   {:label "فارسی (community)" :value "fa"}
   {:label "日本語 (Community)" :value "ja_jp"}
   {:label "简体中文 (community)" :value "zh_cn"}
   {:label "繁體中文 (community)" :value "zh_hant"}])

(defn- parse-locale
  [locale]
  (let [locale (-> (str/lower locale)
                   (str/replace "-" "_"))]
    (cond-> [locale]
      (str/includes? locale "_")
      (conj (subs locale 0 2)))))

(def ^:private browser-locales
  (delay
    (-> (.-language globals/navigator)
        (parse-locale))))


;; Set initial translation loading state as globaly stored variable;
;; this facilitates hot reloading
(when-not (exists? (unchecked-get globals/global "penpotTranslations"))
  (unchecked-set globals/global "penpotTranslations" #js {}))

(defn- autodetect
  []
  (let [supported (into #{} (map :value supported-locales))]
    (loop [locales (seq @browser-locales)]
      (if-let [locale (first locales)]
        (if (contains? supported locale)
          locale
          (recur (rest locales)))
        cf/default-language))))

(defn get-current
  "Get the currently memoized locale or execute the autodetection"
  []
  (or (get storage/global ::locale) (autodetect)))

(def ^:dynamic *current-locale*
  (get-current))

(defonce state
  (l/atom {:render 0 :locale *current-locale*}))

(defn- assign-current-locale
  [state locale]
  (-> state
      (update :render inc)
      (assoc :locale locale)))

(defn- get-translations
  "Get globaly stored mutable object with all loaded translations"
  []
  (unchecked-get globals/global "penpotTranslations"))

(defn set-translations
  "A helper for synchronously set translations data for specified locale"
  [locale data]
  (let [translations (get-translations)]
    (unchecked-set translations locale data)
    nil))

(defn- load
  [locale]
  (let [path (str "./translation." locale ".js")]
    (->> (mod/import path)
         (p/fmap (fn [result] (unchecked-get result "default")))
         (p/fnly (fn [data cause]
                   (if cause
                     (js/console.error "unexpected error on fetching locale" cause)
                     (do
                       (set! *current-locale* locale)
                       (set-translations locale data)
                       (swap! state assign-current-locale locale))))))))

(defn init
  "Initialize the i18n module"
  []
  (load *current-locale*))

(defn set-locale
  [lname]
  (let [lname (if (or (nil? lname)
                      (str/empty? lname))
                (autodetect)
                (let [supported (into #{} (map :value) supported-locales)]
                  (loop [locales (seq (parse-locale lname))]
                    (if-let [locale (first locales)]
                      (if (contains? supported locale)
                        locale
                        (recur (rest locales)))
                      cf/default-language))))]

    (load lname)))

(deftype C [val]
  IDeref
  (-deref [_] val))

(defn ^boolean c?
  [r]
  (instance? C r))

;; A main public api for translate strings.

;; A marker type that is used just for mark
;; a parameter that represented the counter.

(defn c
  [x]
  (C. x))

(defn empty-string?
  [v]
  (or (nil? v) (empty? v)))

(defn t
  ([locale code]
   (let [translations (get-translations)
         code  (d/name code)
         value (gobj/getValueByKeys translations locale code)]
     (if (empty-string? value)
       (if (= cf/default-language locale)
         code
         (t cf/default-language code))
       (if (array? value)
         (aget value 0)
         value))))
  ([locale code & args]
   (let [translations (get-translations)
         code   (d/name code)
         value  (gobj/getValueByKeys translations locale code)]
     (if (empty-string? value)
       (if (= cf/default-language locale)
         code
         (apply t cf/default-language code args))
       (let [plural (first (filter c? args))
             value  (if (array? value)
                      (if (= @plural 1) (aget value 0) (aget value 1))
                      value)]
         (apply str/fmt value (map #(if (c? %) @% %) args)))))))

(defn tr
  ([code] (t *current-locale* code))
  ([code & args] (apply t *current-locale* code args)))

(mf/defc tr-html*
  {::mf/props :obj}
  [{:keys [content class tag-name on-click]}]
  (let [tag-name (d/nilv tag-name "p")]
    [:> tag-name {:dangerouslySetInnerHTML #js {:__html content}
                  :className class
                  :on-click on-click}]))

(add-watch state "common.time"
           (fn [_ _ pv cv]
             (let [pv (get pv :locale)
                   cv (get cv :locale)]
               (when (not= pv cv)
                 (ct/set-default-locale! cv)))))

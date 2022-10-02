;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.i18n
  "A i18n foundation."
  (:require
   [app.common.logging :as log]
   [app.config :as cfg]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.object :as obj]
   [app.util.storage :refer [storage]]
   [cuerdas.core :as str]
   [goog.object :as gobj]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(log/set-level! :info)

(def supported-locales
  [{:label "English" :value "en"}
   {:label "Español" :value "es"}
   {:label "Català" :value "ca"}
   {:label "Français (community)" :value "fr"}
   {:label "Deutsch (community)" :value "de"}
   {:label "Italiano (community)" :value "it"}
   {:label "Euskera (community)" :value "eu"}
   {:label "Norsk - Bokmål (community)" :value "nb_no"}
   {:label "Portuguese - Brazil (community)" :value "pt_br"}
   {:label "Polski (community)" :value "pl"}
   {:label "Русский (community)" :value "ru"}
   {:label "Rumanian (community)" :value "ro"}
   {:label "Türkçe (community)" :value "tr"}
   {:label "Ελληνική γλώσσα (community)" :value "el"}
   {:label "עִבְרִית (community)" :value "he"}
   {:label "عربي/عربى (community)" :value "ar"}
   {:label "فارسی (community)" :value "fa"}
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

(defn- autodetect
  []
  (let [supported (into #{} (map :value supported-locales))]
    (loop [locales (seq @browser-locales)]
      (if-let [locale (first locales)]
        (if (contains? supported locale)
          locale
          (recur (rest locales)))
        cfg/default-language))))

(defonce translations #js {})
(defonce locale (l/atom (or (get @storage ::locale)
                            (autodetect))))

;; The translations `data` is a javascript object and should be treated
;; with `goog.object` namespace functions instead of a standard
;; clojure functions. This is for performance reasons because this
;; code is executed in the critical part (application bootstrap) and
;; used in many parts of the application.

(defn init!
  [data]
  (set! translations data))

(defn set-locale!
  [lname]
  (if lname
    (let [supported (into #{} (map :value supported-locales))
          lname     (loop [locales (seq (parse-locale lname))]
                      (if-let [locale (first locales)]
                        (if (contains? supported locale)
                          locale
                          (recur (rest locales)))
                        cfg/default-language))]

      (swap! storage assoc ::locale lname)
      (reset! locale lname))
    (let [lname (autodetect)]
      (swap! storage dissoc ::locale)
      (reset! locale lname))))

(defn reset-locale
  "Set the current locale to the browser detected one if it is
  supported or default locale if not."
  []
  (swap! storage dissoc ::locale)
  (reset! locale (autodetect)))

(add-watch locale ::browser-font
           (fn [_ _ _ locale]
             (log/info :hint "locale changed" :locale locale)
             (dom/set-html-lang! locale)
             (let [node  (dom/get-body)]
               (if (or (= locale "fa")
                       (= locale "ar"))
                 (dom/set-css-property! node "--font-family" "'vazirmatn', 'worksans', sans-serif")
                 (dom/unset-css-property! node "--font-family")))))

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
   (let [code  (name code)
         value (gobj/getValueByKeys translations code locale)]
     (if (empty-string? value)
       (if (= cfg/default-language locale)
         code
         (t cfg/default-language code))
       (if (array? value)
         (aget value 0)
         value))))
  ([locale code & args]
   (let [code   (name code)
         value  (gobj/getValueByKeys translations code locale)]
     (if (empty-string? value)
       (if (= cfg/default-language locale)
         code
         (apply t cfg/default-language code args))
       (let [plural (first (filter c? args))
             value  (if (array? value)
                      (if (= @plural 1) (aget value 0) (aget value 1))
                      value)]
         (apply str/fmt value (map #(if (c? %) @% %) args)))))))

(defn tr
  ([code] (t @locale code))
  ([code & args] (apply t @locale code args)))

(mf/defc tr-html
  {::mf/wrap-props false}
  [props]
  (let [label    (obj/get props "label")
        tag-name (obj/get props "tag-name" "p")]
    [:> tag-name {:dangerouslySetInnerHTML #js {:__html (tr label)}}]))

;; DEPRECATED
(defn use-locale
  []
  (mf/deref locale))


;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.spec
  (:require [cljs.spec.alpha :as s]
            [cuerdas.core :as str]))


;; --- Constants

(def email-rx
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(def number-rx
  #"^[+-]?([0-9]*\.?[0-9]+|[0-9]+\.?[0-9]*)([eE][+-]?[0-9]+)?$")

(def ^:private color-re
  #"^#[0-9A-Fa-f]{6}$")

;; --- Predicates

(defn email?
  [v]
  (and (string? v)
       (re-matches email-rx v)))

(defn color?
  [v]
  (and (string? v)
       (re-matches #"^#[0-9A-Fa-f]{6}$" v)))

(defn file?
  [v]
  (instance? js/File v))

(defn url-str?
  [v]
  (string? v))

;; --- Default Specs

(s/def ::bool boolean?)
(s/def ::uuid uuid?)
(s/def ::email email?)
(s/def ::color color?)
(s/def ::string string?)
(s/def ::positive pos?)
(s/def ::inst inst?)
(s/def ::keyword keyword?)
(s/def ::fn fn?)
(s/def ::set set?)
(s/def ::coll coll?)

(s/def ::not-empty-string
  (s/and string? #(not (str/empty? %))))


(defn- conform-number
  [v]
  (cond
    (number? v) v
    (re-matches number-rx v) (js/parseFloat v)
    :else ::s/invalid))

(s/def ::number
  (s/conformer conform-number str))

;; NOTE: backward compatibility (revisit the code and remove)
(s/def ::number-str ::number)

(s/def ::color color?)

;; --- Public Api

(defn valid?
  [spec data]
  (let [valid (s/valid? spec data)]
    (when-not valid
      (js/console.error (str "Spec validation error:\n" (s/explain-str spec data))))
    valid))

(defn extract
  "Given a map spec, performs a `select-keys`
  like exctraction from the object.

  NOTE: this function does not executes
  the conform or validation of the data,
  is responsability of the user to do it."
  [data spec]
  (let [desc (s/describe spec)
        {:keys [req-un opt-un]} (apply hash-map (rest desc))
        keys (concat
              (map (comp keyword name) req-un)
              (map (comp keyword name) opt-un))]
    (select-keys data keys)))



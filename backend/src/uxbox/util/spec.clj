;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.spec
  (:refer-clojure :exclude [keyword uuid vector boolean map set])
  (:require [clojure.spec :as s]
            [cuerdas.core :as str]
            [uxbox.util.exceptions :as ex])
  (:import java.time.Instant))

;; --- Constants

(def email-rx
  #"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")

(def uuid-rx
  #"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

;; --- Public Api

(defn conform
  [spec data]
  (let [result (s/conform spec data)]
    (if (= result ::s/invalid)
      (ex/raise :type :validation
                :code ::invalid
                :message (s/explain-str spec data)
                :context (s/explain-data spec data))
      result)))

;; --- Predicates

(defn email?
  [v]
  (and string?
       (re-matches email-rx v)))

(defn instant?
  [v]
  (instance? Instant v))

(defn path?
  [v]
  (instance? java.nio.file.Path v))

(defn regex?
  [v]
  (instance? java.util.regex.Pattern v))

;; --- Conformers

(defn- uuid-conformer
  [v]
  (cond
    (uuid? v) v
    (string? v)
    (cond
      (re-matches uuid-rx v)
      (java.util.UUID/fromString v)

      (str/empty? v)
      nil

      :else
      ::s/invalid)
    :else ::s/invalid))

(defn- integer-conformer
  [v]
  (cond
    (integer? v) v
    (string? v)
    (if (re-matches #"^[-+]?\d+$" v)
      (Long/parseLong v)
      ::s/invalid)
    :else ::s/invalid))

(defn boolean-conformer
  [v]
  (cond
    (boolean? v) v
    (string? v)
    (if (re-matches #"^(?:t|true|false|f|0|1)$" v)
      (contains? #{"t" "true" "1"} v)
      ::s/invalid)
    :else ::s/invalid))

(defn boolean-unformer
  [v]
  (if v "true" "false"))

;; --- Default Specs

(s/def ::integer-string (s/conformer integer-conformer str))
(s/def ::uuid-string (s/conformer uuid-conformer str))
(s/def ::boolean-string (s/conformer boolean-conformer boolean-unformer))
(s/def ::positive-integer #(< 0 % Long/MAX_VALUE))
(s/def ::uploaded-file #(instance? ratpack.form.UploadedFile %))
(s/def ::uuid uuid?)
(s/def ::bytes bytes?)
(s/def ::path path?)

(s/def ::id ::uuid-string)
(s/def ::name string?)
(s/def ::username string?)
(s/def ::password string?)
(s/def ::version integer?)
(s/def ::email email?)
(s/def ::token string?)


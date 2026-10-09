;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.units
  "Unit registry.

  Units are schemas (`:type \"absolute\"` or `\"relative\"`) with a
  keyword (`px`, `rem`, `%`) and conversion scripts. Relative units carry
  a `to_absolute` script evaluated against another operand. Script
  execution lives in the interpreter.

  Registry shape: the common registry plus `:keywords {UPPER-KEYWORD uri}`."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.schema-uri :as suri]
   [clojure.string :as str]
   [clojure.walk :as walk]))

(def pixel-unit-uri (suri/build-uri {:category "core" :name "px-unit" :version {:major 0}}))
(def seconds-unit-uri (suri/build-uri {:category "core" :name "seconds-unit" :version {:major 0}}))
(def ms-unit-uri (suri/build-uri {:category "core" :name "ms-unit" :version {:major 0}}))
(def rem-unit-uri (suri/build-uri {:category "core" :name "rem-unit" :version {:major 0}}))
(def ch-unit-uri (suri/build-uri {:category "core" :name "ch-unit" :version {:major 0}}))
(def percent-unit-uri "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/percent-unit/0/")

(def default-specs
  [[seconds-unit-uri
    {:name "seconds" :type "absolute" :keyword "s"
     :description "A unit of time equal to one second."
     :conversions []}]
   [ms-unit-uri
    {:name "milliseconds" :type "absolute" :keyword "ms"
     :description "A unit of time equal to one millisecond."
     :conversions [{:source "$self" :target seconds-unit-uri
                    :script {:script "return ({input} / 1000)s;"}
                    :description "Convert milliseconds to seconds."}
                   {:source seconds-unit-uri :target "$self"
                    :script {:script "return ({input} * 1000)ms;"}
                    :description "Convert seconds to milliseconds."}]}]
   [pixel-unit-uri
    {:name "pixel" :type "absolute" :keyword "px"
     :description "A pixel unit, typically used in digital displays."
     :conversions []}]
   [rem-unit-uri
    {:name "root em" :type "absolute" :keyword "rem"
     :description "A root em unit, relative to the root font size."
     :conversions [{:source "$self" :target pixel-unit-uri
                    :script {:script "return ({input} * 16)px;"}
                    :description "Convert rem to pixel based on a root font size of 16px."}
                   {:source pixel-unit-uri :target "$self"
                    :script {:script "return ({input} / 16)rem;"}
                    :description "Convert pixel to rem based on a root font size of 16px."}]}]
   [percent-unit-uri
    {:name "percent" :type "relative" :keyword "%"
     :description "A percentage unit, representing a fraction of 100."
     :to_absolute {:script "return {other_value} * ({relative_value} / 100);"}
     :conversions []}]
   [ch-unit-uri
    {:name "character" :type "absolute" :keyword "ch"
     :description "Relative to the width of the '0' character in the element's font."
     :conversions []}]])

(def valid-unit-types ["absolute" "relative"])

(def empty-registry
  (assoc reg/empty-registry :keywords {}))

(defn spec-name
  [spec]
  (str/lower-case (:name spec)))

(defn- normalize-spec
  [uri spec]
  (let [spec (walk/keywordize-keys spec)]
    (when-not (and (map? spec)
                   (string? (:name spec))
                   (string? (:keyword spec))
                   (contains? (set valid-unit-types) (:type spec)))
      (err/raise :config/invalid-specification
                 {:data {:kind "unit" :uri uri
                         :error "expected a map with :name, :keyword and :type absolute|relative"}}))
    (cond-> (update spec :conversions #(vec (or % [])))
      (:to_absolute spec) (assoc-in [:to_absolute :ast] (:ast (parser/parse (get-in spec [:to_absolute :script])))))))

(defn- register-conversions
  [registry uri spec]
  (reduce (fn [registry {:keys [source target script]}]
            (let [source-uri (if (= source "$self") uri source)
                  target-uri (if (= target "$self") uri target)
                  ast (:ast (parser/parse (:script script)))]
              (reg/add-conversion registry source-uri target-uri
                                  {:ast ast :source-uri source-uri :target-uri target-uri})))
          registry
          (:conversions spec)))

(defn register
  "Registers a unit specification under `uri`."
  [registry uri spec]
  (let [spec (normalize-spec uri spec)]
    (-> registry
        (reg/add-spec uri spec (spec-name spec))
        (assoc-in [:keywords (str/upper-case (:keyword spec))] uri)
        (register-conversions uri spec))))

(defn default-registry
  []
  (reduce (fn [registry [uri spec]] (register registry uri spec)) empty-registry default-specs))

(defn spec
  [registry uri]
  (reg/spec registry uri))

(defn uri-by-keyword
  [registry keyword]
  (get (:keywords registry) (str/upper-case keyword)))

(defn spec-by-keyword
  [registry keyword]
  (when-let [uri (uri-by-keyword registry keyword)]
    (spec registry uri)))

(defn relative?
  "Whether the unit keyword is registered as a relative unit."
  [registry keyword]
  (= "relative" (:type (spec-by-keyword registry keyword))))

(defn conversion-steps
  [registry source-uri target-uri]
  (reg/conversion-steps registry source-uri target-uri))

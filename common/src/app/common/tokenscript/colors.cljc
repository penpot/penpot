;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.colors
  "Color space registry.

  Color spaces are schemas (`:type \"color\"`) carrying tokenscript
  scripts: initializers (`hsl(0, 100, 50)`) and conversions between
  spaces. Registration parses the scripts once; execution happens in the
  interpreter, which has access to the whole config.

  `ColorRegistry` has the common registry fields (see
  `app.common.tokenscript.registry`) plus `initializers`
  (`{name-key {:ast .. :keyword ..}}`); conversions are
  `{:ast .. :source-uri .. :target-uri ..}`."
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.schema-uri :as suri]
   [app.common.tokenscript.util :as u]
   [clojure.walk :as walk]))

(def hex-color-uri
  (suri/build-uri {:category "core" :name "hex-color" :version {:major 0}}))

(def default-specs
  {hex-color-uri
   {:name "Hex"
    :type "color"
    :description "A color in hex format, e.g. #ff0000"
    :schema {:type "object"
             :properties {:value {:type "string"}}}
    :initializers [{:title "Hex Color Initializer"
                    :keyword "hex"
                    :script {:type "/api/v1/core/tokenscript/0/"
                             :script "variable c: Color.Hex;\n c.value = {input};\nreturn c;"}}]
    :conversions []}})

(def valid-schema-types ["number" "string" "color"])

(defn- normalize-spec
  [uri spec]
  (let [spec (if (string? spec)
               (err/raise :config/invalid-specification
                          {:data {:kind "color" :uri uri :error "string specifications must be decoded first"}})
               (walk/keywordize-keys spec))]
    (when-not (and (map? spec) (string? (:name spec)) (= "color" (:type spec)))
      (err/raise :config/invalid-specification
                 {:data {:kind "color" :uri uri :error "expected a map with :name and :type \"color\""}}))
    (-> spec
        (update :initializers #(vec (or % [])))
        (update :conversions #(vec (or % []))))))

(defn- register-initializers
  [registry spec]
  (reduce (fn [registry {:keys [keyword script]}]
            (let [ast (try
                        (:ast (parser/parse (:script script)))
                        (catch #?(:clj Throwable :cljs :default) e
                          (err/raise :color/initializer-construct-failed
                                     {:data {:error (err/describe e)}})))]
              (assoc-in registry [:initializers (u/name-key keyword)] {:ast ast :keyword keyword})))
          registry
          (:initializers spec)))

(defn- register-spec
  [registry uri spec]
  (let [uri (suri/normalize uri)
        spec (normalize-spec uri spec)]
    (-> registry
        (reg/add-spec uri spec)
        (register-initializers spec)
        (reg/add-conversions uri (:conversions spec)))))

(defrecord ColorRegistry [specs spec-types conversions initializers cache]
  reg/ISchemaRegistry
  (register [this uri spec]
    (register-spec this uri spec)))

(defn empty-registry
  []
  (->ColorRegistry {} {} {} {} (reg/new-cache)))

(defn default-registry
  "Registry with the hex color space only."
  []
  (reduce-kv reg/register (empty-registry) default-specs))

(defn spec-from-color
  [registry color]
  (when-let [sub (:sub-type color)]
    (reg/spec-by-name registry sub)))

(defn initializer
  "Initializer registered for `keyword` (case insensitive), or nil."
  [registry keyword]
  (u/get-name (:initializers registry) keyword))

(defn has-conversion-by-type?
  [registry source-type target-type]
  (let [s (reg/uri-by-name registry source-type)
        t (reg/uri-by-name registry target-type)]
    (boolean (and s t (reg/has-conversion? registry s t)))))

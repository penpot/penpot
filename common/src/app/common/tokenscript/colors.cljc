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

  Registry shape (see `app.common.tokenscript.registry`):

    {:specs {uri spec} :spec-types {name uri}
     :conversions {source {target {:ast .. :target-uri ..}}}
     :initializers {keyword {:ast ..}}}"
  (:require
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.schema-uri :as suri]
   [clojure.string :as str]
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
                    :script {:type "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/initializer"
                             :script "variable c: Color.Hex;\n c.value = {input};\nreturn c;"}}]
    :conversions []}})

(def valid-schema-types ["number" "string" "color"])

(def empty-registry
  (assoc reg/empty-registry :initializers {}))

(defn spec-name
  [spec]
  (str/lower-case (:name spec)))

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
              (assoc-in registry [:initializers (str/lower-case keyword)] {:ast ast :keyword keyword})))
          registry
          (:initializers spec)))

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
  "Registers a color specification under `uri`."
  [registry uri spec]
  (let [spec (normalize-spec uri spec)]
    (-> registry
        (reg/add-spec uri spec (spec-name spec))
        (register-initializers spec)
        (register-conversions uri spec))))

(defn default-registry
  []
  (reduce-kv register empty-registry default-specs))

(defn spec
  [registry uri]
  (reg/spec registry uri))

(defn spec-by-type
  [registry type]
  (reg/spec-by-name registry (str/lower-case type)))

(defn uri-by-type
  [registry type]
  (get (:spec-types registry) (str/lower-case type)))

(defn spec-from-color
  [registry color]
  (when-let [sub (:sub-type color)]
    (spec-by-type registry sub)))

(defn has-initializer?
  [registry keyword]
  (contains? (:initializers registry) (str/lower-case keyword)))

(defn initializer
  [registry keyword]
  (get (:initializers registry) (str/lower-case keyword)))

(defn has-conversion-by-type?
  [registry source-type target-type]
  (let [s (uri-by-type registry source-type)
        t (uri-by-type registry target-type)]
    (boolean (and s t (reg/has-conversion? registry s t)))))

(defn conversion-steps
  [registry source-uri target-uri]
  (reg/conversion-steps registry source-uri target-uri))

(defn schemas
  "All registered color specs by URI."
  [registry]
  (:specs registry))

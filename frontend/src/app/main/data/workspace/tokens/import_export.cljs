;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.workspace.tokens.import-export
  (:require
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.path-names :as cpn]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.notifications :as ntf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.tokenscript :as ts]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.store :as st]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(defn- extract-error-with-code
  "Return the error if it has an error code generated from Penpot code"
  [err]
  (when (contains? (ex-data err) :error/code)
    (wte/error-ex-info (:error/code (ex-data err)) (ex-message err) err)))

(defn- extract-reference-errors
  "Extracts reference errors from errors produced by StyleDictionary."
  [err]
  (let [[header-1 header-2 & errors] (str/split err "\n")]
    (when (and
           (= header-1 "Error: ")
           (= header-2 "Reference Errors:"))
      errors)))

(defn- extract-name-error
  "Extracts name error out of malli schema error during import."
  [err]
  (let [schema-error (some-> (ex-data err)
                             (get-in [:app.common.schema/explain :errors])
                             (first))
        name-error? (= (:in schema-error) [:name])]
    (when name-error?
      (wte/error-ex-info :error.import/invalid-token-name (:value schema-error) err))))

(defn- group-by-value [m]
  (reduce (fn [acc [k v]]
            (update acc v conj k)) {} m))

(defn- skipped-detail
  "HTML lists of the skipped token paths, one section per label, with
  `section-fn` called with the label and the count text."
  [section-fn label->paths]
  (->> (for [[label paths] (sort-by key label->paths)]
         (str (section-fn (dom/escape-html label)
                          (tr "labels.warning-count" (i18n/c (count paths))))
              "<ul>"
              (->> paths
                   (sort)
                   (map #(str "<li>" (dom/escape-html %) "</li>"))
                   (str/join ""))
              "</ul>"))
       (str/join "")))

(defn- show-import-warnings
  "One toast for what the import skipped: tokens of types penpot does not
  have, and token data it does not store (`$extensions`, `$deprecated`,
  group descriptions, values it cannot read)."
  [unknown-types unsupported]
  (l/wrn :hint "token data skipped during import"
         :types (str/join ", " (map (fn [[path type]] (str path " (" type ")")) unknown-types))
         :data (str/join ", " (map (fn [{:keys [path property]}] (str path " (" property ")")) unsupported)))
  (let [content (if (seq unsupported)
                  (tr "workspace.tokens.unsupported-token-data-message")
                  (tr "workspace.tokens.unknown-token-type-message"))]
    (ntf/show {:content content
               :is-html true
               :detail (str (skipped-detail #(tr "workspace.tokens.unknown-token-type-section" %1 %2)
                                            (group-by-value unknown-types))
                            (skipped-detail #(tr "workspace.tokens.unsupported-token-data-section" %1 %2)
                                            (update-vals (group-by :property unsupported) #(mapv :path %))))
               :type :toast
               :level :info})))

(defn- decode-json
  [json-string]
  (try
    (json/decode json-string {:key-fn identity})
    (catch js/Error e
      (throw (wte/error-ex-info :error.import/json-parse-error json-string e)))))

(defn- parse-decoded-json
  [decoded-json file-name]
  (try
    (ctob/import-decoded-json decoded-json file-name)
    (catch js/Error e
      (let [err (or (extract-error-with-code e)
                    (extract-name-error e)
                    (wte/error-ex-info :error.import/invalid-json-data decoded-json e))]
        (throw err)))))

(defn- validate-library
  "Resolve tokens in the library and search for errors. Reference errors are ignored, since
   it can be resolved by the user in the UI. All the other errors are thrown as exceptions."
  [{:keys [tokens-lib unknown-types unsupported]}]
  (when (or unknown-types unsupported)
    (st/emit! (show-import-warnings unknown-types unsupported)))
  (try
    (let [tokens-tree     (ctob/get-all-tokens-map tokens-lib)
          resolved-tokens (if (contains? cf/flags :tokenscript)
                            (rx/of (ts/resolve-tokens tokens-tree))
                            (sd/resolve-tokens-with-verbose-errors tokens-tree))]
      (->> resolved-tokens
           (rx/map (fn [_]
                     tokens-lib))
           (rx/catch (fn [sd-error]
                       (let [reference-errors (extract-reference-errors sd-error)]
                         (if reference-errors
                           (rx/of tokens-lib)
                           (throw (wte/error-ex-info :error.import/style-dictionary-unknown-error sd-error sd-error))))))))
    (catch js/Error e
      (throw (wte/error-ex-info :error.import/style-dictionary-unknown-error "" e)))))

(defn- drop-parent-directory
  [path]
  (->> (cpn/split-path path)
       (rest)
       (str/join "/")))

(defn- remove-path-extension
  [path]
  (-> (str/split path ".")
      (butlast)
      (str/join)))

(defn- file-path->set-name
  [path]
  (-> path
      (drop-parent-directory)
      (remove-path-extension)))

(defn import-file-stream
  [file-path file-text]
  (let [file-name (remove-path-extension file-path)]
    (->> file-text
         (rx/map decode-json)
         (rx/map #(parse-decoded-json % file-name))
         (rx/mapcat validate-library))))

(defn import-directory-stream
  [file-stream]
  (->> file-stream
       (rx/map (fn [[file-path file-text]]
                 (let [set-name (file-path->set-name file-path)]
                   (try
                     {set-name (decode-json file-text)}
                     (catch js/Error e
                       ;; Ignore files with json parse errors
                       {:path file-path :error e})))))
       (rx/reduce (fn [merged-json decoded-json]
                    (if (:error decoded-json)
                      merged-json
                      (conj merged-json decoded-json)))
                  {})
       (rx/map (fn [merged-json]
                 (parse-decoded-json (if (= 1 (count merged-json))
                                       (val (first merged-json))
                                       merged-json)
                                     (ffirst merged-json))))
       (rx/mapcat validate-library)))

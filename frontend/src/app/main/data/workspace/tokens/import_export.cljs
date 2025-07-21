;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.import-export
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.json :as json]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.notifications :as ntf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.store :as st]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

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

(defn- show-unknown-types-warning [unknown-tokens]
  (let [type->tokens (group-by-value unknown-tokens)]
    (ntf/show {:content (tr "workspace.tokens.unknown-token-type-message")
               :detail (->> (for [[token-type tokens] type->tokens]
                              (tr "workspace.tokens.unknown-token-type-section" token-type (count tokens)))
                            (str/join "<br>"))
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
    {:tokens-lib (ctob/parse-decoded-json decoded-json file-name)
     :unknown-tokens (ctob/get-tokens-of-unknown-type decoded-json
                                                      ;; Filter out FF token-types
                                                      {:process-token-type
                                                       (fn [dtcg-token-type]
                                                         (if (or
                                                              (and (not (contains? cf/flags :token-units))
                                                                   (= dtcg-token-type "number"))
                                                              (and (not (contains? cf/flags :token-typography-types))
                                                                   (contains? ctt/ff-typography-keys dtcg-token-type)))
                                                           nil
                                                           dtcg-token-type))})}
    (catch js/Error e
      (let [err (or (extract-name-error e)
                    (wte/error-ex-info :error.import/invalid-json-data decoded-json e))]
        (throw err)))))

(defn- validate-library
  "Resolve tokens in the library and search for errors. Reference errors are ignored, since
   it can be resolved by the user in the UI. All the other errors are thrown as exceptions."
  [{:keys [tokens-lib unknown-tokens]}]
  (when unknown-tokens
    (st/emit! (show-unknown-types-warning unknown-tokens)))
  (try
    (->> (ctob/get-all-tokens tokens-lib)
         (sd/resolve-tokens-with-verbose-errors)
         (rx/map (fn [_]
                   tokens-lib))
         (rx/catch (fn [sd-error]
                     (let [reference-errors (extract-reference-errors sd-error)]
                       (if reference-errors
                         (rx/of tokens-lib)
                         (throw (wte/error-ex-info :error.import/style-dictionary-unknown-error sd-error sd-error)))))))
    (catch js/Error e
      (throw (wte/error-ex-info :error.import/style-dictionary-unknown-error "" e)))))

(defn- drop-parent-directory
  [path]
  (->> (cfh/split-path path)
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

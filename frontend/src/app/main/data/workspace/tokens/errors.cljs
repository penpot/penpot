;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.data.workspace.tokens.errors
  (:require
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]))

(def error-codes
  {:error.import/json-parse-error
   {:error/code :error.import/json-parse-error
    :error/fn #(tr "workspace.tokens.error-parse")}

   :error.import/no-token-files-found
   {:error/code :error.import/no-token-files-found
    :error/fn #(tr "workspace.tokens.no-token-files-found")}

   :error.import/invalid-json-data
   {:error/code :error.import/invalid-json-data
    :error/fn #(tr "workspace.tokens.invalid-json")}

   :error.import/invalid-token-name
   {:error/code :error.import/invalid-json-data
    :error/fn #(tr "workspace.tokens.invalid-json-token-name")
    :error/detail #(tr "workspace.tokens.invalid-json-token-name-detail" %)}

   :error.import/style-dictionary-reference-errors
   {:error/code :error.import/style-dictionary-reference-errors
    :error/fn #(str (tr "workspace.tokens.import-error") "\n\n" (first %))
    :error/detail #(str/join "\n\n" (rest %))}

   :error.import/style-dictionary-unknown-error
   {:error/code :error.import/style-dictionary-reference-errors
    :error/fn #(tr "workspace.tokens.import-error")}

   :error.token/empty-input
   {:error/code :error.token/empty-input
    :error/fn #(tr "workspace.tokens.empty-input")}

   :error.token/direct-self-reference
   {:error/code :error.token/direct-self-reference
    :error/fn #(tr "workspace.tokens.self-reference")}

   :error.token/invalid-color
   {:error/code :error.token/invalid-color
    :error/fn #(str (tr "workspace.tokens.invalid-color" %))}

   :error.token/number-too-large
   {:error/code :error.token/number-too-large
    :error/fn #(str (tr "workspace.tokens.number-too-large" %))}

   :error.style-dictionary/missing-reference
   {:error/code :error.style-dictionary/missing-reference
    :error/fn #(str (tr "workspace.tokens.missing-references") (str/join " " %))}

   :error.style-dictionary/invalid-token-value
   {:error/code :error.style-dictionary/invalid-token-value
    :error/fn #(str (tr "workspace.tokens.invalid-value" %))}

   :error.style-dictionary/value-with-units
   {:error/code :error.style-dictionary/value-with-units
    :error/fn #(str (tr "workspace.tokens.value-with-units"))}

   :error.style-dictionary/invalid-token-value-opacity
   {:error/code :error.style-dictionary/invalid-token-value-opacity
    :error/fn #(str/join "\n" [(str (tr "workspace.tokens.invalid-value" %) ".") (tr "workspace.tokens.opacity-range")])}

   :error.style-dictionary/invalid-token-value-stroke-width
   {:error/code :error.style-dictionary/invalid-token-value-stroke-width
    :error/fn #(str/join "\n" [(str (tr "workspace.tokens.invalid-value" %) ".") (tr "workspace.tokens.stroke-width-range")])}

   :error/unknown
   {:error/code :error/unknown
    :error/fn #(tr "labels.unknown-error")}})

(defn get-error-code [error-key]
  (get error-codes error-key (:error/unknown error-codes)))

(defn error-with-value [error-key error-value]
  (-> (get-error-code error-key)
      (assoc :error/value error-value)))

(defn error-ex-info [error-key error-value exception]
  (let [err (-> (error-with-value error-key error-value)
                (assoc :error/exception exception))]
    (ex-info (:error/code err) err)))

(defn has-error-code? [error-key errors]
  (some #(= (:error/code %) error-key) errors))

(defn humanize-errors [errors]
  (->> errors
       (map (fn [err]
              (cond
                (:error/fn err) ((:error/fn err) (:error/value err))
                (:error/message err) (:error/message err)
                :else err)))))

(defn detail-errors [errors]
  (->> errors
       (map (fn [err]
              (when (:error/detail err)
                ((:error/detail err) (:error/value err)))))
       (filter some?)
       (seq)))

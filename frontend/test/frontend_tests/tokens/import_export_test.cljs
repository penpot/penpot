;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.import-export-test
  (:require
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.import-export :as dwti]
   [app.util.i18n :as i18n]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [clojure.string :as str]))

(def ^:private import-warnings-notification @#'dwti/import-warnings-notification)

;; Echoes the key and its arguments, so the test sees what went into each
;; string. Both arities, like `tr`: a call site compiled for one of them
;; looks up that arity on the replacement.
(defn- tr-stub
  ([key] key)
  ([key & args]
   (str key "(" (str/join ", " (map #(if (i18n/c? %) @% %) args)) ")")))

(t/deftest import-warnings-notification-test
  (with-redefs [i18n/tr tr-stub]
    (let [unknown-types {"core.motion.fast" "duration"
                         "core.motion.slow" "duration"
                         "core.odd<1>" "x<y>"}
          unsupported [{:property "$extensions" :path "core.color.old"}
                       {:property "$extensions" :path "core.color"}
                       {:property "$value" :path "core.color.<odd>"}]]

      (t/testing "only unknown types"
        (let [{:keys [content detail]} (import-warnings-notification unknown-types nil)]
          (t/is (= "workspace.tokens.unknown-token-type-message" content))
          (t/is (str/includes? detail "workspace.tokens.unknown-token-type-section(duration, labels.warning-count(2))"))
          (t/is (str/includes? detail "core.motion.fast"))
          (t/is (str/includes? detail "core.motion.slow"))
          (t/is (not (str/includes? detail "workspace.tokens.unsupported-token-data-section")))))

      (t/testing "only unsupported data"
        (let [{:keys [content detail]} (import-warnings-notification nil unsupported)]
          (t/is (= "workspace.tokens.unsupported-token-data-message" content))
          (t/is (str/includes? detail "workspace.tokens.unsupported-token-data-section($extensions, labels.warning-count(2))"))
          (t/is (str/includes? detail "workspace.tokens.unsupported-token-data-section($value, labels.warning-count(1))"))
          (t/is (str/includes? detail "core.color.old"))
          (t/is (not (str/includes? detail "workspace.tokens.unknown-token-type-section")))))

      (t/testing "both list their sections, and the unsupported data picks the message"
        (let [{:keys [content detail]} (import-warnings-notification unknown-types unsupported)]
          (t/is (= "workspace.tokens.unsupported-token-data-message" content))
          (t/is (str/includes? detail "workspace.tokens.unknown-token-type-section(duration, labels.warning-count(2))"))
          (t/is (str/includes? detail "workspace.tokens.unsupported-token-data-section($extensions, labels.warning-count(2))"))))

      (t/testing "types and paths from the file are escaped"
        (let [{:keys [detail]} (import-warnings-notification unknown-types unsupported)]
          (t/is (str/includes? detail "workspace.tokens.unknown-token-type-section(x&lt;y&gt;, labels.warning-count(1))"))
          (t/is (str/includes? detail "core.odd&lt;1&gt;"))
          (t/is (str/includes? detail "core.color.&lt;odd&gt;"))
          (t/is (not (str/includes? detail "<odd>")))
          (t/is (not (str/includes? detail "x<y>"))))))))

(t/deftest import-file-stream-test
  (t/async
    done
    (t/testing "import simple color token value"
      (let [json (-> {"core" {"color" {"$value" "red"
                                       "$type" "color"}}
                      "$metadata" {"tokenSetOrder" ["core"]}}
                     (json/encode {:type :json-verbose}))]
        (->> (rx/of json)
             (dwti/import-file-stream "core")
             (rx/take 1)
             (rx/subs! (fn [tokens-lib]
                         (t/is (instance? ctob/TokensLib tokens-lib))
                         (t/is (= "red" (-> tokens-lib
                                            (ctob/get-token-by-name "core" "color")
                                            (:value)))))
                       (fn [err]
                         (t/do-report {:type :error :message "Stream error" :actual err})
                         (done))
                       (fn []
                         (done))))))))

(t/deftest reference-errors-test
  (t/testing "Extracts reference errors from StyleDictionary errors"
    ;; Using unicode for the white-space after "Error: " as some editors might remove it and its more visible
    (t/is (=
           ["Some token references (2) could not be found."
            ""
            "foo.value tries to reference missing, which is not defined."
            "color.value tries to reference missing, which is not defined."]
           (#'dwti/extract-reference-errors "Error:\u0020
Reference Errors:
Some token references (2) could not be found.

foo.value tries to reference missing, which is not defined.
color.value tries to reference missing, which is not defined.")))
    (t/is (nil? (#'dwti/extract-reference-errors nil)))          ;; #' is used to access private functions
    (t/is (nil? (#'dwti/extract-reference-errors "none")))))

(t/deftest import-empty-json-stream-test
  (t/async
    done
    (t/testing "fails on empty json string"
      (->> (rx/of "{}")
           (dwti/import-file-stream "")
           (rx/subs!
            (fn [_]
              (throw (js/Error. "Should be an error")))
            (fn [err]
              (t/is (= :error.import/no-token-files-found (:error/code (ex-data err))))
              (done)))))))

(t/deftest import-invalid-json-stream-test
  (t/async
    done
    (t/testing "fails on invalid json"
      (->> (rx/of "{,}")
           (dwti/import-file-stream "")
           (rx/subs!
            (fn [_]
              (throw (js/Error. "Should be an error")))
            (fn [err]
              (t/is (= :error.import/json-parse-error (:error/code (ex-data err))))
              (done)))))))

(t/deftest import-non-token-json-stream-test
  (t/async
    done
    (t/testing "fails on non-token json"
      (->> (rx/of "{\"foo\": \"bar\"}")
           (dwti/import-file-stream "")
           (rx/subs!
            (fn []
              (throw (js/Error. "Should be an error")))
            (fn [err]
              (t/is (= :error.import/no-token-files-found (:error/code (ex-data err))))
              (done)))))))

(t/deftest import-missing-references-json-test
  (t/async
    done
    (t/testing "allows missing references in tokens"
      (let [json (-> {"core" {"color" {"$value" "{missing}"
                                       "$type" "color"}}
                      "$metadata" {"tokenSetOrder" ["core"]}}
                     (json/encode {:type :json-verbose}))]
        (->> (rx/of json)
             (dwti/import-file-stream "")
             (rx/subs! (fn [tokens-lib]
                         (t/is (instance? ctob/TokensLib tokens-lib))
                         (t/is (= "{missing}" (:value (ctob/get-token-by-name tokens-lib "core" "color"))))
                         (done))))))))

(t/deftest import-dtcg-2025-json-test
  (t/async
    done
    (t/testing "imports DTCG 2025.10 object values"
      (let [json (-> {"core" {"space" {"$type" "dimension"
                                       "sm" {"$value" {"value" 4 "unit" "px"}}}
                              "color" {"$type" "color"
                                       "brand" {"$value" {"colorSpace" "srgb" "components" [1 0 0]}
                                                "$extensions" {"com.figma" {"scopes" ["ALL"]}}}}}
                      "$metadata" {"tokenSetOrder" ["core"]}}
                     (json/encode {:type :json-verbose}))]
        (->> (rx/of json)
             (dwti/import-file-stream "")
             (rx/subs! (fn [tokens-lib]
                         (t/is (= "4px" (:value (ctob/get-token-by-name tokens-lib "core" "space.sm"))))
                         (t/is (= "srgb(1, 0, 0)" (:value (ctob/get-token-by-name tokens-lib "core" "color.brand"))))
                         (done))
                       (fn [err]
                         (t/do-report {:type :error :message "Stream error" :actual err})
                         (done))))))))

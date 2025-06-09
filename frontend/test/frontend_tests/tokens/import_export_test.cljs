;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.tokens.import-export-test
  (:require
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace.tokens.import-export :as dwti]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

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
             (rx/subs! (fn [tokens-lib]
                         (t/is (instance? ctob/TokensLib tokens-lib))
                         (t/is (= "red" (-> (ctob/get-set tokens-lib "core")
                                            (ctob/get-token "color")
                                            (:value))))
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
              (t/is (= :error.import/invalid-json-data (:error/code (ex-data err))))
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
              (t/is (= :error.import/invalid-json-data (:error/code (ex-data err))))
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
                         (t/is (= "{missing}" (:value (ctob/get-token-in-set tokens-lib "core" "color"))))
                         (done))))))))

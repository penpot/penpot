;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.bundle-test
  (:require
   #?(:clj [app.common.tokenscript.bundler :as bundler])
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.colors :as colors]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.registry :as reg]
   [app.common.tokenscript.schema-uri :as suri]
   [clojure.string :as str]
   [clojure.test :as t]))

#?(:clj
   (t/deftest bundle-up-to-date-test
     (t/is (= (bundler/bundle-source)
              (slurp bundler/bundle-file))
           "bundle.cljc is stale, run: clojure -M:dev -m app.common.tokenscript.bundler")))

(t/deftest bundled-uris-test
  (t/testing "bundled schemas are identified by path URIs"
    (doseq [{:keys [uri]} tsc/bundled-schemas]
      (t/is (str/starts-with? uri "/api/v1/") uri))))

(t/deftest uri-host-test
  (t/testing "the host of an absolute URI is not part of its identity"
    (t/is (= "/api/v1/core/rgb-color/0/" (suri/normalize "https://example.com/api/v1/core/rgb-color/0/")))
    (t/is (= "/api/v1/core/rgb-color/0/" (suri/normalize "/api/v1/core/rgb-color/0/")))
    (t/is (= "$self" (suri/normalize "$self")))
    (t/is (= (suri/parse-uri "/api/v1/core/rgb-color/0/")
             (suri/parse-uri "https://example.com/api/v1/core/rgb-color/0/")))
    (t/is (suri/same-schema? "https://a.com/api/v1/core/x/0/" "/api/v1/core/x/0.1/")))

  (t/testing "registries resolve schemas with or without host"
    (let [registry (:colors (tsc/default-config))]
      (t/is (some? (colors/spec registry "/api/v1/core/hsl-color/0/")))
      (t/is (some? (colors/spec registry "https://example.com/api/v1/core/hsl-color/0/")))
      (t/is (seq (reg/conversion-steps registry
                                       "https://example.com/api/v1/core/hex-color/0/"
                                       "/api/v1/core/hsl-color/0/")))))

  (t/testing "schemas registered with a host convert to bundled ones"
    (let [cfg (config/register-schema
               (tsc/default-config)
               {:uri "https://example.com/api/v1/core/gray-color/0/"
                :schema {:name "Gray"
                         :type "color"
                         :schema {:type "object" :properties {:v {:type "number"}} :order ["v"]}
                         :initializers []
                         :conversions [{:source "$self"
                                        :target "https://other.org/api/v1/core/rgb-color/0/"
                                        :script {:script "variable c: Color.Rgb; c.r = {input}.v; c.g = {input}.v; c.b = {input}.v; return c;"}}]}})]
      (t/is (seq (reg/conversion-steps (:colors cfg) "/api/v1/core/gray-color/0/" "/api/v1/core/hex-color/0/"))))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.binfile-config-test
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.config :as cf]
   [clojure.test :as t]))

(t/deftest import-limits-reach-the-importer
  (let [values {:binfile-import-max-text-entry-size 101
                :binfile-import-max-text-total-size 202
                :binfile-import-max-binary-entry-size 303
                :binfile-import-max-zip-entries 404}]
    (binding [cf/config (merge cf/config values)]
      (doseq [[key value] values]
        (t/is (= value (cf/get key))))
      (let [cfg      (bfc/import-cfg {} {:team {:features #{}}})
            resolved (#'v3/setup-limits cfg)]
        (t/is (= 101 (::bfc/import-max-text-entry-size cfg) (::v3/max-text-entry-size resolved)))
        (t/is (= 202 (::bfc/import-max-text-total-size cfg) (::v3/max-text-total-size resolved)))
        (t/is (= 303 (::bfc/import-max-binary-entry-size cfg) (::v3/max-binary-entry-size resolved)))
        (t/is (= 404 (::bfc/import-max-zip-entries cfg) (::v3/max-zip-entries resolved)))))))

(t/deftest import-limits-default-when-unconfigured
  (let [keys [:binfile-import-max-text-entry-size
              :binfile-import-max-text-total-size
              :binfile-import-max-binary-entry-size
              :binfile-import-max-zip-entries]]
    (binding [cf/config (apply dissoc cf/config keys)]
      (let [cfg      (bfc/import-cfg {} {:team {:features #{}}})
            resolved (#'v3/setup-limits cfg)
            other    (#'v3/setup-limits cfg)]
        (t/is (nil? (::bfc/import-max-text-entry-size cfg)))
        (t/is (nil? (::bfc/import-max-text-total-size cfg)))
        (t/is (nil? (::bfc/import-max-binary-entry-size cfg)))
        (t/is (nil? (::bfc/import-max-zip-entries cfg)))
        (t/is (= bfc/default-max-text-entry-size (::v3/max-text-entry-size resolved)))
        (t/is (= bfc/default-max-text-total-size (::v3/max-text-total-size resolved)))
        (t/is (= bfc/default-max-binary-entry-size (::v3/max-binary-entry-size resolved)))
        (t/is (= bfc/default-max-zip-entries (::v3/max-zip-entries resolved)))
        (t/is (= 0 @(::v3/accumulated-total-text-size resolved)))
        (t/is (not (identical? (::v3/accumulated-total-text-size resolved)
                               (::v3/accumulated-total-text-size other))))))))

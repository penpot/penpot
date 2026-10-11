;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.features-test
  (:require
   [app.common.features :as cfeat]
   [clojure.test :as t]))

(defn- incompatibility
  "The code of the error raised when moving files between two teams
  with the given features, or nil when they are compatible."
  [source-features destination-features]
  (try
    (cfeat/check-teams-compatibility! {:features source-features}
                                      {:features destination-features})
    nil
    (catch #?(:clj Exception :cljs :default) cause
      (:code (ex-data cause)))))

(t/deftest teams-with-the-same-features-are-compatible
  (t/is (nil? (incompatibility cfeat/default-features cfeat/default-features))))

(t/deftest a-feature-only-one-team-has-makes-them-incompatible
  (let [features (conj cfeat/default-features "test/unknown")]
    (t/is (= :team-feature-mismatch (incompatibility features cfeat/default-features)))
    (t/is (= :team-feature-mismatch (incompatibility cfeat/default-features features)))))

(t/deftest ephimeral-features-left-on-a-team-are-ignored
  (let [features (conj cfeat/default-features "ephimeral/migration")]
    (t/is (nil? (incompatibility features cfeat/default-features)))
    (t/is (nil? (incompatibility cfeat/default-features features)))))

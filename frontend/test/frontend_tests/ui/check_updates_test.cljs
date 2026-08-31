;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.check-updates-test
  (:require
   [app.common.version :as v]
   [app.main.ui.dashboard.check-updates :as dcu]
   [cljs.test :as t :include-macros true]))

(def ^:private sample-highlights
  (str "# HIGHLIGHTS\n"
       "\n"
       "## 2.18.0 (Unreleased)\n"
       "\n"
       "- To do\n"
       "\n"
       "## 2.17.2\n"
       "\n"
       "- Background blur is here\n"
       "- WebGL rendering gets stronger\n"
       "\n"
       "## 2.17.1\n"
       "\n"
       "- MCP connection status and more\n"
       "- Design tokens: more visible, more user-friendly\n"))

(t/deftest parse-latest-released-version-skips-unreleased
  (t/is (= "2.17.2" (dcu/parse-latest-released-version sample-highlights))))

(t/deftest parse-latest-released-version-first-released
  (t/is (= "2.17.2"
           (dcu/parse-latest-released-version
            "## 2.17.2\n\n- Fix\n\n## 2.17.1\n\n- Fix\n"))))

(t/deftest parse-latest-released-version-only-unreleased
  (t/is (nil? (dcu/parse-latest-released-version
               "## 2.18.0 (Unreleased)\n\n- WIP\n"))))

(t/deftest parse-latest-released-version-empty
  (t/is (nil? (dcu/parse-latest-released-version "")))
  (t/is (nil? (dcu/parse-latest-released-version "# HIGHLIGHTS\n"))))

(t/deftest parse-highlights-skips-unreleased-and-collects-bullets
  (t/is (= [{:version "2.17.2"
             :items ["Background blur is here"
                     "WebGL rendering gets stronger"]}
            {:version "2.17.1"
             :items ["MCP connection status and more"
                     "Design tokens: more visible, more user-friendly"]}]
           (dcu/parse-highlights sample-highlights))))

(t/deftest version-compare
  (t/is (zero? (v/compare-versions "2.17.1" "2.17.1")))
  (t/is (pos? (v/compare-versions "2.17.2" "2.17.1")))
  (t/is (neg? (v/compare-versions "2.17.1" "2.17.2")))
  (t/is (pos? (v/compare-versions "3.0.0" "2.99.99")))
  (t/is (neg? (v/compare-versions "2.17.2" "2.17.10"))))

(t/deftest highlights-until-installed
  (let [sections (dcu/parse-highlights sample-highlights)]
    (t/is (= [{:version "2.17.2"
               :items ["Background blur is here"
                       "WebGL rendering gets stronger"]}]
             (dcu/highlights-until-installed sections "2.17.1")))
    (t/is (= [] (dcu/highlights-until-installed sections "2.17.2")))
    (t/is (= sections (dcu/highlights-until-installed sections "2.16.0")))
    (t/is (= [] (dcu/highlights-until-installed sections "2.17.10")))))

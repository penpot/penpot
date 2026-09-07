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

(def ^:private sample-changes
  (str "# CHANGELOG\n"
       "\n"
       "## 2.18.0 (Unreleased)\n"
       "\n"
       "### :sparkles: New features & Enhancements\n"
       "\n"
       "- Something WIP\n"
       "\n"
       "## 2.17.0\n"
       "\n"
       "### :rocket: Epics and highlights\n"
       "\n"
       "- Background blur [#9844](https://github.com/penpot/penpot/issues/9844) (PR: [#10034](https://github.com/penpot/penpot/pull/10034))\n"
       "- WebGL rendering [#10068](https://github.com/penpot/penpot/issues/10068) (PR: [#10014](https://github.com/penpot/penpot/pull/10014))\n"
       "\n"
       "### :sparkles: New features & Enhancements\n"
       "\n"
       "- Other stuff\n"
       "\n"
       "## 2.16.0\n"
       "\n"
       "### :rocket: Epics and highlights\n"
       "\n"
       "### :sparkles: New features & Enhancements\n"
       "\n"
       "- Tokens stuff\n"
       "\n"
       "## 2.15.0\n"
       "\n"
       "### :sparkles: New features & Enhancements\n"
       "\n"
       "- MCP server\n"))

;; --- parse-latest-released-version ---

(t/deftest parse-latest-released-version-from-changes
  (t/is (= "2.17.0" (dcu/parse-latest-released-version sample-changes))))

(t/deftest parse-latest-released-version-first-released
  (t/is (= "2.17.0"
           (dcu/parse-latest-released-version
            "## 2.17.0\n\n### :sparkles:\n\n- Fix\n\n## 2.16.0\n\n### :sparkles:\n\n- Fix\n"))))

(t/deftest parse-latest-released-version-only-unreleased
  (t/is (nil? (dcu/parse-latest-released-version
               "## 2.18.0 (Unreleased)\n\n- WIP\n"))))

(t/deftest parse-latest-released-version-empty
  (t/is (nil? (dcu/parse-latest-released-version "")))
  (t/is (nil? (dcu/parse-latest-released-version "# CHANGELOG\n"))))

;; --- parse-highlights ---

(t/deftest parse-highlights-extracts-rocket-items
  (let [result (dcu/parse-highlights sample-changes)]
    (t/is (= [{:version "2.17.0"
               :items   ["Background blur [#9844](https://github.com/penpot/penpot/issues/9844) (PR: [#10034](https://github.com/penpot/penpot/pull/10034))"
                         "WebGL rendering [#10068](https://github.com/penpot/penpot/issues/10068) (PR: [#10014](https://github.com/penpot/penpot/pull/10014))"]}]
             result))))

(t/deftest parse-highlights-skips-empty-rocket
  ;; 2.16.0 has an empty :rocket: section — should be skipped
  (let [result (dcu/parse-highlights sample-changes)]
    (t/is (not (some #(= "2.16.0" (:version %)) result)))))

(t/deftest parse-highlights-skips-missing-rocket
  ;; 2.15.0 has no :rocket: section — should be skipped
  (let [result (dcu/parse-highlights sample-changes)]
    (t/is (not (some #(= "2.15.0" (:version %)) result)))))

(t/deftest parse-highlights-skips-unreleased
  ;; 2.18.0 (Unreleased) should be skipped
  (let [result (dcu/parse-highlights sample-changes)]
    (t/is (not (some #(= "2.18.0" (:version %)) result)))))

(t/deftest parse-highlights-empty-input
  (t/is (= [] (dcu/parse-highlights "")))
  (t/is (= [] (dcu/parse-highlights nil)))
  (t/is (= [] (dcu/parse-highlights 42))))

(t/deftest parse-highlights-extracts-multiple-versions
  (let [input (str "## 2.17.0\n\n### :rocket: Epics and highlights\n\n"
                   "- Feature A [#1](https://github.com/penpot/penpot/issues/1)\n\n"
                   "## 2.16.0\n\n### :rocket: Epics and highlights\n\n"
                   "- Feature B [#2](https://github.com/penpot/penpot/issues/2)\n")
        result (dcu/parse-highlights input)]
    (t/is (= 2 (count result)))
    (t/is (= "2.17.0" (:version (first result))))
    (t/is (= "2.16.0" (:version (second result))))))

;; --- version-compare ---

(t/deftest version-compare
  (t/is (zero? (v/compare-versions "2.17.0" "2.17.0")))
  (t/is (pos? (v/compare-versions "2.17.0" "2.16.0")))
  (t/is (neg? (v/compare-versions "2.16.0" "2.17.0")))
  (t/is (pos? (v/compare-versions "3.0.0" "2.99.99")))
  (t/is (neg? (v/compare-versions "2.17.0" "2.17.10"))))

;; --- highlights-until-installed ---

(t/deftest highlights-until-installed
  (let [sections (dcu/parse-highlights sample-changes)]
    ;; installed 2.16.0 → shows 2.17.0 highlights
    (t/is (= [{:version "2.17.0"
               :items   ["Background blur [#9844](https://github.com/penpot/penpot/issues/9844) (PR: [#10034](https://github.com/penpot/penpot/pull/10034))"
                         "WebGL rendering [#10068](https://github.com/penpot/penpot/issues/10068) (PR: [#10014](https://github.com/penpot/penpot/pull/10014))"]}]
             (dcu/highlights-until-installed sections "2.16.0")))
    ;; installed 2.17.0 → no newer highlights
    (t/is (= [] (dcu/highlights-until-installed sections "2.17.0")))
    ;; installed older version → shows all available highlights
    (t/is (= sections (dcu/highlights-until-installed sections "2.14.0")))
    ;; installed newer than any highlight → empty
    (t/is (= [] (dcu/highlights-until-installed sections "2.99.0")))))

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

;; --- parse-highlight-item ---

(t/deftest parse-highlight-item-link
  (t/is (= [{:type :text :text "See "}
            {:type :link :text "#9844"
             :href "https://github.com/penpot/penpot/issues/9844"}]
           (dcu/parse-highlight-item
            "See [#9844](https://github.com/penpot/penpot/issues/9844)"))))

(t/deftest parse-highlight-item-bold
  (t/is (= [{:type :bold :text "New plugin system."}]
           (dcu/parse-highlight-item "**New plugin system.**"))))

(t/deftest parse-highlight-item-mixed-real-line
  (t/is (= [{:type :text :text "Background blur "}
            {:type :link :text "#9844"
             :href "https://github.com/penpot/penpot/issues/9844"}
            {:type :text :text " (PR: "}
            {:type :link :text "#10034"
             :href "https://github.com/penpot/penpot/pull/10034"}
            {:type :text :text ")"}]
           (dcu/parse-highlight-item
            "Background blur [#9844](https://github.com/penpot/penpot/issues/9844) (PR: [#10034](https://github.com/penpot/penpot/pull/10034))"))))

(t/deftest parse-highlight-item-plain-text
  (t/is (= [{:type :text :text "Just plain text, no markdown"}]
           (dcu/parse-highlight-item "Just plain text, no markdown"))))

(t/deftest parse-highlight-item-empty
  (t/is (= [] (dcu/parse-highlight-item "")))
  (t/is (= [] (dcu/parse-highlight-item nil))))

(t/deftest parse-highlight-item-unbalanced-fallback
  (t/is (= [{:type :text :text "Broken [link without end"}]
           (dcu/parse-highlight-item "Broken [link without end")))
  (t/is (= [{:type :text :text "Unclosed **bold"}]
           (dcu/parse-highlight-item "Unclosed **bold"))))

(t/deftest parse-highlight-item-non-http-url-is-plain-text
  (t/is (= [{:type :text :text "[click](javascript:alert(1))"}]
           (dcu/parse-highlight-item "[click](javascript:alert(1))"))))

;; --- parse-highlight-item edge cases ---

(t/deftest parse-highlight-item-taiga-link
  (t/is (= [{:type :text :text "Grid CSS layout "}
            {:type :link :text "Taiga #4915"
             :href "https://tree.taiga.io/project/penpot/epic/4915"}]
           (dcu/parse-highlight-item
            "Grid CSS layout [Taiga #4915](https://tree.taiga.io/project/penpot/epic/4915)"))))

(t/deftest parse-highlight-item-multi-pr-line
  (t/is (= [{:type :text :text "Add MCP "}
            {:type :link :text "#9174"
             :href "https://github.com/penpot/penpot/issues/9174"}
            {:type :text :text " (PR: "}
            {:type :link :text "#9032"
             :href "https://github.com/penpot/penpot/pull/9032"}
            {:type :text :text ", "}
            {:type :link :text "#9321"
             :href "https://github.com/penpot/penpot/pull/9321"}
            {:type :text :text ")"}]
           (dcu/parse-highlight-item
            "Add MCP [#9174](https://github.com/penpot/penpot/issues/9174) (PR: [#9032](https://github.com/penpot/penpot/pull/9032), [#9321](https://github.com/penpot/penpot/pull/9321))"))))

(t/deftest parse-highlight-item-link-only
  (t/is (= [{:type :link :text "#1"
             :href "https://github.com/penpot/penpot/issues/1"}]
           (dcu/parse-highlight-item
            "[#1](https://github.com/penpot/penpot/issues/1)"))))

(t/deftest parse-highlight-item-bold-adjacent-to-link
  (t/is (= [{:type :bold :text "Hot"}
            {:type :text :text " "}
            {:type :link :text "#1"
             :href "https://github.com/penpot/penpot/issues/1"}]
           (dcu/parse-highlight-item
            "**Hot** [#1](https://github.com/penpot/penpot/issues/1)"))))

(t/deftest parse-highlight-item-bold-inside-link-is-not-nested
  ;; Link wins; inner ** stays raw text (documented, no nesting)
  (t/is (= [{:type :link :text "a **b**"
             :href "https://github.com/penpot/penpot/issues/1"}]
           (dcu/parse-highlight-item
            "[a **b**](https://github.com/penpot/penpot/issues/1)"))))

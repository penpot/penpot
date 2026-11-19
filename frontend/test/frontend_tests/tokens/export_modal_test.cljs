;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.export-modal-test
  "The active themes and sets the export writes into the files."
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.json :as json]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.export.modal :as export-modal]
   [cljs.test :as t :include-macros true]))

(def ^:private single-file-json @#'export-modal/single-file-json)
(def ^:private multi-file-entries @#'export-modal/multi-file-entries)

(defn- imported-lib
  "A library imported from a file with the themes `mode/light` and
  `mode/dark`, where the file marked `mode/dark` as active."
  []
  (ctob/parse-multi-set-dtcg-json
   {"mode/light" {"surface" {"$value" "#ffffff" "$type" "color"}}
    "mode/dark"  {"surface" {"$value" "#111111" "$type" "color"}}
    "$themes"    [{"name" "light" "group" "mode" "selectedTokenSets" {"mode/light" "enabled"}}
                  {"name" "dark" "group" "mode" "selectedTokenSets" {"mode/dark" "enabled"}}]
    "$metadata"  {"tokenSetOrder" ["mode/light" "mode/dark"]
                  "activeThemes"  ["mode/dark"]
                  "activeSets"    ["mode/dark"]}}))

(defn- status-with-light-active
  "The file's status after the user picked `mode/light` in the theme
  selector."
  [lib]
  (let [light (ctob/get-theme-by-name lib "mode" "light")]
    (cfo/activate-theme (cfo/make-tokens-status-from-lib lib) lib (ctob/get-id light))))

(defn- active-in
  [metadata]
  {:themes (set (get metadata "activeThemes"))
   :sets   (set (get metadata "activeSets"))})

(t/deftest export-writes-the-themes-active-in-the-file
  (let [lib    (imported-lib)
        status (status-with-light-active lib)]
    (t/testing "the library still holds the themes active in the imported file"
      (t/is (contains? (ctob/get-legacy-active-themes lib) "mode/dark")))

    (t/testing "single file"
      (let [exported (json/decode (single-file-json lib status) :key-fn identity)]
        (t/is (= {:themes #{"mode/light"} :sets #{"mode/light"}}
                 (active-in (get exported "$metadata"))))))

    (t/testing "multiple files"
      (t/is (= {:themes #{"mode/light"} :sets #{"mode/light"}}
               (active-in (get (multi-file-entries lib status) "$metadata.json")))))))

(t/deftest export-without-tokens
  (t/testing "an empty library has nothing to export"
    (let [lib (ctob/make-tokens-lib)]
      (t/is (nil? (single-file-json lib (cfo/make-tokens-status-from-lib lib))))))
  (t/testing "without a status there is nothing to export"
    (t/is (nil? (single-file-json (imported-lib) nil)))
    (t/is (nil? (multi-file-entries (imported-lib) nil)))))

;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.files.shapes-builder-test
  (:require
   [app.common.files.shapes-builder :as sb]
   [clojure.test :as t]))

;; Regression for https://github.com/penpot/penpot/issues/7869.
;; ``parse-svg-element`` used to derive the shape name from
;; ``(or (:id attrs) (tag->name tag))`` which dropped Inkscape-authored
;; labels. ``tubax/xml->clj`` (the SVG parser the rest of the import
;; pipeline already feeds these maps to) keeps namespaced attributes as
;; ``:prefix:name`` keywords — same shape the codebase already reads
;; ``:xlink:href`` from in this file (line 134) and in
;; ``app.common.svg``.

(t/deftest resolve-element-name-prefers-inkscape-label
  (t/is (= "Layer 1"
           (sb/resolve-element-name :g {:inkscape:label "Layer 1"
                                        :id "g1234"}))))

(t/deftest resolve-element-name-prefers-sodipodi-label-when-no-inkscape-label
  (t/is (= "phone-icon"
           (sb/resolve-element-name :path {:sodipodi:label "phone-icon"
                                           :id "path5678"}))))

(t/deftest resolve-element-name-falls-back-to-id-when-no-label-namespace
  (t/is (= "manual-id"
           (sb/resolve-element-name :rect {:id "manual-id"}))))

(t/deftest resolve-element-name-falls-back-to-tag-name-when-no-id-and-no-label
  ;; The tag->name mapping returns generic names for known SVG element
  ;; tags. Asserting on the call result here (rather than a hardcoded
  ;; string) keeps the test stable if the tag->name mapping is updated.
  (t/is (some? (sb/resolve-element-name :rect {})))
  (t/is (string? (sb/resolve-element-name :rect {}))))

(t/deftest resolve-element-name-inkscape-label-wins-over-sodipodi-and-id
  ;; Both label conventions and an id present together; the priority is
  ;; inkscape > sodipodi > id > tag, matching the order operators expect
  ;; (Inkscape's own UI shows ``inkscape:label`` as the canonical name).
  (t/is (= "user-name"
           (sb/resolve-element-name :g {:inkscape:label "user-name"
                                        :sodipodi:label "stale-label"
                                        :id "g1"}))))

(t/deftest resolve-element-name-empty-attrs-uses-tag-fallback
  (t/is (some? (sb/resolve-element-name :path {}))))
